package bridge

import (
	"fmt"
	"net"
	"os"
	"sync"
	"syscall"
	"time"
)

// SocketProtector is a function that protects a socket from VPN routing
// It should call VpnService.protect(fd) on Android
type SocketProtector func(fd int) bool

var (
	globalProtector SocketProtector
	protectorMutex  sync.RWMutex
)

// SetSocketProtector sets the global socket protector function
func SetSocketProtector(protector SocketProtector) {
	protectorMutex.Lock()
	defer protectorMutex.Unlock()
	globalProtector = protector
	logInfo("[Protector] Socket protector set")
}

// ProtectSocket protects a socket file descriptor from VPN routing
func ProtectSocket(fd int) bool {
	protectorMutex.RLock()
	protector := globalProtector
	protectorMutex.RUnlock()

	if protector == nil {
		logWarn("[Protector] No protector set, socket %d not protected!", fd)
		return false
	}

	result := protector(fd)
	if result {
		logDebug("[Protector] ✅ Socket %d protected", fd)
	} else {
		logError("[Protector] ❌ Failed to protect socket %d", fd)
	}

	return result
}

// ProtectConn protects a net.Conn from VPN routing
func ProtectConn(conn net.Conn) bool {
	// Get underlying file descriptor
	tcpConn, ok := conn.(*net.TCPConn)
	if !ok {
		// Try UDP
		udpConn, ok := conn.(*net.UDPConn)
		if !ok {
			logWarn("[Protector] Unknown connection type: %T", conn)
			return false
		}

		file, err := udpConn.File()
		if err != nil {
			logError("[Protector] Failed to get UDP file: %v", err)
			return false
		}
		defer file.Close()

		fdInt := int(file.Fd())
		return ProtectSocket(fdInt)
	}

	file, err := tcpConn.File()
	if err != nil {
		logError("[Protector] Failed to get TCP file: %v", err)
		return false
	}
	defer file.Close()

	return ProtectSocket(int(file.Fd()))
}

// ProtectedDialer creates a dialer that protects sockets before connecting
type ProtectedDialer struct {
	Timeout int // seconds
}

// DialTCP dials a protected TCP connection
func (d *ProtectedDialer) DialTCP(network, address string) (net.Conn, error) {
	logInfo("[ProtectedDialer] Dialing %s %s", network, address)

	// Check if socket protector is set
	protectorMutex.RLock()
	protector := globalProtector
	protectorMutex.RUnlock()
	
	if protector == nil {
		logError("[ProtectedDialer] ❌ Socket protector is NOT SET!")
		logError("[ProtectedDialer] ❌ Cannot protect socket - call SetSocketProtector first!")
		return nil, fmt.Errorf("socket protector not set - call initSocketProtector() first")
	}
	logInfo("[ProtectedDialer] ✅ Socket protector is available")

	// Resolve address first
	tcpAddr, err := net.ResolveTCPAddr(network, address)
	if err != nil {
		logError("[ProtectedDialer] ❌ DNS resolution failed for %s: %v", address, err)
		return nil, fmt.Errorf("resolve address: %w", err)
	}
	logInfo("[ProtectedDialer] ✅ DNS resolved: %s → %s", address, tcpAddr.IP.String())

	// Create socket
	fd, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_STREAM, 0)
	if err != nil {
		logError("[ProtectedDialer] ❌ Failed to create socket: %v", err)
		return nil, fmt.Errorf("create socket: %w", err)
	}
	logDebug("[ProtectedDialer] Socket created: fd=%d", fd)

	// PROTECT THE SOCKET BEFORE CONNECTING!
	if !ProtectSocket(fd) {
		syscall.Close(fd)
		logError("[ProtectedDialer] ❌ Failed to protect socket %d", fd)
		return nil, fmt.Errorf("failed to protect socket %d", fd)
	}
	logInfo("[ProtectedDialer] ✅ Socket %d protected successfully", fd)

	// Convert address
	var sa syscall.SockaddrInet4
	copy(sa.Addr[:], tcpAddr.IP.To4())
	sa.Port = tcpAddr.Port

	// Connect in a goroutine with timeout
	timeout := time.Duration(d.Timeout) * time.Second
	if timeout == 0 {
		timeout = 10 * time.Second
	}

	connectDone := make(chan error, 1)
	go func() {
		// Connect in blocking mode
		err := syscall.Connect(fd, &sa)
		connectDone <- err
	}()

	// Wait for connection or timeout
	select {
	case err := <-connectDone:
		if err != nil {
			syscall.Close(fd)
			logError("[ProtectedDialer] ❌ Connect failed: %v", err)
			return nil, fmt.Errorf("connect: %w", err)
		}
		logInfo("[ProtectedDialer] ✅ Connection established")
	case <-time.After(timeout):
		syscall.Close(fd)
		logError("[ProtectedDialer] ❌ Connection timeout after %v", timeout)
		return nil, fmt.Errorf("connection timeout")
	}

	// Create net.Conn from fd
	// IMPORTANT: os.NewFile takes ownership of fd, so we don't close it manually
	file := os.NewFile(uintptr(fd), "")
	conn, err := net.FileConn(file)
	file.Close() // FileConn dups the fd, so we can close the file

	if err != nil {
		syscall.Close(fd) // If FileConn failed, close the fd
		logError("[ProtectedDialer] ❌ FileConn failed: %v", err)
		return nil, fmt.Errorf("fileconn: %w", err)
	}

	logDebug("[ProtectedDialer] ✅ Connected to %s", address)
	return conn, nil
}

// DialUDP dials a protected UDP connection
func (d *ProtectedDialer) DialUDP(network, address string) (*net.UDPConn, error) {
	logDebug("[ProtectedDialer] Dialing UDP %s %s", network, address)

	// Resolve address
	udpAddr, err := net.ResolveUDPAddr(network, address)
	if err != nil {
		return nil, err
	}

	// Create socket
	fd, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_DGRAM, 0)
	if err != nil {
		return nil, fmt.Errorf("create socket: %w", err)
	}

	// PROTECT THE SOCKET!
	fdInt := int(fd)
	if !ProtectSocket(fdInt) {
		syscall.Close(fd)
		return nil, fmt.Errorf("failed to protect socket")
	}

	// Bind to any address
	var bindAddr syscall.SockaddrInet4
	bindAddr.Port = 0 // Any port
	err = syscall.Bind(fd, &bindAddr)
	if err != nil {
		syscall.Close(fd)
		return nil, fmt.Errorf("bind: %w", err)
	}

	// Connect
	var sa syscall.SockaddrInet4
	copy(sa.Addr[:], udpAddr.IP.To4())
	sa.Port = udpAddr.Port

	err = syscall.Connect(fd, &sa)
	if err != nil {
		syscall.Close(fd)
		return nil, fmt.Errorf("connect: %w", err)
	}

	// Create UDPConn from fd
	file := os.NewFile(uintptr(fd), "")
	conn, err := net.FileConn(file)
	file.Close()

	if err != nil {
		return nil, fmt.Errorf("fileconn: %w", err)
	}

	udpConn, ok := conn.(*net.UDPConn)
	if !ok {
		return nil, fmt.Errorf("not a UDP connection")
	}

	return udpConn, nil
}

// DialContext dials with context (for use with http.Transport)
func (d *ProtectedDialer) DialContext(network, address string) (net.Conn, error) {
	return d.DialTCP(network, address)
}

