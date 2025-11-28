package wireguard

import (
    "fmt"
    "net"
    "net/netip"
    "sync"
    "sync/atomic"
    
    "golang.zx2c4.com/wireguard/conn"
    
    "github.com/hyperxray/native/xray"
)

// Simple logging function for packet inspector
func logPacketInspector(format string, args ...interface{}) {
    // Use fmt.Printf for now - can be enhanced to use Android log later
    fmt.Printf("[PacketInspector-XrayBind] "+format+"\n", args...)
}

// XrayBind implements conn.Bind interface
// Routes WireGuard UDP packets through Xray-core
type XrayBind struct {
    xrayInstance *xray.Instance
    endpoint     netip.AddrPort
    
    recvQueue    chan []byte
    sendQueue    chan []byte
    
    closed       int32
    closeOnce    sync.Once
    
    // Stats
    txBytes      uint64
    rxBytes      uint64
}

func NewXrayBind(xrayInstance *xray.Instance, endpoint string) (*XrayBind, error) {
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
        xrayInstance: xrayInstance,
        endpoint:     addrPort,
        recvQueue:    make(chan []byte, 2048),
        sendQueue:    make(chan []byte, 2048),
    }
    
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
        b.xrayInstance.SendUDP(data, b.endpoint)
    }
}

func (b *XrayBind) processIncoming() {
    recvChan := b.xrayInstance.ReceiveUDP()
    packetCount := 0
    for data := range recvChan {
        if atomic.LoadInt32(&b.closed) == 1 {
            return
        }
        
        packetCount++
        
        // ===== PACKET INSPECTOR =====
        // Log first 4 bytes in HEX format to identify packet type
        // WireGuard Handshake Response: 0x02
        // WireGuard Data packet: 0x04
        // If we see random garbage or ASCII (like "HTTP"), routing is wrong
        var headerHex string
        if len(data) >= 4 {
            headerHex = fmt.Sprintf("[0x%02x, 0x%02x, 0x%02x, 0x%02x]", data[0], data[1], data[2], data[3])
        } else {
            headerHex = fmt.Sprintf("[%d bytes only]", len(data))
        }
        
        // Log packet inspector info for every packet (especially important for 1532 byte packets)
        if len(data) >= 1532 || packetCount <= 10 || packetCount%50 == 0 {
            logPacketInspector("RX Len: %d, Header: %s (packetCount: %d)", len(data), headerHex, packetCount)
        } else {
            // Silent for smaller packets to avoid log spam
        }
        
        select {
        case b.recvQueue <- data:
        default:
            // Queue full, drop packet
            logPacketInspector("⚠️ Queue full, dropping %d bytes packet", len(data))
        }
    }
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

