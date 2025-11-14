# Xray Runtime Service Module

## Overview

The `xray-runtime-service` module provides a safe, controlled interface for managing the Xray-core binary lifecycle. It encapsulates all direct interaction with the Xray-core binary and exposes a clean API through Kotlin Flows for reactive programming.

## Responsibilities

- **Start / Stop / Restart** Xray-core binary
- **Send status events** through Flows (StateFlow)
- **Provide a safe public API** for control

## Architecture Rules

- ✅ **Core modules** may depend on `xray-runtime-service`
- ❌ **App and features** CANNOT talk to binary directly
- ✅ All binary interaction goes through this service

## Quick Start

```kotlin
// Create service instance
val service = XrayRuntimeServiceFactory.create(context)

// Observe status
lifecycleScope.launch {
    service.status.collect { status ->
        when (status) {
            is XrayRuntimeStatus.Running -> {
                println("Xray running on port ${status.apiPort}")
            }
            is XrayRuntimeStatus.Error -> {
                println("Error: ${status.message}")
            }
            else -> {}
        }
    }
}

// Start Xray
val apiPort = service.start(configPath = "/path/to/config.json")

// Stop Xray
service.stop()
```

## API Documentation

See [API_DOCUMENTATION.md](./API_DOCUMENTATION.md) for complete API reference.

## Module Structure

```
xray-runtime-service/
├── src/main/kotlin/com/hyperxray/an/xray/runtime/
│   ├── XrayRuntimeService.kt          # Main service implementation
│   ├── XrayRuntimeServiceApi.kt       # Public API interface
│   ├── XrayRuntimeStatus.kt           # Status sealed class
│   └── XrayRuntimeModule.kt           # Module identifier
├── build.gradle
├── README.md
└── API_DOCUMENTATION.md
```

## Dependencies

- Android Core KTX
- Kotlin Coroutines (Core + Android)

## Thread Safety

All public methods are thread-safe and can be called from any thread.

## Security

- Config path validation (prevents path traversal)
- Secure config reading (prevents TOCTOU attacks)
- Process isolation

