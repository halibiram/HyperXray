package xray

import (
    "net"
    "net/netip"
    "sync"
)

// UDPHandler manages UDP packet forwarding through Xray
type UDPHandler struct {
    xrayInstance *Instance
    localConn    *net.UDPConn
    mutex        sync.RWMutex
    running      bool
}

// NewUDPHandler creates a new UDP handler
func NewUDPHandler(instance *Instance) (*UDPHandler, error) {
    // Create local UDP listener for WireGuard packets
    // This will receive packets from WireGuard and forward them through Xray
    addr, err := net.ResolveUDPAddr("udp", "127.0.0.1:0")
    if err != nil {
        return nil, err
    }
    
    conn, err := net.ListenUDP("udp", addr)
    if err != nil {
        return nil, err
    }
    
    return &UDPHandler{
        xrayInstance: instance,
        localConn:    conn,
    }, nil
}

// Start starts the UDP handler
func (h *UDPHandler) Start() error {
    h.mutex.Lock()
    defer h.mutex.Unlock()
    
    if h.running {
        return nil
    }
    
    // Start packet forwarding goroutine
    go h.forwardPackets()
    
    h.running = true
    return nil
}

// Stop stops the UDP handler
func (h *UDPHandler) Stop() {
    h.mutex.Lock()
    defer h.mutex.Unlock()
    
    if !h.running {
        return
    }
    
    h.running = false
    if h.localConn != nil {
        h.localConn.Close()
    }
}

// forwardPackets forwards UDP packets through Xray
func (h *UDPHandler) forwardPackets() {
    buffer := make([]byte, 65535)
    
    for h.running {
        n, addr, err := h.localConn.ReadFromUDP(buffer)
        if err != nil {
            if h.running {
                // Log error but continue
                continue
            }
            return
        }
        
        // Convert to netip.AddrPort
        addrPort, err := netip.ParseAddrPort(addr.String())
        if err != nil {
            continue
        }
        
        // Forward through Xray
        packet := make([]byte, n)
        copy(packet, buffer[:n])
        h.xrayInstance.SendUDP(packet, addrPort)
    }
}








