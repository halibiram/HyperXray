# Phase 5 — Protocol Features Migrated

## Summary

This PR completes Phase 5 of the HyperXray modular migration by moving protocol-specific UI and logic into dedicated feature modules. The app module now serves primarily as the navigation host, with features independently managing their own configuration, UI, and ViewModels.

## Changes

### ✅ Completed Migrations

#### 1. **feature-vless** 
- Moved `VlessLinkConverter.kt` to feature module
- Created `VlessViewModel.kt` for VLESS configuration management
- Updated `VlessScreen.kt` to use ViewModel

#### 2. **feature-reality**
- Moved `RealityContext.kt` and `RealityOptimizer.kt` to feature module
- Created `RealityViewModel.kt` for REALITY configuration management
- Updated `RealityScreen.kt` to use ViewModel

#### 3. **feature-hysteria2**
- Created `Hysteria2ViewModel.kt` for Hysteria2 configuration management
- Updated `Hysteria2Screen.kt` to use ViewModel

#### 4. **feature-profiles** (Partially Complete)
- Created `ProfileViewModel.kt` with profile list management
- Extracted profile-related methods from MainViewModel

#### 5. **feature-routing** (Placeholder)
- Module structure created
- TODO: Implement ACL/load balancer UI

### 📝 Configuration Updates

- Updated `app/build.gradle` to include all feature module dependencies
- Updated `ConfigFormatConverter` to support feature module converters
- Updated feature module `build.gradle` files with dependencies

### 🔧 Module Structure

```
feature/
├── feature-vless/          ✅ Complete
│   ├── config/            (VlessLinkConverter)
│   ├── viewmodel/         (VlessViewModel)
│   └── ui/                (VlessScreen)
├── feature-reality/        ✅ Complete
│   ├── config/            (RealityContext, RealityOptimizer)
│   ├── viewmodel/         (RealityViewModel)
│   └── ui/                (RealityScreen)
├── feature-hysteria2/      ✅ Complete
│   ├── viewmodel/         (Hysteria2ViewModel)
│   └── ui/                (Hysteria2Screen)
├── feature-profiles/       ⏳ Partially Complete
│   └── viewmodel/         (ProfileViewModel)
│   └── TODO: Move ConfigScreen, ConfigEditScreen, ConfigEditViewModel
└── feature-routing/        ⏳ Placeholder
    └── TODO: Implement ACL/load balancer UI
```

## Architecture Rules Followed

✅ **Shared logic goes into core modules** (NOT duplicates)
- `ConfigFormatConverter` interface remains in app/common for shared use
- Feature-specific implementations live in feature modules

✅ **No feature imports another feature**
- Features only depend on core modules and app (temporary for prefs)
- All features are independent

✅ **App module becomes only host of navigation**
- Navigation remains in `AppNavGraph.kt` in app module
- Feature screens exposed via public APIs

## Remaining Work

### High Priority

1. **Complete feature-profiles migration**
   - [ ] Move `ConfigScreen.kt` to `feature/feature-profiles/src/main/kotlin/com/hyperxray/an/feature/profiles/ui/`
   - [ ] Move `ConfigEditScreen.kt` to feature module
   - [ ] Move `ConfigEditViewModel.kt` to feature module
   - [ ] Remove profile methods from `MainViewModel.kt`
   - [ ] Update `AppNavGraph.kt` to use feature module screens

2. **Update Navigation**
   - [ ] Import feature module screens in `AppNavGraph.kt`
   - [ ] Update route definitions if needed
   - [ ] Test navigation flow

3. **Clean Up MainViewModel**
   - [ ] Remove `_configFiles` and `_selectedConfigFile` state
   - [ ] Remove `refreshConfigFileList()`, `updateSelectedConfigFile()`, `moveConfigFile()` methods
   - [ ] Inject or create `ProfileViewModel` where needed

### Medium Priority

4. **Implement feature-routing**
   - [ ] Create routing rules screen
   - [ ] Create load balancer configuration screen
   - [ ] Create RoutingViewModel

5. **Remove feature → app dependencies**
   - [ ] Move `Preferences` to `:core:core-database`
   - [ ] Move `FileManager` to `:core:core-database`
   - [ ] Move `ConfigUtils`, `FilenameValidator` to appropriate module
   - [ ] Update feature module dependencies

### Low Priority

6. **Improve ConfigFormatConverter registration**
   - [ ] Implement converter registry pattern
   - [ ] Use dependency injection for converter discovery
   - [ ] Remove reflection-based converter lookup

## Testing Checklist

- [ ] Verify feature modules compile independently
- [ ] Test VLESS link import (if feature-vless accessible)
- [ ] Test REALITY optimization (if feature-reality accessible)
- [ ] Test profile list display and selection (after feature-profiles complete)
- [ ] Test config file editing (after feature-profiles complete)
- [ ] Verify navigation between screens
- [ ] Check for circular dependencies (Gradle dependency analysis)

## Breaking Changes

### Before
```kotlin
// MainViewModel had profile management
mainViewModel.configFiles
mainViewModel.selectedConfigFile
mainViewModel.refreshConfigFileList()
```

### After
```kotlin
// ProfileViewModel handles profiles (after migration complete)
profileViewModel.configFiles
profileViewModel.selectedConfigFile
profileViewModel.refreshConfigFileList()
```

## Files Changed

### New Files
- `feature/feature-vless/src/main/kotlin/com/hyperxray/an/feature/vless/config/VlessLinkConverter.kt`
- `feature/feature-vless/src/main/kotlin/com/hyperxray/an/feature/vless/viewmodel/VlessViewModel.kt`
- `feature/feature-reality/src/main/kotlin/com/hyperxray/an/feature/reality/config/RealityContext.kt`
- `feature/feature-reality/src/main/kotlin/com/hyperxray/an/feature/reality/config/RealityOptimizer.kt`
- `feature/feature-reality/src/main/kotlin/com/hyperxray/an/feature/reality/viewmodel/RealityViewModel.kt`
- `feature/feature-hysteria2/src/main/kotlin/com/hyperxray/an/feature/hysteria2/viewmodel/Hysteria2ViewModel.kt`
- `feature/feature-profiles/src/main/kotlin/com/hyperxray/an/feature/profiles/viewmodel/ProfileViewModel.kt`
- `PHASE5_PROTOCOL_FEATURES_MIGRATION.md`
- `PR_PHASE5_SUMMARY.md`

### Modified Files
- `app/build.gradle` - Added feature module dependencies
- `feature/feature-vless/build.gradle` - Added app dependency (temporary)
- `feature/feature-vless/src/main/kotlin/com/hyperxray/an/feature/vless/VlessScreen.kt` - Updated to use ViewModel
- `feature/feature-reality/src/main/kotlin/com/hyperxray/an/feature/reality/RealityScreen.kt` - Updated to use ViewModel
- `feature/feature-hysteria2/src/main/kotlin/com/hyperxray/an/feature/hysteria2/Hysteria2Screen.kt` - Updated to use ViewModel
- `app/src/main/kotlin/com/hyperxray/an/common/configFormat/ConfigFormatConverter.kt` - Updated to support feature converters

### Files to be Moved (In Progress)
- `app/src/main/kotlin/com/hyperxray/an/ui/screens/ConfigScreen.kt` → `feature/feature-profiles/...`
- `app/src/main/kotlin/com/hyperxray/an/ui/screens/ConfigEditScreen.kt` → `feature/feature-profiles/...`
- `app/src/main/kotlin/com/hyperxray/an/viewmodel/ConfigEditViewModel.kt` → `feature/feature-profiles/...`

## Next Steps

### TODO: AI Policy Next

After completing this migration:
1. **Migrate AI policy logic to feature-policy-ai**
   - Move `AiInsightsViewModel` to feature module
   - Move `RealityWorker`, `RealityWorkManager` to feature module
   - Move ONNX inference logic to feature module
   - Move telemetry collection/processing to appropriate modules

2. **Complete feature-profiles migration**
   - Finish moving screens and ViewModels
   - Update all references in app module

3. **Implement feature-routing**
   - Create ACL/routing UI
   - Create load balancer configuration UI

## Notes

- Features temporarily depend on `:app` for `Preferences` and `FileManager`. This will be resolved in a future refactor by moving these to `:core:core-database`.
- `ConfigFormatConverter` currently uses reflection to discover feature converters. Consider implementing a proper DI/registry pattern.
- Navigation remains in app module as the single source of truth for routing, which is correct per requirements.

