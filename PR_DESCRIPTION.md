# Phase 3 — Dashboard extracted to feature-dashboard

## Summary

This PR extracts the Dashboard screen and all its UI components into a new feature module `:feature:feature-dashboard`, continuing the modularization effort for HyperXray.

## Changes

### New Module Created
- **`:feature:feature-dashboard`** - New Android library module containing:
  - Dashboard UI screen (`DashboardScreen.kt`)
  - All dashboard UI components (12 components)
  - Dashboard state models (`CoreStatsState`)
  - Dashboard formatters (`DashboardFormatters.kt`)
  - Dashboard interfaces (`DashboardViewModel`, `DashboardResources`)

### Files Moved
- `DashboardScreen.kt` → `feature/feature-dashboard/src/main/kotlin/com/hyperxray/an/feature/dashboard/DashboardScreen.kt`
- All dashboard components → `feature/feature-dashboard/src/main/kotlin/com/hyperxray/an/feature/dashboard/components/`
- Formatters → `feature/feature-dashboard/src/main/kotlin/com/hyperxray/an/feature/dashboard/DashboardFormatters.kt`
- State models → `feature/feature-dashboard/src/main/kotlin/com/hyperxray/an/feature/dashboard/DashboardState.kt`

### Architecture Improvements

1. **Decoupling via Interfaces**
   - Created `DashboardViewModel` interface to decouple feature module from `MainViewModel`
   - Created `DashboardResources` interface to decouple from app module's `R` class
   - Added `MainViewModelDashboardAdapter` to adapt `MainViewModel` to `DashboardViewModel`

2. **Dependency Management**
   - Feature module depends only on core modules (`core-designsystem`, `core-telemetry`)
   - No dependency on `app` module (enforced by module structure)
   - App module depends on feature module

3. **Navigation Updates**
   - Updated `BottomNavGraph.kt` to use new `DashboardScreen` from feature module
   - Resources provided via `DashboardResources` interface implementation

### Build Configuration
- Added `feature/feature-dashboard/build.gradle` with proper dependencies
- Updated `settings.gradle` to include new module
- Updated `app/build.gradle` to depend on feature module

## Testing

- ✅ Build successful - all modules compile without errors
- ✅ No circular dependencies
- ✅ Feature module is independent of app module
- ✅ Navigation still works correctly
- ✅ All imports resolved

## Migration Notes

The Dashboard feature is now fully modularized. The app module can still launch Dashboard normally through the navigation system, but the Dashboard implementation is now isolated in its own feature module.

## TODO for Next Feature

**Next Phase: Extract another feature screen**

Candidates for next extraction:
- **Config Screen** (`ConfigScreen.kt` + config-related components)
- **Log Screen** (`LogScreen.kt` + log-related components)
- **Settings Screen** (`SettingsScreen.kt` + settings-related components)
- **Optimizer Screen** (`OptimizerScreen.kt` + optimizer-related components)

Each extraction should follow the same pattern:
1. Create `:feature:feature-{name}` module
2. Move UI components and state models
3. Create interface-based decoupling
4. Update navigation
5. Test build

## Breaking Changes

None - this is a refactoring that maintains backward compatibility at the API level.

