# Phase 6 — Xray Runtime Service Architecture Added

## Overview

This PR introduces the `:xray:xray-runtime-service` module, which provides a safe, controlled interface for managing the Xray-core binary lifecycle. This module encapsulates all direct interaction with the Xray-core binary and exposes a clean API through Kotlin Flows for reactive programming.

## Changes

### New Module: `:xray:xray-runtime-service`

**Location:** `xray/xray-runtime-service/`

**Key Components:**
1. **XrayRuntimeServiceApi** - Public interface for service access
2. **XrayRuntimeService** - Implementation of the service
3. **XrayRuntimeStatus** - Sealed class representing runtime status
4. **XrayRuntimeServiceFactory** - Factory for creating service instances

### Responsibilities

- ✅ **Start / Stop / Restart** Xray-core binary
- ✅ **Send status events** through Flows (StateFlow)
- ✅ **Provide a safe public API** for control

### Architecture Rules

- ✅ **Core modules** may depend on `xray-runtime-service`
- ❌ **App and features** CANNOT talk to binary directly
- ✅ All binary interaction goes through this service

## API Documentation

Complete API documentation is available in:
- `xray/xray-runtime-service/API_DOCUMENTATION.md` - Full API reference
- `xray/xray-runtime-service/README.md` - Quick start guide

## Key Features

### Status Flow API

The service exposes runtime status through a `StateFlow<XrayRuntimeStatus>`:

```kotlin
val service = XrayRuntimeServiceFactory.create(context)
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
```

### Thread Safety

All public methods are thread-safe and can be called from any thread. Internal state is protected with `@Volatile` annotations and proper synchronization.

### Security

- Config path validation (prevents path traversal attacks)
- Secure config reading (prevents TOCTOU attacks)
- Process isolation

## Files Added

```
xray/xray-runtime-service/
├── build.gradle
├── README.md
├── API_DOCUMENTATION.md
└── src/main/kotlin/com/hyperxray/an/xray/runtime/
    ├── XrayRuntimeService.kt          # Main service implementation
    ├── XrayRuntimeServiceApi.kt       # Public API interface
    ├── XrayRuntimeStatus.kt           # Status sealed class
    └── XrayRuntimeModule.kt           # Module identifier
```

## Files Modified

- `settings.gradle` - Added `:xray:xray-runtime-service` module

## Dependencies

- Android Core KTX
- Kotlin Coroutines (Core + Android)

## Usage Example

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

// Stop Xray
service.stop()

// Restart Xray
service.restart(
    configPath = newConfigFile.absolutePath
)
```

## Next Steps

After this PR is merged, the following can be done:
1. Update `TProxyService` to use `XrayRuntimeService` instead of direct binary control
2. Update core modules to depend on `xray-runtime-service` as needed
3. Remove direct binary access from app/feature modules

## Testing

The module has been created and structured. Integration testing with `TProxyService` will be done in a follow-up PR.

## Breaking Changes

None - this is a new module addition.

