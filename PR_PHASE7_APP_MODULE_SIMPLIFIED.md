# Phase 7 — App Module Simplified (Final Shape)

## Summary

This PR refactors the app module to match the final architecture where it contains **ONLY**:
- Navigation host
- DI initialization
- Global theme
- Activity lifecycle
- **No business logic** (MainViewModel migration deferred to Phase 8)
- **No protocol or xray function calls** (✅ CoreStatsClient moved to xray-runtime-service)

## Changes

### ✅ Completed

1. **Created Core DI Module** (`core/core-di/`)
   - Provides `AppContainer` interface for dependency injection
   - Foundation for future DI expansion

2. **Simplified MainActivity**
   - Removed intent processing business logic
   - Moved intent handling to `feature-profiles.IntentHandler`
   - Now only handles lifecycle, theme, and navigation

3. **Simplified HyperXrayApplication**
   - Removed extensive AI optimizer initialization logic
   - Now only initializes DI container and delegates to `AppInitializer`
   - Clean separation of concerns

4. **Created Intent Handler** (`feature-profiles`)
   - Separated config sharing/import logic from activity
   - Business logic now in feature module

5. **Created App Initializer** (Temporary)
   - Contains AI optimizer initialization logic
   - **Marked as TEMPORARY** - will be moved to feature modules when telemetry classes are migrated

## Architecture

### App Module Now Contains:
- ✅ Navigation host (`ui/navigation/`)
- ✅ DI initialization (`HyperXrayApplication`)
- ✅ Global theme (`ui/theme/`)
- ✅ Activity lifecycle (`activity/MainActivity`)

### Business Logic Moved To:
- ✅ Intent handling → `feature-profiles`
- ✅ AI optimizer initialization → `AppInitializer` (temporary location)

## Known Issues / Future Work

### ✅ Completed in This PR

1. **CoreStatsClient** - ✅ Moved to `xray-runtime-service`
   - **Location**: `xray/xray-runtime-service/src/main/kotlin/com/hyperxray/an/xray/runtime/stats/CoreStatsClient.kt`
   - **Status**: All imports updated, protocol/xray calls properly isolated

2. **TrafficState** - ✅ Moved to `xray-runtime-service`
   - **Location**: `xray/xray-runtime-service/src/main/kotlin/com/hyperxray/an/xray/runtime/stats/model/TrafficState.kt`
   - **Status**: Typealias added for backward compatibility

### ⚠️ Remaining in App Module (Documented for Future Phases)

1. **MainViewModel** - Contains business logic
   - **Action**: Move to `feature-dashboard` module
   - **Complexity**: **HIGH** - 1900+ lines, tightly coupled
   - **Status**: Uses CoreStatsClient from xray-runtime-service (acceptable)

2. **AppInitializer** - Temporary initialization logic
   - **Action**: Move to `feature-policy-ai` when telemetry classes are migrated
   - **Status**: Clearly marked with TODO comments

3. **Telemetry Classes** - Still in app module
   - **Action**: Move to `core-telemetry` or `feature-policy-ai`
   - **Impact**: Blocks moving AppInitializer to feature module

## Files Changed

### Created
- `core/core-di/build.gradle`
- `core/core-di/src/main/kotlin/com/hyperxray/an/core/di/AppContainer.kt`
- `feature/feature-profiles/src/main/kotlin/com/hyperxray/an/feature/profiles/IntentHandler.kt`
- `feature/feature-policy-ai/src/main/kotlin/com/hyperxray/an/feature/policyai/AiOptimizerInitializer.kt`
- `app/src/main/kotlin/com/hyperxray/an/AppInitializer.kt`
- `xray/xray-runtime-service/src/main/kotlin/com/hyperxray/an/xray/runtime/stats/CoreStatsClient.kt`
- `xray/xray-runtime-service/src/main/kotlin/com/hyperxray/an/xray/runtime/stats/model/TrafficState.kt`
- `PHASE7_APP_MODULE_SIMPLIFIED.md`
- `PHASE7_REMAINING_WORK.md`

### Modified
- `settings.gradle` - Added core-di module
- `app/build.gradle` - Added dependencies (core-di, xray-runtime-service)
- `app/src/main/kotlin/com/hyperxray/an/activity/MainActivity.kt` - Simplified
- `app/src/main/kotlin/com/hyperxray/an/HyperXrayApplication.kt` - Simplified
- `app/src/main/kotlin/com/hyperxray/an/viewmodel/MainViewModel.kt` - Updated CoreStatsClient import
- `app/src/main/kotlin/com/hyperxray/an/viewmodel/CoreState.kt` - Added TrafficState typealias
- `app/src/main/kotlin/com/hyperxray/an/service/TProxyService.kt` - Updated CoreStatsClient import
- `app/src/main/kotlin/com/hyperxray/an/telemetry/XrayCoreAiOptimizer.kt` - Updated CoreStatsClient import
- `app/src/main/kotlin/com/hyperxray/an/telemetry/TProxyMetricsCollector.kt` - Updated CoreStatsClient import
- `feature/feature-profiles/build.gradle` - Added core-di dependency
- `xray/xray-runtime-service/build.gradle` - Added protobuf plugin and gRPC dependencies

### Deleted
- `app/src/main/kotlin/com/hyperxray/an/common/CoreStatsClient.kt` - Moved to xray-runtime-service

## Testing

- [x] App builds successfully
- [x] Navigation works correctly
- [x] Intent handling works (config sharing)
- [x] AI optimizer initialization works
- [x] No regressions in functionality

## Migration Notes

This is **Phase 7** of the architecture migration. The app module is now significantly simplified, but some business logic remains (documented above). Future phases will:

- Phase 8: Move MainViewModel to feature-dashboard
- Phase 9: Move telemetry classes to core-telemetry
- Phase 10: Move AppInitializer to feature-policy-ai
- Phase 11: Final cleanup

## Breaking Changes

None - this is a refactoring that maintains backward compatibility.

