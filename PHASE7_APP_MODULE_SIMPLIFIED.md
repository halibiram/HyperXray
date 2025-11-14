# Phase 7 — App Module Simplified (Final Shape)

## Overview

This phase refactors the app module to match the final architecture where it contains **ONLY**:
- Navigation host
- DI initialization
- Global theme
- Activity lifecycle
- **No business logic**
- **No protocol or xray function calls**

## Changes Made

### 1. Created Core DI Module

- **Location**: `core/core-di/`
- **Purpose**: Provides dependency injection container interface
- **Files**:
  - `AppContainer.kt`: Interface and default implementation for DI container

### 2. Simplified MainActivity

- **Before**: Contained intent processing business logic
- **After**: Only handles lifecycle, theme, and navigation
- **Changes**:
  - Moved intent processing to `feature-profiles.IntentHandler`
  - Removed business logic from `processShareIntent()`
  - Added DI container initialization
  - Delegates intent handling to feature module

### 3. Simplified HyperXrayApplication

- **Before**: Contained extensive AI optimizer initialization logic
- **After**: Only handles DI initialization and delegates to `AppInitializer`
- **Changes**:
  - Created `AppInitializer` class for temporary initialization logic
  - HyperXrayApplication now only initializes DI container and delegates
  - All AI optimizer initialization moved to `AppInitializer` (temporary location)

### 4. Created Intent Handler in Feature Module

- **Location**: `feature/feature-profiles/src/main/kotlin/com/hyperxray/an/feature/profiles/IntentHandler.kt`
- **Purpose**: Handles config sharing and import intents
- **Note**: This separates business logic from the activity

### 5. Created App Initializer (Temporary)

- **Location**: `app/src/main/kotlin/com/hyperxray/an/AppInitializer.kt`
- **Purpose**: Contains AI optimizer initialization logic
- **Status**: **TEMPORARY** - Will be moved to feature modules when telemetry classes are migrated
- **Note**: This is a transitional solution. The initialization logic will be moved to `feature-policy-ai` or `core-telemetry` in a future phase.

## Architecture After Phase 7

### App Module Structure

```
app/
├── activity/
│   └── MainActivity.kt          # Lifecycle, theme, navigation only
├── HyperXrayApplication.kt      # DI initialization only
├── AppInitializer.kt            # TEMPORARY - initialization logic
├── ui/
│   ├── navigation/              # Navigation host
│   ├── theme/                   # Global theme
│   └── scaffold/                # UI scaffolding
└── viewmodel/                   # ViewModels (to be moved in future)
```

### What Remains in App Module (Future Work)

1. **MainViewModel** - Contains business logic and CoreStatsClient calls (protocol/xray calls)
   - **Action Required**: Move to `feature-dashboard` module
   - **Complexity**: High - MainViewModel is large and tightly coupled

2. **AppInitializer** - Contains AI optimizer initialization
   - **Action Required**: Move to `feature-policy-ai` when telemetry classes are migrated
   - **Status**: Marked as temporary with TODO comments

3. **Telemetry Classes** - Still in app module
   - **Action Required**: Move to `core-telemetry` or `feature-policy-ai`
   - **Impact**: Once moved, AppInitializer can be moved to feature module

## Known Issues

### Protocol/Xray Function Calls in App Module

The following classes still contain protocol/xray function calls and need to be moved:

1. **MainViewModel** (`app/src/main/kotlin/com/hyperxray/an/viewmodel/MainViewModel.kt`)
   - Uses `CoreStatsClient` (gRPC calls to Xray)
   - Uses `TProxyService` directly
   - **Recommendation**: Move to `feature-dashboard` module

2. **CoreStatsClient** (`app/src/main/kotlin/com/hyperxray/an/common/CoreStatsClient.kt`)
   - Direct gRPC client for Xray stats API
   - **Recommendation**: Move to `xray:xray-runtime-service` or `core-network` module

### Business Logic Still in App Module

1. **MainViewModel** - Large ViewModel with extensive business logic
2. **AppInitializer** - Initialization logic (temporary)
3. **Various ViewModels** - ConfigEditViewModel, LogViewModel, etc.

## Migration Path

### Immediate Next Steps

1. **Move MainViewModel to feature-dashboard**
   - Extract dashboard-specific logic
   - Move CoreStatsClient usage to appropriate module
   - Update navigation to use DI-provided ViewModels

2. **Move CoreStatsClient to core-network or xray-runtime-service**
   - This is a protocol/xray call and should not be in app module
   - Update all references

3. **Move telemetry classes to core-telemetry**
   - Move all classes from `app/src/main/kotlin/com/hyperxray/an/telemetry/`
   - Update AppInitializer to use migrated classes
   - Move AppInitializer to feature-policy-ai

### Future Phases

- Phase 8: Move MainViewModel and related ViewModels to feature modules
- Phase 9: Move telemetry classes to core-telemetry
- Phase 10: Move AppInitializer to feature-policy-ai
- Phase 11: Final cleanup - remove all business logic from app module

## Testing

- [ ] Verify app builds successfully
- [ ] Verify navigation works correctly
- [ ] Verify intent handling works (config sharing)
- [ ] Verify AI optimizer initialization works
- [ ] Verify no regressions in functionality

## Files Changed

### Created
- `core/core-di/build.gradle`
- `core/core-di/src/main/kotlin/com/hyperxray/an/core/di/AppContainer.kt`
- `feature/feature-profiles/src/main/kotlin/com/hyperxray/an/feature/profiles/IntentHandler.kt`
- `feature/feature-policy-ai/src/main/kotlin/com/hyperxray/an/feature/policyai/AiOptimizerInitializer.kt`
- `app/src/main/kotlin/com/hyperxray/an/AppInitializer.kt`

### Modified
- `settings.gradle` - Added core-di module
- `app/build.gradle` - Added core-di and feature-policy-ai dependencies
- `app/src/main/kotlin/com/hyperxray/an/activity/MainActivity.kt` - Simplified
- `app/src/main/kotlin/com/hyperxray/an/HyperXrayApplication.kt` - Simplified
- `feature/feature-profiles/build.gradle` - Added core-di dependency

## Summary

Phase 7 successfully simplifies the app module structure by:
- ✅ Moving intent processing to feature module
- ✅ Creating DI module infrastructure
- ✅ Simplifying MainActivity to lifecycle/theme/navigation only
- ✅ Simplifying HyperXrayApplication to DI initialization only
- ✅ Organizing initialization logic (temporary location)

**Remaining Work**: MainViewModel and CoreStatsClient still need to be moved to feature modules. This is documented as a known issue for future phases.

