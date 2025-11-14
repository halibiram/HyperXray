# Phase 5 — Protocol Features Migrated

## Overview
This phase migrates protocol-specific UI and logic into dedicated feature modules, following the modular architecture pattern where:
- **Shared logic** goes into core modules (NOT duplicates)
- **No feature imports another feature**
- **App module** becomes only host of navigation

## Completed Migrations

### 1. feature-vless ✅
**Location**: `feature/feature-vless/`

**Moved Components**:
- ✅ `VlessLinkConverter.kt` → `feature/feature-vless/src/main/kotlin/com/hyperxray/an/feature/vless/config/VlessLinkConverter.kt`
  - VLESS protocol link converter
  - Config format converter interface
  - DetectedConfig type alias
  
- ✅ `VlessViewModel.kt` → Created in feature module
  - VLESS-specific configuration management
  - UI state handling

- ✅ `VlessScreen.kt` → Updated to use ViewModel
  - VLESS configuration UI
  - Protocol-specific settings

**Dependencies**: 
- `:core:core-designsystem`
- `:app` (temporary, for prefs access - TODO: move prefs to core)

### 2. feature-reality ✅
**Location**: `feature/feature-reality/`

**Moved Components**:
- ✅ `RealityContext.kt` → `feature/feature-reality/src/main/kotlin/com/hyperxray/an/feature/reality/config/RealityContext.kt`
  - Reality protocol context data class
  - Context identifier generation

- ✅ `RealityOptimizer.kt` → `feature/feature-reality/src/main/kotlin/com/hyperxray/an/feature/reality/config/RealityOptimizer.kt`
  - Reality template patching logic
  - JSON validation for Reality configs

- ✅ `RealityViewModel.kt` → Created in feature module
  - REALITY configuration management
  - Optimization state handling

- ✅ `RealityScreen.kt` → Updated to use ViewModel
  - REALITY configuration UI

**Dependencies**:
- `:core:core-designsystem`
- `:app` (temporary, for prefs access)

### 3. feature-hysteria2 ✅
**Location**: `feature/feature-hysteria2/`

**Created Components**:
- ✅ `Hysteria2ViewModel.kt` → Created in feature module
  - Hysteria2 configuration management
  - Bandwidth and optimization settings

- ✅ `Hysteria2Screen.kt` → Updated to use ViewModel
  - Hysteria2 configuration UI

**Dependencies**:
- `:core:core-designsystem`
- `:app` (temporary)

### 4. feature-routing ⏳
**Location**: `feature/feature-routing/`

**Status**: Placeholder created, needs implementation
- ACL (Access Control List) UI
- Load balancer configuration UI
- Routing rules management

**TODO**: Create routing screens and ViewModel for:
- Balancing rule configuration
- Routing rule management
- Outbound selector UI

### 5. feature-profiles ⏳
**Location**: `feature/feature-profiles/`

**Partially Completed**:
- ✅ `ProfileViewModel.kt` → Created in feature module
  - Profile list management
  - Config file selection and ordering
  - Methods: `refreshConfigFileList()`, `updateSelectedConfigFile()`, `moveConfigFile()`

**Needs Migration** (from `app/`):
- ⏳ `ConfigScreen.kt` → Move to `feature/feature-profiles/src/main/kotlin/com/hyperxray/an/feature/profiles/ui/ConfigScreen.kt`
- ⏳ `ConfigEditScreen.kt` → Move to `feature/feature-profiles/src/main/kotlin/com/hyperxray/an/feature/profiles/ui/ConfigEditScreen.kt`
- ⏳ `ConfigEditViewModel.kt` → Move to `feature/feature-profiles/src/main/kotlin/com/hyperxray/an/feature/profiles/viewmodel/ConfigEditViewModel.kt`

**Methods to remove from MainViewModel**:
- `moveConfigFile(fromIndex, toIndex)`
- `refreshConfigFileList()`
- `updateSelectedConfigFile(file)`
- State: `_configFiles`, `_selectedConfigFile`

## Module Dependencies

### Current Dependency Graph
```
app
├── feature-dashboard
├── feature-vless
│   ├── core-designsystem
│   └── app (temporary - for prefs)
├── feature-reality
│   ├── core-designsystem
│   └── app (temporary - for prefs)
├── feature-hysteria2
│   ├── core-designsystem
│   └── app (temporary)
├── feature-routing
│   └── core-designsystem
└── feature-profiles
    ├── core-designsystem
    └── app (temporary - for prefs, FileManager)
```

**⚠️ Note**: Features currently depend on `:app` module for:
- `Preferences` (should move to `:core:core-database`)
- `FileManager` (should move to `:core:core-database`)
- `ConfigUtils`, `FilenameValidator` (should move to core or feature-profiles)

## Remaining Tasks

### High Priority
1. **Complete feature-profiles migration**
   - Move `ConfigScreen.kt`, `ConfigEditScreen.kt`, `ConfigEditViewModel.kt`
   - Remove profile methods from `MainViewModel.kt`
   - Update navigation in `AppNavGraph.kt` to use feature module screens

2. **Update app module navigation**
   - Import feature modules in `app/build.gradle`
   - Update `AppNavGraph.kt` to reference feature screens
   - Update imports in screens that use feature components

3. **Update ConfigFormatConverter**
   - Change `VlessLinkConverter` reference to use feature module
   - Create converter factory or registry in app module

### Medium Priority
4. **Create feature-routing UI**
   - Routing rules screen
   - Load balancer configuration screen
   - ViewModel for routing management

5. **Remove feature → app dependencies**
   - Move `Preferences` to `:core:core-database`
   - Move `FileManager` to `:core:core-database`
   - Move `ConfigUtils`, `FilenameValidator` to appropriate core/feature module

6. **Update MainViewModel**
   - Remove profile-related state and methods
   - Inject `ProfileViewModel` or use callbacks

### Low Priority
7. **Verify no feature → feature dependencies**
   - Audit all feature module imports
   - Ensure features only depend on core modules

8. **Update tests**
   - Migrate tests to feature modules
   - Update test dependencies

## Migration Strategy

### Step 1: Complete feature-profiles
```kotlin
// Move ConfigScreen.kt
app/src/main/kotlin/com/hyperxray/an/ui/screens/ConfigScreen.kt
→ feature/feature-profiles/src/main/kotlin/com/hyperxray/an/feature/profiles/ui/ConfigScreen.kt

// Update imports:
- import com.hyperxray.an.viewmodel.MainViewModel
+ import com.hyperxray.an.feature.profiles.viewmodel.ProfileViewModel

// Update usage:
- mainViewModel.configFiles
+ profileViewModel.configFiles
```

### Step 2: Update app/build.gradle
```gradle
dependencies {
    // ... existing dependencies ...
    
    // Feature modules
    implementation project(':feature:feature-vless')
    implementation project(':feature:feature-reality')
    implementation project(':feature:feature-hysteria2')
    implementation project(':feature:feature-routing')
    implementation project(':feature:feature-profiles')
}
```

### Step 3: Update navigation
```kotlin
// AppNavGraph.kt
import com.hyperxray.an.feature.profiles.ui.ConfigScreen
import com.hyperxray.an.feature.profiles.ui.ConfigEditScreen
// ... other feature screens
```

### Step 4: Clean up MainViewModel
```kotlin
// Remove:
- private val _configFiles: MutableStateFlow<List<File>>
- private val _selectedConfigFile: MutableStateFlow<File?>
- fun refreshConfigFileList()
- fun updateSelectedConfigFile(file: File?)
- fun moveConfigFile(fromIndex: Int, toIndex: Int)
```

## Breaking Changes

### Before Migration
```kotlin
// MainViewModel had profile management
mainViewModel.configFiles
mainViewModel.selectedConfigFile
mainViewModel.refreshConfigFileList()
```

### After Migration
```kotlin
// ProfileViewModel handles profiles
profileViewModel.configFiles
profileViewModel.selectedConfigFile
profileViewModel.refreshConfigFileList()

// MainViewModel focuses on app-level state
mainViewModel.isServiceEnabled
mainViewModel.coreStatsState
```

## Testing Checklist

- [ ] Verify feature modules compile independently
- [ ] Test profile list display and selection
- [ ] Test config file editing
- [ ] Verify VLESS link import works
- [ ] Verify REALITY optimization works
- [ ] Test navigation between screens
- [ ] Verify no circular dependencies

## Next Phase: AI Policy Module

After completing this migration:
- **TODO**: Migrate AI policy logic to `feature-policy-ai`
  - `AiInsightsViewModel`
  - `RealityWorker`, `RealityWorkManager`
  - ONNX inference logic
  - Telemetry collection and processing

## Notes

- Features temporarily depend on `:app` for `Preferences` and `FileManager`. This will be resolved in a future refactor by moving these to core modules.
- `ConfigFormatConverter` interface is used by both app and feature-vless. Consider creating a shared interface in a core module.
- Navigation is still handled in app module, which is correct per requirements.

