package wireguard

import (
    "fmt"
    "net"
    "net/netip"
    "sync"
    "sync/atomic"
    "time"
    
    "golang.zx2c4.com/wireguard/conn"
    
    "github.com/hyperxray/native/bridge"
)

// Simple logging function for packet inspector
func logPacketInspector(format string, args ...interface{}) {
    // Use fmt.Printf for now - can be enhanced to use Android log later
    fmt.Printf("[PacketInspector-XrayBind] "+format+"\n", args...)
}

// XrayBind implements conn.Bind interface
// Routes WireGuard UDP packets through Xray-core directly (no SOCKS5)
type XrayBind struct {
    xrayWrapper  *bridge.XrayWrapper
    endpoint     netip.AddrPort
    udpConn      *bridge.XrayUDPConn
    
    recvQueue    chan []byte
    sendQueue    chan []byte
    
    closed       int32
    closeOnce    sync.Once
    connMu       sync.Mutex
    
    // Stats
    txBytes      uint64
    rxBytes      uint64
}

func NewXrayBind(xrayWrapper *bridge.XrayWrapper, endpoint string) (*XrayBind, error) {
    addrPort, err := netip.ParseAddrPort(endpoint)
    if err != nil {
        // Try resolving hostname
        host, port, _ := net.SplitHostPort(endpoint)
        ips, err := net.LookupIP(host)
        if err != nil || len(ips) == 0 {
            return nil, err
        }
        addr, _ := netip.AddrFromSlice(ips[0])
        portNum, _ := net.LookupPort("udp", port)
        addrPort = netip.AddrPortFrom(addr, uint16(portNum))
    }
    
    bind := &XrayBind{
        xrayWrapper: xrayWrapper,
        endpoint:   addrPort,
        recvQueue:  make(chan []byte, 2048),
        sendQueue:  make(chan []byte, 2048),
    }
    
    // Create UDP connection through Xray
    addrStr := addrPort.Addr().String()
    portInt := int(addrPort.Port())
    
    udpConn, err := xrayWrapper.DialUDP(addrStr, portInt)
    if err != nil {
        return nil, fmt.Errorf("dial UDP: %w", err)
    }
    
    // Connect to establish the connection
    if err := udpConn.Connect(); err != nil {
        return nil, fmt.Errorf("connect UDP: %w", err)
    }
    
    bind.udpConn = udpConn
    
    // Start packet processors
    go bind.processOutgoing()
    go bind.processIncoming()
    
    return bind, nil
}

func (b *XrayBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
    return []conn.ReceiveFunc{b.receiveFunc}, port, nil
}

func (b *XrayBind) receiveFunc(bufs [][]byte, sizes []int, eps []conn.Endpoint) (int, error) {
    if atomic.LoadInt32(&b.closed) == 1 {
        return 0, net.ErrClosed
    }
    
    select {
    case data := <-b.recvQueue:
        if len(bufs) > 0 && len(bufs[0]) >= len(data) {
            n := copy(bufs[0], data)
            sizes[0] = n
            eps[0] = &XrayEndpoint{addr: b.endpoint}
            atomic.AddUint64(&b.rxBytes, uint64(n))
            return 1, nil
        }
    }
    
    return 0, nil
}

func (b *XrayBind) Send(bufs [][]byte, ep conn.Endpoint) error {
    if atomic.LoadInt32(&b.closed) == 1 {
        return net.ErrClosed
    }
    
    for _, buf := range bufs {
        data := make([]byte, len(buf))
        copy(data, buf)
        
        select {
        case b.sendQueue <- data:
            atomic.AddUint64(&b.txBytes, uint64(len(data)))
        default:
            // Queue full, drop packet
        }
    }
    
    return nil
}

func (b *XrayBind) Close() error {
    b.closeOnce.Do(func() {
        atomic.StoreInt32(&b.closed, 1)
        close(b.sendQueue)
        
        b.connMu.Lock()
        if b.udpConn != nil {
            b.udpConn.Close()
            b.udpConn = nil
        }
        b.connMu.Unlock()
    })
    return nil
}

func (b *XrayBind) SetMark(mark uint32) error {
    return nil
}

func (b *XrayBind) BatchSize() int {
    return 1
}

func (b *XrayBind) ParseEndpoint(s string) (conn.Endpoint, error) {
    addrPort, err := netip.ParseAddrPort(s)
    if err != nil {
        return nil, err
    }
    return &XrayEndpoint{addr: addrPort}, nil
}

func (b *XrayBind) processOutgoing() {
    for data := range b.sendQueue {
        b.connMu.Lock()
        conn := b.udpConn
        b.connMu.Unlock()
        
        if conn == nil {
            continue
        }
        
        // Write directly to XrayUDPConn
        _, err := conn.Write(data)
        if err != nil {
            // Log error but continue
            fmt.Printf("[XrayBind] Write error: %v\n", err)
            // Try to reconnect
            b.reconnect()
        }
    }
}

func (b *XrayBind) processIncoming() {
    packetCount := 0
    for {
        if atomic.LoadInt32(&b.closed) == 1 {
            return
        }
        
        b.connMu.Lock()
        conn := b.udpConn
        b.connMu.Unlock()
        
        if conn == nil {
            time.Sleep(100 * time.Millisecond)
            continue
        }
        
        // Read from XrayUDPConn with timeout
        data, err := conn.Read(100 * time.Millisecond)
        if err != nil {
            // Timeout is expected, continue
            if err.Error() == "timeout" {
                continue
            }
            // Other errors - try to reconnect
            fmt.Printf("[XrayBind] Read error: %v\n", err)
            b.reconnect()
            continue
        }
        
        if len(data) == 0 {
            continue
        }
        
        packetCount++
        atomic.AddUint64(&b.rxBytes, uint64(len(data)))
        
        // ===== PACKET INSPECTOR =====
        var headerHex string
        if len(data) >= 4 {
            headerHex = fmt.Sprintf("[0x%02x, 0x%02x, 0x%02x, 0x%02x]", data[0], data[1], data[2], data[3])
        } else {
            headerHex = fmt.Sprintf("[%d bytes only]", len(data))
        }
        
        if len(data) >= 1532 || packetCount <= 10 || packetCount%50 == 0 {
            logPacketInspector("RX Len: %d, Header: %s (packetCount: %d)", len(data), headerHex, packetCount)
        }
        
        select {
        case b.recvQueue <- data:
        default:
            logPacketInspector("⚠️ Queue full, dropping %d bytes packet", len(data))
        }
    }
}

func (b *XrayBind) reconnect() {
    b.connMu.Lock()
    defer b.connMu.Unlock()
    
    if b.udpConn != nil {
        b.udpConn.Close()
    }
    
    addrStr := b.endpoint.Addr().String()
    portInt := int(b.endpoint.Port())
    
    udpConn, err := b.xrayWrapper.DialUDP(addrStr, portInt)
    if err != nil {
        fmt.Printf("[XrayBind] Reconnect dial failed: %v\n", err)
        return
    }
    
    if err := udpConn.Connect(); err != nil {
        fmt.Printf("[XrayBind] Reconnect connect failed: %v\n", err)
        return
    }
    
    b.udpConn = udpConn
    fmt.Printf("[XrayBind] ✅ Reconnected to %s:%d\n", addrStr, portInt)
}

func (b *XrayBind) GetStats() (uint64, uint64) {
    return atomic.LoadUint64(&b.txBytes), atomic.LoadUint64(&b.rxBytes)
}

// XrayEndpoint implements conn.Endpoint
type XrayEndpoint struct {
    addr netip.AddrPort
}

func (e *XrayEndpoint) ClearSrc() {}

func (e *XrayEndpoint) SrcToString() string {
    return ""
}

func (e *XrayEndpoint) DstToString() string {
    return e.addr.String()
}

func (e *XrayEndpoint) DstIP() netip.Addr {
    return e.addr.Addr()
}

func (e *XrayEndpoint) SrcIP() netip.Addr {
    return netip.Addr{}
}

func (e *XrayEndpoint) DstPort() uint16 {
    return e.addr.Port()
}

func (e *XrayEndpoint) DstToBytes() []byte {
    addr := e.addr.Addr()
    if addr.Is4() {
        ip4 := addr.As4()
        return ip4[:]
    } else if addr.Is6() {
        ip6 := addr.As16()
        return ip6[:]
    }
    return nil
}

