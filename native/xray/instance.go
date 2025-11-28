package xray

import (
	"fmt"
	"net/netip"
	"os/exec"
	"path/filepath"
	"sync"
)

// Instance represents an Xray-core instance
type Instance struct {
	config      *Config
	nativeLibDir string
	filesDir     string
	
	process     *exec.Cmd
	udpHandler  *UDPHandler
	
	running     bool
	mutex       sync.RWMutex
	stopChan    chan struct{}
	
	// UDP channels
	udpSendChan chan UDPPacket
	udpRecvChan chan []byte
}

// UDPPacket represents a UDP packet to send
type UDPPacket struct {
	Data   []byte
	Target netip.AddrPort
}

// NewInstance creates a new Xray instance
func NewInstance(configJSON, nativeLibDir, filesDir string) (*Instance, error) {
	config, err := ParseConfig(configJSON)
	if err != nil {
		return nil, fmt.Errorf("parse config: %w", err)
	}
	
	instance := &Instance{
		config:       config,
		nativeLibDir: nativeLibDir,
		filesDir:     filesDir,
		stopChan:     make(chan struct{}),
		udpSendChan:  make(chan UDPPacket, 1024),
		udpRecvChan:  make(chan []byte, 1024),
	}
	
	return instance, nil
}

// Start starts the Xray instance
func (i *Instance) Start() error {
	i.mutex.Lock()
	defer i.mutex.Unlock()
	
	if i.running {
		return fmt.Errorf("instance already running")
	}
	
	// For now, we'll use a placeholder implementation
	// In a real implementation, this would start the Xray-core process
	// or load the native library
	
	// Start UDP handler
	udpHandler, err := NewUDPHandler(i)
	if err != nil {
		return fmt.Errorf("create udp handler: %w", err)
	}
	
	if err := udpHandler.Start(); err != nil {
		return fmt.Errorf("start udp handler: %w", err)
	}
	
	i.udpHandler = udpHandler
	i.running = true
	
	// Start packet processing goroutines
	go i.processUDPPackets()
	
	return nil
}

// Stop stops the Xray instance
func (i *Instance) Stop() {
	i.mutex.Lock()
	defer i.mutex.Unlock()
	
	if !i.running {
		return
	}
	
	close(i.stopChan)
	
	if i.udpHandler != nil {
		i.udpHandler.Stop()
		i.udpHandler = nil
	}
	
	if i.process != nil {
		i.process.Process.Kill()
		i.process = nil
	}
	
	i.running = false
}

// SendUDP sends a UDP packet through Xray
func (i *Instance) SendUDP(data []byte, target netip.AddrPort) {
	i.mutex.RLock()
	defer i.mutex.RUnlock()
	
	if !i.running {
		return
	}
	
	select {
	case i.udpSendChan <- UDPPacket{Data: data, Target: target}:
	default:
		// Channel full, drop packet
	}
}

// ReceiveUDP returns the channel for receiving UDP packets
func (i *Instance) ReceiveUDP() <-chan []byte {
	return i.udpRecvChan
}

// processUDPPackets processes UDP packets
func (i *Instance) processUDPPackets() {
	for {
		select {
		case <-i.stopChan:
			return
		case packet := <-i.udpSendChan:
			// In a real implementation, this would forward the packet
			// through Xray-core. For now, we'll just echo it back
			// as a placeholder.
			select {
			case i.udpRecvChan <- packet.Data:
			default:
				// Channel full, drop packet
			}
		}
	}
}

// GetConfigPath returns the path to the Xray config file
func (i *Instance) GetConfigPath() string {
	return filepath.Join(i.filesDir, "xray_config.json")
}

// GetGeoIPPath returns the path to geoip.dat
func (i *Instance) GetGeoIPPath() string {
	return filepath.Join(i.filesDir, "geoip.dat")
}

// GetGeoSitePath returns the path to geosite.dat
func (i *Instance) GetGeoSitePath() string {
	return filepath.Join(i.filesDir, "geosite.dat")
}
