# Xray Runtime Service API Documentation

## Overview

The `xray-runtime-service` module provides a safe, controlled interface for managing the Xray-core binary lifecycle. It encapsulates all direct interaction with the Xray-core binary and exposes a clean API through Kotlin Flows for reactive programming.

## Architecture

### Module Dependencies

- **Core modules** may depend on `xray-runtime-service`
- **App and features** CANNOT talk to binary directly
- All binary interaction must go through this service

### Key Components

1. **XrayRuntimeServiceApi** - Public interface for service access
2. **XrayRuntimeService** - Implementation of the service
3. **XrayRuntimeStatus** - Sealed class representing runtime status
4. **XrayRuntimeServiceFactory** - Factory for creating service instances

## API Reference

### XrayRuntimeServiceApi

The main interface for interacting with the Xray runtime service.

#### Properties

##### `status: StateFlow<XrayRuntimeStatus>`

Current runtime status of Xray-core. This StateFlow emits status updates whenever the Xray-core process state changes.

**Example:**
```kotlin
val service = XrayRuntimeServiceFactory.create(context)
service.status.collect { status ->
    when (status) {
        is XrayRuntimeStatus.Running -> {
            println("Xray is running on port ${status.apiPort}")
        }
        is XrayRuntimeStatus.Error -> {
            println("Error: ${status.message}")
        }
        is XrayRuntimeStatus.Stopped -> {
            println("Xray is stopped")
        }
        else -> {}
    }
}
```

#### Methods

##### `start(configPath: String, configContent: String? = null, apiPort: Int? = null): Int?`

Start Xray-core with the provided configuration.

**Parameters:**
- `configPath`: Path to the Xray configuration JSON file (must be within app's private directory)
- `configContent`: Optional pre-read configuration content (for security - avoids re-reading file)
- `apiPort`: Optional API port number (if null, will automatically find an available port)

**Returns:**
- The API port number used, or `null` if startup failed

**Example:**
```kotlin
val apiPort = service.start(
    configPath = "/data/data/com.hyperxray.an/files/config.json",
    apiPort = 10086
)
if (apiPort != null) {
    println("Xray started on port $apiPort")
} else {
    println("Failed to start Xray")
}
```

**Thread-safety:** Thread-safe, can be called from any thread.

##### `stop()`

Stop Xray-core gracefully. This method will:
1. Signal graceful shutdown by closing stdin
2. Wait for process to finish current operations (400ms grace period)
3. Destroy the process if still alive
4. Update status to `Stopped`

**Example:**
```kotlin
service.stop()
```

**Thread-safety:** Thread-safe, can be called from any thread.

##### `restart(configPath: String, configContent: String? = null, apiPort: Int? = null): Int?`

Restart Xray-core with new configuration. This is equivalent to calling `stop()` followed by `start()`.

**Parameters:**
- `configPath`: Path to the Xray configuration JSON file
- `configContent`: Optional pre-read configuration content
- `apiPort`: Optional API port number

**Returns:**
- The API port number used, or `null` if restart failed

**Example:**
```kotlin
val apiPort = service.restart(
    configPath = "/data/data/com.hyperxray.an/files/config.json"
)
```

**Thread-safety:** Thread-safe, can be called from any thread.

##### `isRunning(): Boolean`

Check if Xray-core is currently running.

**Returns:**
- `true` if running, `false` otherwise

**Example:**
```kotlin
if (service.isRunning()) {
    println("Xray is running")
}
```

##### `getProcessId(): Long?`

Get the current process ID if running.

**Returns:**
- Process ID, or `null` if not running

**Example:**
```kotlin
val pid = service.getProcessId()
if (pid != null) {
    println("Xray process ID: $pid")
}
```

##### `getApiPort(): Int?`

Get the current API port if running.

**Returns:**
- API port number, or `null` if not running

**Example:**
```kotlin
val port = service.getApiPort()
if (port != null) {
    println("Xray API port: $port")
}
```

##### `cleanup()`

Cleanup resources. Call this when the service is no longer needed. This will stop Xray-core and cancel all coroutines.

**Example:**
```kotlin
service.cleanup()
```

### XrayRuntimeStatus

Sealed class representing the runtime status of Xray-core.

#### Status Types

##### `Stopped`

Xray-core is not running.

##### `Starting`

Xray-core is starting up.

##### `Running(processId: Long, apiPort: Int)`

Xray-core is running successfully.

**Properties:**
- `processId`: The process ID of the running Xray-core instance
- `apiPort`: The API port number for gRPC communication

##### `Stopping`

Xray-core is stopping.

##### `Error(message: String, throwable: Throwable? = null)`

Xray-core encountered an error.

**Properties:**
- `message`: Error message describing what went wrong
- `throwable`: Optional exception that caused the error

##### `ProcessExited(exitCode: Int, message: String? = null)`

Xray-core process exited unexpectedly.

**Properties:**
- `exitCode`: The exit code of the process
- `message`: Optional message describing the exit

### XrayRuntimeServiceFactory

Factory for creating XrayRuntimeServiceApi instances.

#### Methods

##### `create(context: Context): XrayRuntimeServiceApi`

Create a new XrayRuntimeServiceApi instance.

**Parameters:**
- `context`: Android context

**Returns:**
- XrayRuntimeServiceApi instance

**Example:**
```kotlin
val service = XrayRuntimeServiceFactory.create(context)
```

## Usage Examples

### Basic Usage

```kotlin
// Create service instance
val service = XrayRuntimeServiceFactory.create(context)

// Observe status changes
lifecycleScope.launch {
    service.status.collect { status ->
        when (status) {
            is XrayRuntimeStatus.Running -> {
                // Xray is running, can now use gRPC API
                val apiPort = status.apiPort
                // Connect to gRPC API at 127.0.0.1:$apiPort
            }
            is XrayRuntimeStatus.Error -> {
                // Handle error
                Log.e(TAG, "Xray error: ${status.message}")
            }
            else -> {}
        }
    }
}

// Start Xray
val apiPort = service.start(
    configPath = configFile.absolutePath
)

// Later, restart with new config
service.restart(
    configPath = newConfigFile.absolutePath
)

// Stop Xray
service.stop()

// Cleanup when done
service.cleanup()
```

### Integration with ViewModel

```kotlin
class MyViewModel(
    private val xrayService: XrayRuntimeServiceApi
) : ViewModel() {
    
    val xrayStatus = xrayService.status
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = XrayRuntimeStatus.Stopped
        )
    
    fun startXray(configPath: String) {
        viewModelScope.launch {
            val apiPort = xrayService.start(configPath)
            if (apiPort == null) {
                // Handle error
            }
        }
    }
    
    fun stopXray() {
        viewModelScope.launch {
            xrayService.stop()
        }
    }
}
```

## Security Considerations

1. **Config Path Validation**: All configuration file paths are validated to ensure they are within the app's private directory, preventing path traversal attacks.

2. **Secure Config Reading**: Configuration content is read securely with validation checks to prevent TOCTOU (Time-of-Check-Time-of-Use) race conditions.

3. **Process Isolation**: The Xray-core process runs in a separate process, providing isolation from the main app.

4. **No Direct Binary Access**: App and feature modules cannot directly access the binary - all interaction goes through this service.

## Error Handling

The service provides comprehensive error handling through the `XrayRuntimeStatus.Error` and `XrayRuntimeStatus.ProcessExited` status types. Always observe the status flow to handle errors appropriately.

**Common Error Scenarios:**
- Invalid configuration file path
- Configuration file not readable
- Failed to find available port
- Process exited during startup
- Process execution failure

## Thread Safety

All public methods in `XrayRuntimeServiceApi` are thread-safe and can be called from any thread. Internal state is protected with `@Volatile` annotations and proper synchronization.

## Lifecycle Management

The service manages its own coroutine scope for process monitoring. When the service is no longer needed, call `cleanup()` to properly stop Xray-core and cancel all coroutines.

## Dependencies

To use this module in a core module, add to `build.gradle`:

```gradle
dependencies {
    implementation project(':xray:xray-runtime-service')
}
```

**Note:** App and feature modules should NOT depend on this module directly. They should interact with Xray through higher-level services that use this module.

