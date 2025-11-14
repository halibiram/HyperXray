# Phase 7 - Remaining Work Documentation

## Protocol/Xray Function Calls Status

### ✅ Moved to xray-runtime-service
- **CoreStatsClient** - gRPC client for Xray stats API
  - **Location**: `xray/xray-runtime-service/src/main/kotlin/com/hyperxray/an/xray/runtime/stats/CoreStatsClient.kt`
  - **Status**: ✅ Moved and all imports updated

- **TrafficState** - Traffic statistics model
  - **Location**: `xray/xray-runtime-service/src/main/kotlin/com/hyperxray/an/xray/runtime/stats/model/TrafficState.kt`
  - **Status**: ✅ Moved, typealias added for backward compatibility

### ⚠️ Still in App Module (Requires Large Refactoring)

1. **MainViewModel** (`app/src/main/kotlin/com/hyperxray/an/viewmodel/MainViewModel.kt`)
   - **Protocol/Xray Calls**: Uses `CoreStatsClient` (now imported from xray-runtime-service)
   - **Business Logic**: Extensive business logic for:
     - Connection management
     - Config file management
     - Stats collection
     - Service control
   - **Action Required**: Move to `feature-dashboard` module
   - **Complexity**: **HIGH** - 1900+ lines, tightly coupled, used throughout app
   - **Dependencies**: 
     - CoreStatsClient (now in xray-runtime-service) ✅
     - TProxyService (service, can stay in app)
     - FileManager, Preferences, etc.

2. **TProxyService** (`app/src/main/kotlin/com/hyperxray/an/service/TProxyService.kt`)
   - **Protocol/Xray Calls**: 
     - Uses `CoreStatsClient` (now imported from xray-runtime-service) ✅
     - Executes `libxray.so` process
     - Manages Xray-core process lifecycle
   - **Status**: Service class - typically stays in app module
   - **Note**: Uses CoreStatsClient but that's now properly imported from xray-runtime-service

3. **Telemetry Classes** (`app/src/main/kotlin/com/hyperxray/an/telemetry/`)
   - **Protocol/Xray Calls**: Some classes use `CoreStatsClient` (now imported from xray-runtime-service) ✅
   - **Action Required**: Move to `core-telemetry` or `feature-policy-ai`
   - **Impact**: Once moved, AppInitializer can be moved to feature module

## Summary

### Protocol/Xray Calls Status
- ✅ **CoreStatsClient** - Moved to xray-runtime-service
- ✅ **All imports updated** - No direct protocol calls in app/common
- ⚠️ **MainViewModel** - Still uses CoreStatsClient but imports from xray-runtime-service (acceptable)
- ⚠️ **TProxyService** - Service class, uses CoreStatsClient from xray-runtime-service (acceptable)

### Business Logic Status
- ✅ **Intent handling** - Moved to feature-profiles
- ✅ **AI optimizer initialization** - Organized in AppInitializer (temporary)
- ⚠️ **MainViewModel** - Large ViewModel with extensive business logic (needs migration)
- ⚠️ **Telemetry classes** - Still in app module (needs migration)

## Next Steps

### Phase 8 (Future)
1. Move MainViewModel to feature-dashboard
   - Extract dashboard-specific logic
   - Create proper ViewModel interfaces
   - Update navigation to use DI

2. Move telemetry classes to core-telemetry
   - Move all classes from `app/src/main/kotlin/com/hyperxray/an/telemetry/`
   - Update AppInitializer
   - Move AppInitializer to feature-policy-ai

3. Final cleanup
   - Remove all business logic from app module
   - Ensure app module contains ONLY:
     - Navigation host
     - DI initialization
     - Global theme
     - Activity lifecycle

## Current Architecture Compliance

### ✅ App Module Now Contains:
- Navigation host (`ui/navigation/`)
- DI initialization (`HyperXrayApplication`, `AppContainer`)
- Global theme (`ui/theme/`)
- Activity lifecycle (`activity/MainActivity`)

### ⚠️ App Module Still Contains (Documented):
- MainViewModel (business logic) - **Large refactoring required**
- AppInitializer (temporary initialization) - **Will be moved when telemetry classes migrate**
- Telemetry classes - **Will be moved to core-telemetry**

### ✅ Protocol/Xray Calls:
- All protocol/xray calls now properly isolated in xray-runtime-service
- App module imports CoreStatsClient from xray-runtime-service (acceptable)
- No direct protocol implementation in app module

## Conclusion

Phase 7 has successfully:
1. ✅ Moved CoreStatsClient to xray-runtime-service
2. ✅ Simplified MainActivity and HyperXrayApplication
3. ✅ Created DI infrastructure
4. ✅ Moved intent handling to feature module
5. ✅ Organized initialization logic

**Remaining work** (MainViewModel migration) is documented and requires a separate large refactoring effort that should be done in Phase 8.

