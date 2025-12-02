package bridge

import (
	"fmt"
	"net"
	"syscall"
)

// SetReuseAddr sets SO_REUSEADDR on a socket file descriptor
// This allows immediate reuse of ports after socket close
func SetReuseAddr(fd int) error {
	err := syscall.SetsockoptInt(fd, syscall.SOL_SOCKET, syscall.SO_REUSEADDR, 1)
	if err != nil {
		return fmt.Errorf("failed to set SO_REUSEADDR: %w", err)
	}
	return nil
}

// SetReusePort sets SO_REUSEPORT on a socket file descriptor (if available)
// This allows multiple sockets to bind to the same port
func SetReusePort(fd int) error {
	// SO_REUSEPORT is available on Linux 3.9+ and Android
	const SO_REUSEPORT = 15 // syscall.SO_REUSEPORT may not be defined on all platforms
	err := syscall.SetsockoptInt(fd, syscall.SOL_SOCKET, SO_REUSEPORT, 1)
	if err != nil {
		// SO_REUSEPORT may not be available, log but don't fail
		logDebug("[Socket] SO_REUSEPORT not available: %v", err)
		return nil
	}
	return nil
}

// CreateReuseableListener creates a TCP listener with SO_REUSEADDR set
// This allows immediate port reuse after the listener is closed
func CreateReuseableListener(network, address string) (net.Listener, error) {
	lc := net.ListenConfig{
		Control: func(network, address string, c syscall.RawConn) error {
			var opErr error
			err := c.Control(func(fd uintptr) {
				// Set SO_REUSEADDR
				opErr = SetReuseAddr(int(fd))
				if opErr != nil {
					logError("[Socket] Failed to set SO_REUSEADDR on TCP listener: %v", opErr)
					return
				}
				// Try to set SO_REUSEPORT (optional)
				SetReusePort(int(fd))
				logDebug("[Socket] SO_REUSEADDR set on TCP listener fd=%d", fd)
			})
			if err != nil {
				return err
			}
			return opErr
		},
	}

	listener, err := lc.Listen(nil, network, address)
	if err != nil {
		return nil, fmt.Errorf("failed to create reuseable listener: %w", err)
	}

	logInfo("[Socket] Created reuseable TCP listener on %s", address)
	return listener, nil
}

// CreateReuseableUDPConn creates a UDP connection with SO_REUSEADDR set
// This allows immediate port reuse after the connection is closed
func CreateReuseableUDPConn(network, address string) (*net.UDPConn, error) {
	lc := net.ListenConfig{
		Control: func(network, address string, c syscall.RawConn) error {
			var opErr error
			err := c.Control(func(fd uintptr) {
				// Set SO_REUSEADDR
				opErr = SetReuseAddr(int(fd))
				if opErr != nil {
					logError("[Socket] Failed to set SO_REUSEADDR on UDP socket: %v", opErr)
					return
				}
				// Try to set SO_REUSEPORT (optional)
				SetReusePort(int(fd))
				logDebug("[Socket] SO_REUSEADDR set on UDP socket fd=%d", fd)
			})
			if err != nil {
				return err
			}
			return opErr
		},
	}

	// Use ListenPacket for UDP
	conn, err := lc.ListenPacket(nil, network, address)
	if err != nil {
		return nil, fmt.Errorf("failed to create reuseable UDP conn: %w", err)
	}

	udpConn, ok := conn.(*net.UDPConn)
	if !ok {
		conn.Close()
		return nil, fmt.Errorf("expected *net.UDPConn, got %T", conn)
	}

	logInfo("[Socket] Created reuseable UDP connection on %s", address)
	return udpConn, nil
}

// DialUDPWithReuseAddr creates a UDP connection to a remote address with SO_REUSEADDR
func DialUDPWithReuseAddr(network string, laddr, raddr *net.UDPAddr) (*net.UDPConn, error) {
	dialer := net.Dialer{
		Control: func(network, address string, c syscall.RawConn) error {
			var opErr error
			err := c.Control(func(fd uintptr) {
				opErr = SetReuseAddr(int(fd))
				if opErr != nil {
					logError("[Socket] Failed to set SO_REUSEADDR on dial: %v", opErr)
					return
				}
				SetReusePort(int(fd))
				logDebug("[Socket] SO_REUSEADDR set on dial fd=%d", fd)
			})
			if err != nil {
				return err
			}
			return opErr
		},
	}

	conn, err := dialer.Dial(network, raddr.String())
	if err != nil {
		return nil, fmt.Errorf("failed to dial UDP with reuse: %w", err)
	}

	udpConn, ok := conn.(*net.UDPConn)
	if !ok {
		conn.Close()
		return nil, fmt.Errorf("expected *net.UDPConn, got %T", conn)
	}

	return udpConn, nil
}
