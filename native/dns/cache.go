package dns

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

const (
	DefaultTTL        = 86400    // 24 hours in seconds
	PopularDomainTTL  = 172800   // 48 hours for popular domains
	DynamicDomainTTL  = 86400    // 24 hours for dynamic domains (CDN, etc.)
	MaxEntries        = 10000
	CacheVersion      = 1
	MemoryLimitMB     = 10
	MovingAvgAlpha    = 0.1      // Exponential moving average factor
	TopDomainCount    = 10
	SaveDebounceMs    = 200
)

// Popular domains that rarely change - use longer TTL
var popularDomains = map[string]bool{
	"google.com":     true,
	"www.google.com": true,
	"googleapis.com": true,
	"facebook.com":   true,
	"youtube.com":    true,
	"instagram.com":  true,
	"twitter.com":    true,
	"amazon.com":     true,
	"microsoft.com":  true,
	"apple.com":      true,
}

// CacheEntry represents a DNS cache entry
type CacheEntry struct {
	IPs       []string `json:"ips"`
	Timestamp int64    `json:"timestamp"` // Unix timestamp in seconds
	TTL       int64    `json:"ttl"`       // TTL in seconds
}

// IsExpired checks if the entry has expired
func (e *CacheEntry) IsExpired() bool {
	currentTime := time.Now().Unix()
	return (currentTime - e.Timestamp) > e.TTL
}

// ExpiryTime returns the expiry time as Unix timestamp
func (e *CacheEntry) ExpiryTime() int64 {
	return e.Timestamp + e.TTL
}

// EstimateMemoryBytes estimates memory usage
func (e *CacheEntry) EstimateMemoryBytes(hostname string) int64 {
	hostnameSize := int64(len(hostname) * 2) // UTF-16 encoding
	var ipsSize int64
	for _, ip := range e.IPs {
		ipsSize += int64(len(ip) * 2)
	}
	metadataOverhead := int64(64)
	return hostnameSize + ipsSize + metadataOverhead
}

// DomainStats tracks hit/miss statistics for a domain
type DomainStats struct {
	Hits   int64 `json:"hits"`
	Misses int64 `json:"misses"`
}

// CacheMetrics contains all statistics for dashboard display
type CacheMetrics struct {
	EntryCount         int                      `json:"entryCount"`
	TotalLookups       int64                    `json:"totalLookups"`
	Hits               int64                    `json:"hits"`
	Misses             int64                    `json:"misses"`
	HitRate            int                      `json:"hitRate"` // 0-100 percentage
	MemoryUsageBytes   int64                    `json:"memoryUsageBytes"`
	MemoryLimitBytes   int64                    `json:"memoryLimitBytes"`
	MemoryUsagePercent int                      `json:"memoryUsagePercent"`
	AvgHitLatencyMs    float64                  `json:"avgHitLatencyMs"`
	AvgMissLatencyMs   float64                  `json:"avgMissLatencyMs"`
	AvgDomainHitRate   int                      `json:"avgDomainHitRate"`
	TopDomains         []DomainHitRate          `json:"topDomains"`
	AvgTTLSeconds      int64                    `json:"avgTtlSeconds"`
	ActiveEntries      []CacheEntryDisplay      `json:"activeEntries"`
	UpdateTimestamp    int64                    `json:"updateTimestamp"`
}

// DomainHitRate contains hit rate info for a domain
type DomainHitRate struct {
	Domain  string `json:"domain"`
	Hits    int64  `json:"hits"`
	Misses  int64  `json:"misses"`
	HitRate int    `json:"hitRate"`
}

// CacheEntryDisplay is for UI display
type CacheEntryDisplay struct {
	Domain     string   `json:"domain"`
	IPs        []string `json:"ips"`
	ExpiryTime int64    `json:"expiryTime"`
}

// DNSCacheManager manages the DNS cache
type DNSCacheManager struct {
	cache      map[string]*CacheEntry
	cacheMutex sync.RWMutex
	
	// Statistics
	totalLookups int64
	cacheHits    int64
	cacheMisses  int64
	
	// TTL tracking
	totalTTLSeconds int64
	ttlSampleCount  int64
	
	// Latency tracking (exponential moving average)
	avgHitLatency  float64
	avgMissLatency float64
	latencyMutex   sync.Mutex
	
	// Domain stats
	domainStats      map[string]*DomainStats
	domainStatsMutex sync.RWMutex
	
	// File persistence
	cacheDir     string
	cacheFile    string
	saveChannel  chan struct{}
	saveMutex    sync.Mutex
	
	// Status
	initialized bool
	stopChan    chan struct{}
}

// Global instance
var (
	globalCacheManager *DNSCacheManager
	cacheManagerMutex  sync.Mutex
)

// GetCacheManager returns the global DNS cache manager instance
func GetCacheManager() *DNSCacheManager {
	cacheManagerMutex.Lock()
	defer cacheManagerMutex.Unlock()
	
	if globalCacheManager == nil {
		globalCacheManager = &DNSCacheManager{
			cache:       make(map[string]*CacheEntry),
			domainStats: make(map[string]*DomainStats),
			saveChannel: make(chan struct{}, 100),
			stopChan:    make(chan struct{}),
		}
	}
	return globalCacheManager
}

// Initialize initializes the DNS cache manager
func (m *DNSCacheManager) Initialize(cacheDir string) error {
	m.cacheMutex.Lock()
	defer m.cacheMutex.Unlock()
	
	if m.initialized {
		return nil
	}
	
	m.cacheDir = cacheDir
	m.cacheFile = filepath.Join(cacheDir, "dns_cache_native.json")
	
	// Load existing cache
	if err := m.loadFromFile(); err != nil {
		// Not a critical error, start with empty cache
		fmt.Printf("DNS Cache: Could not load existing cache: %v\n", err)
	}
	
	// Start debounced save goroutine
	go m.debouncedSaveLoop()
	
	m.initialized = true
	fmt.Printf("DNS Cache: Initialized with %d entries\n", len(m.cache))
	
	return nil
}

// Lookup looks up a hostname in the cache
func (m *DNSCacheManager) Lookup(hostname string) ([]net.IP, bool) {
	startTime := time.Now()
	atomic.AddInt64(&m.totalLookups, 1)
	
	m.cacheMutex.RLock()
	entry, exists := m.cache[hostname]
	m.cacheMutex.RUnlock()
	
	if exists && !entry.IsExpired() {
		// Cache hit
		atomic.AddInt64(&m.cacheHits, 1)
		m.recordDomainAccess(hostname, true)
		
		var ips []net.IP
		for _, ipStr := range entry.IPs {
			if ip := net.ParseIP(ipStr); ip != nil {
				ips = append(ips, ip)
			}
		}
		
		if len(ips) > 0 {
			latencyMs := float64(time.Since(startTime).Nanoseconds()) / 1e6
			m.recordHitLatency(latencyMs)
			return ips, true
		}
	}
	
	// Cache miss or expired
	if exists && entry.IsExpired() {
		m.cacheMutex.Lock()
		delete(m.cache, hostname)
		m.cacheMutex.Unlock()
	}
	
	atomic.AddInt64(&m.cacheMisses, 1)
	m.recordDomainAccess(hostname, false)
	
	latencyMs := float64(time.Since(startTime).Nanoseconds()) / 1e6
	m.recordMissLatency(latencyMs)
	
	return nil, false
}

// Save saves a DNS resolution to cache
func (m *DNSCacheManager) Save(hostname string, ips []net.IP, ttl int64) {
	if len(ips) == 0 {
		return
	}
	
	if ttl <= 0 {
		ttl = m.getOptimizedTTL(hostname)
	}
	
	ipStrings := make([]string, 0, len(ips))
	for _, ip := range ips {
		ipStrings = append(ipStrings, ip.String())
	}
	
	entry := &CacheEntry{
		IPs:       ipStrings,
		Timestamp: time.Now().Unix(),
		TTL:       ttl,
	}
	
	// Track TTL for average
	atomic.AddInt64(&m.totalTTLSeconds, ttl)
	atomic.AddInt64(&m.ttlSampleCount, 1)
	
	m.cacheMutex.Lock()
	
	// Remove oldest entries if cache is full
	if len(m.cache) >= MaxEntries {
		var oldestKey string
		var oldestTime int64 = time.Now().Unix()
		for k, v := range m.cache {
			if v.Timestamp < oldestTime {
				oldestTime = v.Timestamp
				oldestKey = k
			}
		}
		if oldestKey != "" {
			delete(m.cache, oldestKey)
		}
	}
	
	m.cache[hostname] = entry
	m.cacheMutex.Unlock()
	
	// Trigger debounced save
	select {
	case m.saveChannel <- struct{}{}:
	default:
	}
}

// SaveFromStrings saves DNS resolution from string IPs
func (m *DNSCacheManager) SaveFromStrings(hostname string, ipStrings []string, ttl int64) {
	var ips []net.IP
	for _, ipStr := range ipStrings {
		if ip := net.ParseIP(ipStr); ip != nil {
			ips = append(ips, ip)
		}
	}
	m.Save(hostname, ips, ttl)
}

// getOptimizedTTL returns optimized TTL based on domain type
func (m *DNSCacheManager) getOptimizedTTL(hostname string) int64 {
	// Check popular domains
	if popularDomains[hostname] {
		return PopularDomainTTL
	}
	
	// Check if any parent domain is popular
	for domain := range popularDomains {
		if len(hostname) > len(domain) && hostname[len(hostname)-len(domain)-1:] == "."+domain {
			return PopularDomainTTL
		}
	}
	
	// Default TTL
	return DefaultTTL
}

// recordHitLatency records latency for cache hit
func (m *DNSCacheManager) recordHitLatency(latencyMs float64) {
	m.latencyMutex.Lock()
	defer m.latencyMutex.Unlock()
	
	if m.avgHitLatency == 0 {
		m.avgHitLatency = latencyMs
	} else {
		m.avgHitLatency = MovingAvgAlpha*latencyMs + (1-MovingAvgAlpha)*m.avgHitLatency
	}
}

// recordMissLatency records latency for cache miss
func (m *DNSCacheManager) recordMissLatency(latencyMs float64) {
	m.latencyMutex.Lock()
	defer m.latencyMutex.Unlock()
	
	if m.avgMissLatency == 0 {
		m.avgMissLatency = latencyMs
	} else {
		m.avgMissLatency = MovingAvgAlpha*latencyMs + (1-MovingAvgAlpha)*m.avgMissLatency
	}
}

// recordDomainAccess records domain access for statistics
func (m *DNSCacheManager) recordDomainAccess(domain string, isHit bool) {
	m.domainStatsMutex.Lock()
	defer m.domainStatsMutex.Unlock()
	
	stats, exists := m.domainStats[domain]
	if !exists {
		stats = &DomainStats{}
		m.domainStats[domain] = stats
	}
	
	if isHit {
		stats.Hits++
	} else {
		stats.Misses++
	}
	
	// Limit domain tracking
	if len(m.domainStats) > TopDomainCount*2 {
		m.pruneDomainsStats()
	}
}

// pruneDomainsStats removes least accessed domains
func (m *DNSCacheManager) pruneDomainsStats() {
	type domainTotal struct {
		domain string
		total  int64
	}
	
	var domains []domainTotal
	for d, s := range m.domainStats {
		domains = append(domains, domainTotal{d, s.Hits + s.Misses})
	}
	
	sort.Slice(domains, func(i, j int) bool {
		return domains[i].total > domains[j].total
	})
	
	// Keep only top domains
	keep := make(map[string]bool)
	for i := 0; i < TopDomainCount && i < len(domains); i++ {
		keep[domains[i].domain] = true
	}
	
	for domain := range m.domainStats {
		if !keep[domain] {
			delete(m.domainStats, domain)
		}
	}
}

// GetMetrics returns current cache metrics
func (m *DNSCacheManager) GetMetrics() *CacheMetrics {
	m.cacheMutex.RLock()
	entryCount := len(m.cache)
	
	var memoryUsageBytes int64
	var activeEntries []CacheEntryDisplay
	
	// Sort entries by timestamp descending
	type entryWithKey struct {
		key   string
		entry *CacheEntry
	}
	var entries []entryWithKey
	for k, v := range m.cache {
		entries = append(entries, entryWithKey{k, v})
	}
	sort.Slice(entries, func(i, j int) bool {
		return entries[i].entry.Timestamp > entries[j].entry.Timestamp
	})
	
	// Get top 100 entries
	count := 100
	if len(entries) < count {
		count = len(entries)
	}
	for i := 0; i < count; i++ {
		e := entries[i]
		memoryUsageBytes += e.entry.EstimateMemoryBytes(e.key)
		activeEntries = append(activeEntries, CacheEntryDisplay{
			Domain:     e.key,
			IPs:        e.entry.IPs,
			ExpiryTime: e.entry.ExpiryTime(),
		})
	}
	m.cacheMutex.RUnlock()
	
	// Get statistics
	hits := atomic.LoadInt64(&m.cacheHits)
	misses := atomic.LoadInt64(&m.cacheMisses)
	total := atomic.LoadInt64(&m.totalLookups)
	
	hitRate := 0
	if total > 0 {
		hitRate = int(float64(hits) / float64(total) * 100)
	}
	
	// Average TTL
	ttlCount := atomic.LoadInt64(&m.ttlSampleCount)
	var avgTTL int64
	if ttlCount > 0 {
		avgTTL = atomic.LoadInt64(&m.totalTTLSeconds) / ttlCount
	}
	
	// Memory
	memoryLimitBytes := int64(MemoryLimitMB * 1024 * 1024)
	memoryPercent := 0
	if memoryLimitBytes > 0 {
		memoryPercent = int(float64(memoryUsageBytes) * 100 / float64(memoryLimitBytes))
		if memoryPercent > 100 {
			memoryPercent = 100
		}
	}
	
	// Latency
	m.latencyMutex.Lock()
	avgHitLatency := m.avgHitLatency
	avgMissLatency := m.avgMissLatency
	m.latencyMutex.Unlock()
	
	// Top domains
	m.domainStatsMutex.RLock()
	var topDomains []DomainHitRate
	for domain, stats := range m.domainStats {
		total := stats.Hits + stats.Misses
		if total > 0 {
			hitRate := int(float64(stats.Hits) * 100 / float64(total))
			topDomains = append(topDomains, DomainHitRate{
				Domain:  domain,
				Hits:    stats.Hits,
				Misses:  stats.Misses,
				HitRate: hitRate,
			})
		}
	}
	m.domainStatsMutex.RUnlock()
	
	// Sort by total accesses
	sort.Slice(topDomains, func(i, j int) bool {
		return (topDomains[i].Hits + topDomains[i].Misses) > (topDomains[j].Hits + topDomains[j].Misses)
	})
	if len(topDomains) > TopDomainCount {
		topDomains = topDomains[:TopDomainCount]
	}
	
	// Average domain hit rate
	avgDomainHitRate := 0
	if len(topDomains) > 0 {
		var sum int
		for _, d := range topDomains {
			sum += d.HitRate
		}
		avgDomainHitRate = sum / len(topDomains)
	}
	
	return &CacheMetrics{
		EntryCount:         entryCount,
		TotalLookups:       total,
		Hits:               hits,
		Misses:             misses,
		HitRate:            hitRate,
		MemoryUsageBytes:   memoryUsageBytes,
		MemoryLimitBytes:   memoryLimitBytes,
		MemoryUsagePercent: memoryPercent,
		AvgHitLatencyMs:    avgHitLatency,
		AvgMissLatencyMs:   avgMissLatency,
		AvgDomainHitRate:   avgDomainHitRate,
		TopDomains:         topDomains,
		AvgTTLSeconds:      avgTTL,
		ActiveEntries:      activeEntries,
		UpdateTimestamp:    time.Now().UnixMilli(),
	}
}

// GetMetricsJSON returns metrics as JSON string
func (m *DNSCacheManager) GetMetricsJSON() string {
	metrics := m.GetMetrics()
	jsonBytes, err := json.Marshal(metrics)
	if err != nil {
		return "{}"
	}
	return string(jsonBytes)
}

// Clear clears all cache entries
func (m *DNSCacheManager) Clear() {
	m.cacheMutex.Lock()
	m.cache = make(map[string]*CacheEntry)
	m.cacheMutex.Unlock()
	
	m.domainStatsMutex.Lock()
	m.domainStats = make(map[string]*DomainStats)
	m.domainStatsMutex.Unlock()
	
	atomic.StoreInt64(&m.cacheHits, 0)
	atomic.StoreInt64(&m.cacheMisses, 0)
	atomic.StoreInt64(&m.totalLookups, 0)
	atomic.StoreInt64(&m.totalTTLSeconds, 0)
	atomic.StoreInt64(&m.ttlSampleCount, 0)
	
	m.latencyMutex.Lock()
	m.avgHitLatency = 0
	m.avgMissLatency = 0
	m.latencyMutex.Unlock()
	
	// Delete cache file
	if m.cacheFile != "" {
		os.Remove(m.cacheFile)
	}
}

// CleanupExpired removes expired entries
func (m *DNSCacheManager) CleanupExpired() int {
	m.cacheMutex.Lock()
	defer m.cacheMutex.Unlock()
	
	var removed int
	for hostname, entry := range m.cache {
		if entry.IsExpired() {
			delete(m.cache, hostname)
			removed++
		}
	}
	
	if removed > 0 {
		select {
		case m.saveChannel <- struct{}{}:
		default:
		}
	}
	
	return removed
}

// debouncedSaveLoop handles debounced saves to file
func (m *DNSCacheManager) debouncedSaveLoop() {
	timer := time.NewTimer(SaveDebounceMs * time.Millisecond)
	timer.Stop()
	
	for {
		select {
		case <-m.stopChan:
			m.saveToFile()
			return
		case <-m.saveChannel:
			timer.Reset(SaveDebounceMs * time.Millisecond)
		case <-timer.C:
			m.saveToFile()
		}
	}
}

// loadFromFile loads cache from JSON file
func (m *DNSCacheManager) loadFromFile() error {
	if m.cacheFile == "" {
		return nil
	}
	
	data, err := os.ReadFile(m.cacheFile)
	if err != nil {
		return err
	}
	
	var fileData struct {
		Version int                    `json:"version"`
		Entries map[string]*CacheEntry `json:"entries"`
	}
	
	if err := json.Unmarshal(data, &fileData); err != nil {
		return err
	}
	
	if fileData.Version != CacheVersion {
		return fmt.Errorf("cache version mismatch")
	}
	
	// Load non-expired entries
	for hostname, entry := range fileData.Entries {
		if !entry.IsExpired() {
			m.cache[hostname] = entry
		}
	}
	
	return nil
}

// saveToFile saves cache to JSON file
func (m *DNSCacheManager) saveToFile() {
	if m.cacheFile == "" {
		return
	}
	
	m.saveMutex.Lock()
	defer m.saveMutex.Unlock()
	
	m.cacheMutex.RLock()
	fileData := struct {
		Version int                    `json:"version"`
		Entries map[string]*CacheEntry `json:"entries"`
	}{
		Version: CacheVersion,
		Entries: make(map[string]*CacheEntry),
	}
	for k, v := range m.cache {
		fileData.Entries[k] = v
	}
	m.cacheMutex.RUnlock()
	
	data, err := json.MarshalIndent(fileData, "", "  ")
	if err != nil {
		return
	}
	
	// Ensure directory exists
	os.MkdirAll(filepath.Dir(m.cacheFile), 0755)
	
	os.WriteFile(m.cacheFile, data, 0644)
}

// Shutdown shuts down the cache manager
func (m *DNSCacheManager) Shutdown() {
	close(m.stopChan)
}





