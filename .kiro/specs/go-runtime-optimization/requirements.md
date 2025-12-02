# Requirements Document

## Introduction

Bu doküman, HyperXray'in native Go runtime'ında tespit edilen sorunları ve bunların çözümü için gerekli optimizasyonları tanımlar. Go runtime, Xray-core ve WireGuard entegrasyonunu yöneten kritik bir bileşendir. Mevcut implementasyonda goroutine sızıntıları, bellek yönetimi sorunları, mutex contention ve kaynak temizleme problemleri tespit edilmiştir.

## Glossary

- **Go Runtime**: Go dilinin çalışma zamanı ortamı, goroutine scheduler, garbage collector ve memory allocator içerir
- **Goroutine**: Go'nun hafif thread implementasyonu
- **Goroutine Leak**: Sonlandırılmayan veya bekleyen goroutine'lerin birikmesi
- **Mutex Contention**: Birden fazla goroutine'in aynı mutex'i beklemesi durumu
- **Channel**: Go'da goroutine'ler arası iletişim mekanizması
- **Context Cancellation**: Go'da goroutine'leri iptal etmek için kullanılan mekanizma
- **XrayWrapper**: Xray-core instance'ını yöneten Go struct'ı
- **XrayUDPConn**: Xray üzerinden UDP bağlantısını temsil eden struct
- **ProtectedDialer**: VPN routing loop'unu önlemek için socket protection uygulayan dialer
- **DNS Cache Manager**: DNS çözümlemelerini önbelleğe alan bileşen
- **gRPC Client**: Xray-core ile iletişim kuran gRPC istemcisi

## Requirements

### Requirement 1: Goroutine Lifecycle Management

**User Story:** As a developer, I want goroutines to be properly managed throughout their lifecycle, so that there are no goroutine leaks causing memory growth and performance degradation.

#### Acceptance Criteria

1. WHEN a tunnel is stopped THEN the Go Runtime SHALL terminate all associated goroutines within 5 seconds
2. WHEN XrayUDPConn.Close() is called THEN the Go Runtime SHALL signal the readLoop goroutine to exit via stopChan
3. WHEN context is cancelled THEN all goroutines monitoring that context SHALL exit gracefully
4. WHILE the tunnel is running THEN the Go Runtime SHALL track active goroutine count and log warnings if count exceeds baseline by more than 50%
5. IF a goroutine fails to exit within timeout THEN the Go Runtime SHALL log the goroutine stack trace for debugging

### Requirement 2: Channel Management and Deadlock Prevention

**User Story:** As a developer, I want channels to be properly managed, so that there are no deadlocks or blocked goroutines waiting on closed channels.

#### Acceptance Criteria

1. WHEN XrayLogChannel receives logs THEN the Go Runtime SHALL use non-blocking send with buffer overflow handling
2. WHEN a channel is closed THEN the Go Runtime SHALL set a closed flag before closing to prevent send-on-closed-channel panics
3. WHEN reading from a channel THEN the Go Runtime SHALL use select with context.Done() to allow cancellation
4. WHILE XrayLogChannel is active THEN the Go Runtime SHALL maintain a maximum buffer size of 1000 entries with oldest-first eviction
5. IF channel send blocks for more than 100ms THEN the Go Runtime SHALL drop the message and increment a dropped counter

### Requirement 3: Mutex Contention Reduction

**User Story:** As a developer, I want mutex contention to be minimized, so that concurrent operations do not cause performance bottlenecks.

#### Acceptance Criteria

1. WHEN accessing tunnel stats THEN the Go Runtime SHALL use RWMutex with read locks for read-only operations
2. WHEN multiple goroutines access XrayWrapper THEN the Go Runtime SHALL minimize critical section duration to under 1ms
3. WHEN protecting socket THEN the Go Runtime SHALL use lock-free atomic operations where possible
4. WHILE gRPC client is being accessed THEN the Go Runtime SHALL cache the client reference to avoid repeated mutex acquisitions
5. IF mutex is held for more than 100ms THEN the Go Runtime SHALL log a warning with stack trace

### Requirement 4: Resource Cleanup on Shutdown

**User Story:** As a developer, I want all resources to be properly cleaned up when the tunnel stops, so that there are no resource leaks.

#### Acceptance Criteria

1. WHEN tunnel.Stop() is called THEN the Go Runtime SHALL close all UDP connections before stopping Xray instance
2. WHEN XrayWrapper.Stop() is called THEN the Go Runtime SHALL close gRPC client connection and release all resources
3. WHEN DNS cache manager is shutdown THEN the Go Runtime SHALL persist cache to disk and close all channels
4. WHILE cleanup is in progress THEN the Go Runtime SHALL follow a deterministic order: connections → gRPC → Xray → WireGuard → TUN
5. IF cleanup fails for a resource THEN the Go Runtime SHALL log the error and continue with remaining cleanup

### Requirement 5: Memory Management Optimization

**User Story:** As a developer, I want memory to be efficiently managed, so that the application does not consume excessive memory over time.

#### Acceptance Criteria

1. WHEN creating byte buffers for UDP packets THEN the Go Runtime SHALL use sync.Pool for buffer reuse
2. WHEN DNS cache grows beyond 10MB THEN the Go Runtime SHALL trigger cleanup of expired entries
3. WHEN diagnostic logs exceed 100 entries THEN the Go Runtime SHALL evict oldest entries
4. WHILE tunnel is running THEN the Go Runtime SHALL limit total memory allocation growth to 50MB above baseline
5. IF memory usage exceeds threshold THEN the Go Runtime SHALL trigger manual GC and log memory statistics

### Requirement 6: Connection Health Monitoring

**User Story:** As a developer, I want connection health to be continuously monitored, so that issues can be detected and recovered from automatically.

#### Acceptance Criteria

1. WHEN XrayUDPConn is created THEN the Go Runtime SHALL start a health check goroutine with 30-second intervals
2. WHEN health check fails 3 consecutive times THEN the Go Runtime SHALL attempt automatic reconnection
3. WHEN WireGuard handshake is not received within 60 seconds THEN the Go Runtime SHALL log detailed diagnostics
4. WHILE connection is active THEN the Go Runtime SHALL track and expose RTT, packet loss, and throughput metrics
5. IF reconnection fails THEN the Go Runtime SHALL notify the Android layer via callback

### Requirement 7: Error Handling and Recovery

**User Story:** As a developer, I want errors to be properly handled and recovered from, so that the tunnel remains stable under adverse conditions.

#### Acceptance Criteria

1. WHEN a panic occurs in any goroutine THEN the Go Runtime SHALL recover, log the panic with stack trace, and attempt graceful shutdown
2. WHEN gRPC connection fails THEN the Go Runtime SHALL implement exponential backoff retry with maximum 5 attempts
3. WHEN DNS resolution fails THEN the Go Runtime SHALL try alternative DNS servers in sequence
4. WHILE handling errors THEN the Go Runtime SHALL categorize errors as recoverable or fatal
5. IF fatal error occurs THEN the Go Runtime SHALL stop the tunnel and report error code to Android layer

### Requirement 8: Logging and Diagnostics

**User Story:** As a developer, I want comprehensive logging and diagnostics, so that issues can be quickly identified and debugged.

#### Acceptance Criteria

1. WHEN GetXrayLogs is called THEN the Go Runtime SHALL return logs from XrayLogChannel without blocking
2. WHEN goroutine count changes significantly THEN the Go Runtime SHALL log the change with goroutine IDs
3. WHEN mutex contention is detected THEN the Go Runtime SHALL log the contending goroutines
4. WHILE tunnel is running THEN the Go Runtime SHALL expose runtime statistics via GetXraySystemStats
5. IF log channel is full THEN the Go Runtime SHALL increment overflow counter and log periodically

### Requirement 9: Physical IP Cache Optimization

**User Story:** As a developer, I want physical IP lookups to be fast and non-blocking, so that dial operations are not delayed.

#### Acceptance Criteria

1. WHEN GetLocalPhysicalIP is called THEN the Go Runtime SHALL return cached value immediately if valid
2. WHEN cache is expired THEN the Go Runtime SHALL trigger async refresh and return stale value
3. WHEN cache is empty on first call THEN the Go Runtime SHALL perform quick synchronous lookup with 500ms timeout
4. WHILE VPN is active THEN the Go Runtime SHALL prioritize wlan0 interface over mobile data interfaces
5. IF all interface lookups fail THEN the Go Runtime SHALL return nil gracefully without blocking dial operations

### Requirement 10: gRPC Client Lifecycle Management

**User Story:** As a developer, I want gRPC client to be properly managed, so that stats queries work reliably throughout tunnel lifetime.

#### Acceptance Criteria

1. WHEN Xray starts THEN the Go Runtime SHALL create gRPC client with retry logic and store reference
2. WHEN GetGrpcClient is called THEN the Go Runtime SHALL return cached client without creating new connection
3. WHEN gRPC connection is lost THEN the Go Runtime SHALL attempt automatic reconnection
4. WHILE gRPC client is active THEN the Go Runtime SHALL verify connection health periodically
5. IF gRPC client creation fails THEN the Go Runtime SHALL log detailed error and continue without gRPC stats
