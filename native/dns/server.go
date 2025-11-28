package dns

import (
	"context"
	"encoding/binary"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

const (
	DNSPort             = 5353 // Default DNS port (non-privileged)
	MaxUDPSize          = 512
	MaxDNSMessageSize   = 4096
	DefaultUpstreamDNS  = "1.1.1.1:53"
	DNSTimeout          = 5 * time.Second
	MaxConcurrentQueries = 100
)

// DNSServer is a caching DNS server
type DNSServer struct {
	listenAddr     string
	listenPort     int
	upstreamDNS    string
	conn           *net.UDPConn
	cacheManager   *DNSCacheManager
	
	running        int32
	stopChan       chan struct{}
	wg             sync.WaitGroup
	
	// Statistics
	totalQueries   int64
	cachedResponses int64
	forwardedQueries int64
	failedQueries  int64
	
	// Query semaphore
	querySem       chan struct{}
	
	// Callbacks
	onLog          func(string)
}

// DNSServerConfig contains server configuration
type DNSServerConfig struct {
	ListenAddr  string
	ListenPort  int
	UpstreamDNS string
	CacheDir    string
}

// DNSServerStats contains server statistics
type DNSServerStats struct {
	ListenPort       int   `json:"listenPort"`
	Running          bool  `json:"running"`
	TotalQueries     int64 `json:"totalQueries"`
	CachedResponses  int64 `json:"cachedResponses"`
	ForwardedQueries int64 `json:"forwardedQueries"`
	FailedQueries    int64 `json:"failedQueries"`
	CacheHitRate     int   `json:"cacheHitRate"`
}

// Global DNS server instance
var (
	globalDNSServer *DNSServer
	dnsServerMutex  sync.Mutex
)

// GetDNSServer returns the global DNS server instance
func GetDNSServer() *DNSServer {
	dnsServerMutex.Lock()
	defer dnsServerMutex.Unlock()
	
	if globalDNSServer == nil {
		globalDNSServer = &DNSServer{
			listenAddr:   "127.0.0.1",
			listenPort:   DNSPort,
			upstreamDNS:  DefaultUpstreamDNS,
			cacheManager: GetCacheManager(),
			stopChan:     make(chan struct{}),
			querySem:     make(chan struct{}, MaxConcurrentQueries),
		}
	}
	return globalDNSServer
}

// SetLogCallback sets the log callback
func (s *DNSServer) SetLogCallback(callback func(string)) {
	s.onLog = callback
}

// log logs a message
func (s *DNSServer) log(format string, args ...interface{}) {
	msg := fmt.Sprintf(format, args...)
	if s.onLog != nil {
		s.onLog(msg)
	}
	fmt.Println("[DNSServer] " + msg)
}

// Start starts the DNS server
func (s *DNSServer) Start(port int) error {
	if atomic.LoadInt32(&s.running) == 1 {
		return fmt.Errorf("DNS server already running")
	}
	
	if port > 0 {
		s.listenPort = port
	}
	
	addr := &net.UDPAddr{
		IP:   net.ParseIP(s.listenAddr),
		Port: s.listenPort,
	}
	
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		return fmt.Errorf("failed to listen on %s:%d: %w", s.listenAddr, s.listenPort, err)
	}
	
	s.conn = conn
	s.stopChan = make(chan struct{})
	atomic.StoreInt32(&s.running, 1)
	
	s.log("DNS server started on %s:%d", s.listenAddr, s.listenPort)
	
	// Start query handler
	s.wg.Add(1)
	go s.handleQueries()
	
	return nil
}

// Stop stops the DNS server
func (s *DNSServer) Stop() {
	if atomic.LoadInt32(&s.running) == 0 {
		return
	}
	
	atomic.StoreInt32(&s.running, 0)
	close(s.stopChan)
	
	if s.conn != nil {
		s.conn.Close()
	}
	
	s.wg.Wait()
	s.log("DNS server stopped")
}

// IsRunning returns whether the server is running
func (s *DNSServer) IsRunning() bool {
	return atomic.LoadInt32(&s.running) == 1
}

// GetListeningPort returns the actual listening port
func (s *DNSServer) GetListeningPort() int {
	if s.conn != nil {
		if addr, ok := s.conn.LocalAddr().(*net.UDPAddr); ok {
			return addr.Port
		}
	}
	return s.listenPort
}

// SetUpstreamDNS sets the upstream DNS server
func (s *DNSServer) SetUpstreamDNS(upstream string) {
	s.upstreamDNS = upstream
}

// GetStats returns server statistics
func (s *DNSServer) GetStats() *DNSServerStats {
	total := atomic.LoadInt64(&s.totalQueries)
	cached := atomic.LoadInt64(&s.cachedResponses)
	
	hitRate := 0
	if total > 0 {
		hitRate = int(float64(cached) * 100 / float64(total))
	}
	
	return &DNSServerStats{
		ListenPort:       s.GetListeningPort(),
		Running:          s.IsRunning(),
		TotalQueries:     total,
		CachedResponses:  cached,
		ForwardedQueries: atomic.LoadInt64(&s.forwardedQueries),
		FailedQueries:    atomic.LoadInt64(&s.failedQueries),
		CacheHitRate:     hitRate,
	}
}

// handleQueries handles incoming DNS queries
func (s *DNSServer) handleQueries() {
	defer s.wg.Done()
	
	buf := make([]byte, MaxDNSMessageSize)
	
	for atomic.LoadInt32(&s.running) == 1 {
		s.conn.SetReadDeadline(time.Now().Add(time.Second))
		n, remoteAddr, err := s.conn.ReadFromUDP(buf)
		if err != nil {
			if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
				continue
			}
			select {
			case <-s.stopChan:
				return
			default:
				s.log("Error reading UDP: %v", err)
				continue
			}
		}
		
		// Limit concurrent queries
		select {
		case s.querySem <- struct{}{}:
			go func(data []byte, addr *net.UDPAddr) {
				defer func() { <-s.querySem }()
				s.handleQuery(data, addr)
			}(append([]byte(nil), buf[:n]...), remoteAddr)
		default:
			s.log("Too many concurrent queries, dropping")
		}
	}
}

// handleQuery processes a single DNS query
func (s *DNSServer) handleQuery(query []byte, remoteAddr *net.UDPAddr) {
	atomic.AddInt64(&s.totalQueries, 1)
	
	if len(query) < 12 {
		return
	}
	
	// Parse query
	domain, qtype := parseDNSQuery(query)
	if domain == "" {
		return
	}
	
	// Only handle A and AAAA queries
	if qtype != 1 && qtype != 28 { // A=1, AAAA=28
		s.forwardQuery(query, remoteAddr)
		return
	}
	
	// Check cache
	ips, found := s.cacheManager.Lookup(domain)
	if found && len(ips) > 0 {
		atomic.AddInt64(&s.cachedResponses, 1)
		response := buildDNSResponse(query, ips, qtype)
		s.conn.WriteToUDP(response, remoteAddr)
		s.log("✅ DNS cache hit: %s", domain)
		return
	}
	
	// Forward to upstream
	s.forwardQuery(query, remoteAddr)
}

// forwardQuery forwards a query to upstream DNS
func (s *DNSServer) forwardQuery(query []byte, remoteAddr *net.UDPAddr) {
	atomic.AddInt64(&s.forwardedQueries, 1)
	
	ctx, cancel := context.WithTimeout(context.Background(), DNSTimeout)
	defer cancel()
	
	// Connect to upstream
	dialer := net.Dialer{}
	conn, err := dialer.DialContext(ctx, "udp", s.upstreamDNS)
	if err != nil {
		atomic.AddInt64(&s.failedQueries, 1)
		s.log("Failed to connect to upstream DNS: %v", err)
		return
	}
	defer conn.Close()
	
	// Send query
	conn.SetDeadline(time.Now().Add(DNSTimeout))
	_, err = conn.Write(query)
	if err != nil {
		atomic.AddInt64(&s.failedQueries, 1)
		return
	}
	
	// Read response
	buf := make([]byte, MaxDNSMessageSize)
	n, err := conn.Read(buf)
	if err != nil {
		atomic.AddInt64(&s.failedQueries, 1)
		return
	}
	
	response := buf[:n]
	
	// Parse response and cache it
	domain, ips := parseDNSResponse(response)
	if domain != "" && len(ips) > 0 {
		s.cacheManager.Save(domain, ips, DefaultTTL)
		s.log("💾 DNS cached: %s -> %v", domain, ips)
	}
	
	// Send response to client
	s.conn.WriteToUDP(response, remoteAddr)
}

// parseDNSQuery parses a DNS query and returns domain and query type
func parseDNSQuery(query []byte) (string, uint16) {
	if len(query) < 12 {
		return "", 0
	}
	
	// Skip header
	offset := 12
	
	// Parse domain name
	var domain []byte
	for offset < len(query) {
		length := int(query[offset])
		if length == 0 {
			offset++
			break
		}
		if length&0xC0 == 0xC0 {
			// Compression pointer - skip
			offset += 2
			break
		}
		offset++
		if offset+length > len(query) {
			return "", 0
		}
		if len(domain) > 0 {
			domain = append(domain, '.')
		}
		domain = append(domain, query[offset:offset+length]...)
		offset += length
	}
	
	// Get query type
	if offset+2 > len(query) {
		return string(domain), 0
	}
	qtype := binary.BigEndian.Uint16(query[offset : offset+2])
	
	return string(domain), qtype
}

// parseDNSResponse parses a DNS response and returns domain and IPs
func parseDNSResponse(response []byte) (string, []net.IP) {
	if len(response) < 12 {
		return "", nil
	}
	
	// Check answer count
	ancount := binary.BigEndian.Uint16(response[6:8])
	if ancount == 0 {
		return "", nil
	}
	
	// Parse domain from question section
	domain, _ := parseDNSQuery(response)
	
	// Skip to answers section
	offset := 12
	
	// Skip question section
	for offset < len(response) {
		length := int(response[offset])
		if length == 0 {
			offset++ // null terminator
			offset += 4 // qtype + qclass
			break
		}
		if length&0xC0 == 0xC0 {
			offset += 2
			offset += 4 // qtype + qclass
			break
		}
		offset += 1 + length
	}
	
	var ips []net.IP
	
	// Parse answer records
	for i := uint16(0); i < ancount && offset+12 <= len(response); i++ {
		// Skip name (might be compressed)
		if response[offset]&0xC0 == 0xC0 {
			offset += 2
		} else {
			for offset < len(response) && response[offset] != 0 {
				offset += 1 + int(response[offset])
			}
			offset++ // null terminator
		}
		
		if offset+10 > len(response) {
			break
		}
		
		rtype := binary.BigEndian.Uint16(response[offset : offset+2])
		offset += 2
		offset += 2 // class
		offset += 4 // TTL
		rdlength := binary.BigEndian.Uint16(response[offset : offset+2])
		offset += 2
		
		if offset+int(rdlength) > len(response) {
			break
		}
		
		// Extract IP addresses
		if rtype == 1 && rdlength == 4 { // A record
			ip := net.IPv4(response[offset], response[offset+1], response[offset+2], response[offset+3])
			ips = append(ips, ip)
		} else if rtype == 28 && rdlength == 16 { // AAAA record
			ip := net.IP(response[offset : offset+16])
			ips = append(ips, ip)
		}
		
		offset += int(rdlength)
	}
	
	return domain, ips
}

// buildDNSResponse builds a DNS response from cached IPs
func buildDNSResponse(query []byte, ips []net.IP, qtype uint16) []byte {
	if len(query) < 12 {
		return query
	}
	
	// Copy query to response
	response := make([]byte, len(query))
	copy(response, query)
	
	// Set response flags
	response[2] = 0x81 // Response + recursion desired
	response[3] = 0x80 // Recursion available
	
	// Set answer count
	answerCount := uint16(0)
	for _, ip := range ips {
		ip4 := ip.To4()
		if qtype == 1 && ip4 != nil { // A record
			answerCount++
		} else if qtype == 28 && ip4 == nil { // AAAA record
			answerCount++
		}
	}
	binary.BigEndian.PutUint16(response[6:8], answerCount)
	
	// Add answer records
	for _, ip := range ips {
		ip4 := ip.To4()
		
		if qtype == 1 && ip4 != nil { // A record
			// Name pointer (compression)
			response = append(response, 0xC0, 0x0C)
			// Type A
			response = append(response, 0x00, 0x01)
			// Class IN
			response = append(response, 0x00, 0x01)
			// TTL (300 seconds)
			response = append(response, 0x00, 0x00, 0x01, 0x2C)
			// RDLENGTH
			response = append(response, 0x00, 0x04)
			// RDATA (IPv4)
			response = append(response, ip4[0], ip4[1], ip4[2], ip4[3])
		} else if qtype == 28 && ip4 == nil { // AAAA record
			ip16 := ip.To16()
			if ip16 == nil {
				continue
			}
			// Name pointer (compression)
			response = append(response, 0xC0, 0x0C)
			// Type AAAA
			response = append(response, 0x00, 0x1C)
			// Class IN
			response = append(response, 0x00, 0x01)
			// TTL (300 seconds)
			response = append(response, 0x00, 0x00, 0x01, 0x2C)
			// RDLENGTH
			response = append(response, 0x00, 0x10)
			// RDATA (IPv6)
			response = append(response, ip16...)
		}
	}
	
	return response
}

// Resolve resolves a domain using cache or upstream
func (s *DNSServer) Resolve(domain string) ([]net.IP, error) {
	// Check cache first
	ips, found := s.cacheManager.Lookup(domain)
	if found && len(ips) > 0 {
		return ips, nil
	}
	
	// Resolve using system DNS
	addrs, err := net.LookupIP(domain)
	if err != nil {
		return nil, err
	}
	
	// Cache the result
	if len(addrs) > 0 {
		s.cacheManager.Save(domain, addrs, DefaultTTL)
	}
	
	return addrs, nil
}





