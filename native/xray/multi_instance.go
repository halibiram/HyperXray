package xray

import (
	"encoding/json"
	"fmt"
	"sync"
	"sync/atomic"
	"time"
)

// InstanceStatus represents the status of an Xray instance
type InstanceStatus string

const (
	StatusStopped  InstanceStatus = "stopped"
	StatusStarting InstanceStatus = "starting"
	StatusRunning  InstanceStatus = "running"
	StatusStopping InstanceStatus = "stopping"
	StatusError    InstanceStatus = "error"
)

// InstanceInfo holds information about a running instance
type InstanceInfo struct {
	Index       int            `json:"index"`
	Status      InstanceStatus `json:"status"`
	ApiPort     int            `json:"apiPort"`
	StartTime   int64          `json:"startTime"`
	ErrorMsg    string         `json:"errorMsg,omitempty"`
	TxBytes     int64          `json:"txBytes"`
	RxBytes     int64          `json:"rxBytes"`
	Connections int            `json:"connections"`
}

// ManagedInstance wraps an Xray instance with management metadata
type ManagedInstance struct {
	Instance   *Instance
	Info       InstanceInfo
	ConfigJSON string
	StopChan   chan struct{}
}

// MultiInstanceManager manages multiple Xray instances
type MultiInstanceManager struct {
	instances     map[int]*ManagedInstance
	mutex         sync.RWMutex
	maxInstances  int
	baseApiPort   int
	nextInstanceId int32
	nativeLibDir  string
	filesDir      string
	
	// Load balancing
	loadBalanceCounter int32
	
	// Callbacks
	onStatusChange func(index int, status InstanceStatus, errorMsg string)
	onLogLine      func(index int, line string)
}

// NewMultiInstanceManager creates a new multi-instance manager
func NewMultiInstanceManager(nativeLibDir, filesDir string, maxInstances int) *MultiInstanceManager {
	if maxInstances <= 0 {
		maxInstances = 4
	}
	if maxInstances > 8 {
		maxInstances = 8 // Cap at 8 instances
	}
	
	return &MultiInstanceManager{
		instances:    make(map[int]*ManagedInstance),
		maxInstances: maxInstances,
		baseApiPort:  10085,
		nativeLibDir: nativeLibDir,
		filesDir:     filesDir,
	}
}

// SetStatusCallback sets the callback for status changes
func (m *MultiInstanceManager) SetStatusCallback(callback func(index int, status InstanceStatus, errorMsg string)) {
	m.mutex.Lock()
	defer m.mutex.Unlock()
	m.onStatusChange = callback
}

// SetLogCallback sets the callback for log lines
func (m *MultiInstanceManager) SetLogCallback(callback func(index int, line string)) {
	m.mutex.Lock()
	defer m.mutex.Unlock()
	m.onLogLine = callback
}

// StartInstances starts the specified number of Xray instances
func (m *MultiInstanceManager) StartInstances(count int, configJSON string, excludedPorts []int) (map[int]int, error) {
	if count <= 0 {
		count = 1
	}
	if count > m.maxInstances {
		count = m.maxInstances
	}
	
	m.mutex.Lock()
	defer m.mutex.Unlock()
	
	// Stop existing instances first
	m.stopAllInstancesLocked()
	
	result := make(map[int]int)
	excludedPortsMap := make(map[int]bool)
	for _, p := range excludedPorts {
		excludedPortsMap[p] = true
	}
	
	for i := 0; i < count; i++ {
		// Find available API port
		apiPort := m.findAvailablePort(excludedPortsMap)
		if apiPort == 0 {
			// Clean up started instances on error
			m.stopAllInstancesLocked()
			return nil, fmt.Errorf("no available port for instance %d", i)
		}
		excludedPortsMap[apiPort] = true
		
		// Modify config for this instance (change API port)
		instanceConfig, err := m.modifyConfigForInstance(configJSON, i, apiPort)
		if err != nil {
			m.stopAllInstancesLocked()
			return nil, fmt.Errorf("failed to modify config for instance %d: %w", i, err)
		}
		
		// Create instance
		instance, err := NewInstance(instanceConfig, m.nativeLibDir, m.filesDir)
		if err != nil {
			m.stopAllInstancesLocked()
			return nil, fmt.Errorf("failed to create instance %d: %w", i, err)
		}
		
		managed := &ManagedInstance{
			Instance:   instance,
			ConfigJSON: instanceConfig,
			StopChan:   make(chan struct{}),
			Info: InstanceInfo{
				Index:     i,
				Status:    StatusStarting,
				ApiPort:   apiPort,
				StartTime: time.Now().UnixMilli(),
			},
		}
		
		m.instances[i] = managed
		m.notifyStatusChange(i, StatusStarting, "")
		
		// Start instance
		if err := instance.Start(); err != nil {
			managed.Info.Status = StatusError
			managed.Info.ErrorMsg = err.Error()
			m.notifyStatusChange(i, StatusError, err.Error())
			m.stopAllInstancesLocked()
			return nil, fmt.Errorf("failed to start instance %d: %w", i, err)
		}
		
		managed.Info.Status = StatusRunning
		m.notifyStatusChange(i, StatusRunning, "")
		result[i] = apiPort
	}
	
	return result, nil
}

// StopInstance stops a specific instance
func (m *MultiInstanceManager) StopInstance(index int) error {
	m.mutex.Lock()
	defer m.mutex.Unlock()
	
	managed, exists := m.instances[index]
	if !exists {
		return fmt.Errorf("instance %d not found", index)
	}
	
	m.stopInstanceLocked(managed)
	delete(m.instances, index)
	
	return nil
}

// StopAllInstances stops all running instances
func (m *MultiInstanceManager) StopAllInstances() {
	m.mutex.Lock()
	defer m.mutex.Unlock()
	m.stopAllInstancesLocked()
}

// stopAllInstancesLocked stops all instances (must be called with mutex held)
func (m *MultiInstanceManager) stopAllInstancesLocked() {
	for idx, managed := range m.instances {
		m.stopInstanceLocked(managed)
		delete(m.instances, idx)
	}
}

// stopInstanceLocked stops a single instance (must be called with mutex held)
func (m *MultiInstanceManager) stopInstanceLocked(managed *ManagedInstance) {
	if managed == nil {
		return
	}
	
	managed.Info.Status = StatusStopping
	m.notifyStatusChange(managed.Info.Index, StatusStopping, "")
	
	// Signal stop
	select {
	case <-managed.StopChan:
		// Already closed
	default:
		close(managed.StopChan)
	}
	
	// Stop the instance
	if managed.Instance != nil {
		managed.Instance.Stop()
	}
	
	managed.Info.Status = StatusStopped
	m.notifyStatusChange(managed.Info.Index, StatusStopped, "")
}

// GetInstanceStatus returns the status of a specific instance
func (m *MultiInstanceManager) GetInstanceStatus(index int) (*InstanceInfo, error) {
	m.mutex.RLock()
	defer m.mutex.RUnlock()
	
	managed, exists := m.instances[index]
	if !exists {
		return nil, fmt.Errorf("instance %d not found", index)
	}
	
	// Copy to avoid race conditions
	info := managed.Info
	return &info, nil
}

// GetAllInstancesStatus returns the status of all instances
func (m *MultiInstanceManager) GetAllInstancesStatus() map[int]InstanceInfo {
	m.mutex.RLock()
	defer m.mutex.RUnlock()
	
	result := make(map[int]InstanceInfo)
	for idx, managed := range m.instances {
		result[idx] = managed.Info
	}
	return result
}

// GetAllInstancesStatusJSON returns status as JSON string
func (m *MultiInstanceManager) GetAllInstancesStatusJSON() string {
	status := m.GetAllInstancesStatus()
	jsonBytes, err := json.Marshal(status)
	if err != nil {
		return "{}"
	}
	return string(jsonBytes)
}

// GetInstanceCount returns the number of running instances
func (m *MultiInstanceManager) GetInstanceCount() int {
	m.mutex.RLock()
	defer m.mutex.RUnlock()
	return len(m.instances)
}

// GetNextInstance returns the next instance for load balancing (round-robin)
func (m *MultiInstanceManager) GetNextInstance() (*ManagedInstance, error) {
	m.mutex.RLock()
	defer m.mutex.RUnlock()
	
	if len(m.instances) == 0 {
		return nil, fmt.Errorf("no running instances")
	}
	
	// Round-robin load balancing
	counter := atomic.AddInt32(&m.loadBalanceCounter, 1)
	index := int(counter) % len(m.instances)
	
	// Find the instance at this index
	i := 0
	for _, managed := range m.instances {
		if i == index && managed.Info.Status == StatusRunning {
			return managed, nil
		}
		i++
	}
	
	// Fallback: return first running instance
	for _, managed := range m.instances {
		if managed.Info.Status == StatusRunning {
			return managed, nil
		}
	}
	
	return nil, fmt.Errorf("no running instances available")
}

// GetInstanceByPort returns the instance running on the specified API port
func (m *MultiInstanceManager) GetInstanceByPort(apiPort int) (*ManagedInstance, error) {
	m.mutex.RLock()
	defer m.mutex.RUnlock()
	
	for _, managed := range m.instances {
		if managed.Info.ApiPort == apiPort {
			return managed, nil
		}
	}
	
	return nil, fmt.Errorf("instance with API port %d not found", apiPort)
}

// findAvailablePort finds an available port for an instance
func (m *MultiInstanceManager) findAvailablePort(excluded map[int]bool) int {
	for port := m.baseApiPort; port < m.baseApiPort+1000; port++ {
		if !excluded[port] {
			// TODO: Actually check if port is available using net.Listen
			return port
		}
	}
	return 0
}

// modifyConfigForInstance modifies the Xray config for a specific instance
func (m *MultiInstanceManager) modifyConfigForInstance(configJSON string, index, apiPort int) (string, error) {
	var config map[string]interface{}
	if err := json.Unmarshal([]byte(configJSON), &config); err != nil {
		return "", fmt.Errorf("failed to parse config: %w", err)
	}
	
	// Ensure API inbound exists and update port
	inbounds, ok := config["inbounds"].([]interface{})
	if !ok {
		inbounds = []interface{}{}
	}
	
	// Find or create API inbound
	apiInboundFound := false
	for i, inbound := range inbounds {
		if inboundMap, ok := inbound.(map[string]interface{}); ok {
			if tag, _ := inboundMap["tag"].(string); tag == "api" {
				inboundMap["port"] = apiPort
				inbounds[i] = inboundMap
				apiInboundFound = true
				break
			}
		}
	}
	
	if !apiInboundFound {
		// Add API inbound
		apiInbound := map[string]interface{}{
			"tag":      "api",
			"port":     apiPort,
			"listen":   "127.0.0.1",
			"protocol": "dokodemo-door",
			"settings": map[string]interface{}{
				"address": "127.0.0.1",
			},
		}
		inbounds = append(inbounds, apiInbound)
	}
	
	config["inbounds"] = inbounds
	
	// Add instance identifier to log settings
	if log, ok := config["log"].(map[string]interface{}); ok {
		log["instanceIndex"] = index
	}
	
	modifiedJSON, err := json.Marshal(config)
	if err != nil {
		return "", fmt.Errorf("failed to marshal config: %w", err)
	}
	
	return string(modifiedJSON), nil
}

// notifyStatusChange notifies the callback about status changes
func (m *MultiInstanceManager) notifyStatusChange(index int, status InstanceStatus, errorMsg string) {
	if m.onStatusChange != nil {
		go m.onStatusChange(index, status, errorMsg)
	}
}

// UpdateInstanceStats updates the stats for an instance
func (m *MultiInstanceManager) UpdateInstanceStats(index int, txBytes, rxBytes int64, connections int) {
	m.mutex.Lock()
	defer m.mutex.Unlock()
	
	if managed, exists := m.instances[index]; exists {
		managed.Info.TxBytes = txBytes
		managed.Info.RxBytes = rxBytes
		managed.Info.Connections = connections
	}
}

// IsRunning returns true if there are any running instances
func (m *MultiInstanceManager) IsRunning() bool {
	m.mutex.RLock()
	defer m.mutex.RUnlock()
	
	for _, managed := range m.instances {
		if managed.Info.Status == StatusRunning {
			return true
		}
	}
	return false
}





