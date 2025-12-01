---
inclusion: fileMatch
fileMatchPattern: '*.kt|*.java|*.xml|build.gradle'
---

# Android Development Standards

## Kotlin Code Style
- Follow official Kotlin coding conventions
- Use `ktlint` for formatting consistency
- Prefer immutable data (`val` over `var`)
- Use data classes for DTOs and models
- Leverage sealed classes for state management

## Compose UI Guidelines
- Use Material 3 components from design system
- Keep composables small and focused
- Extract reusable components to `ui/components/`
- Use `remember` and `derivedStateOf` appropriately
- Follow unidirectional data flow (UDF)

## Architecture Patterns
- MVVM with ViewModels for UI state
- Repository pattern for data access
- Use cases for complex business logic
- Dependency injection via Hilt/Koin

## Resource Naming
- Layouts: `activity_`, `fragment_`, `item_`, `dialog_`
- Drawables: `ic_` (icons), `bg_` (backgrounds), `img_` (images)
- Strings: `label_`, `error_`, `action_`, `hint_`
- Colors: Use semantic names (`colorPrimary`, `colorSurface`)

## Performance Best Practices
- Use `LaunchedEffect` for side effects in Compose
- Avoid recomposition with stable keys
- Use `Baseline Profiles` for startup optimization
- Profile with Android Studio Profiler

## Security Guidelines
- Never hardcode API keys or secrets
- Use EncryptedSharedPreferences for sensitive data
- Validate all user inputs
- Use ProGuard/R8 for release builds

## Testing
- Unit tests for ViewModels and Use Cases
- UI tests with Compose Testing
- Integration tests for repositories
- Target 80%+ coverage for business logic
