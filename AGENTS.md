# Repository Guidelines

## Project Structure & Module Organization
- Android app: `app/` (Kotlin + Compose, gRPC/Protobuf).
- Native code: `app/src/main/jni/` (ndk-build, SOCKS5 tunnel).
- Assets & models: `app/src/main/assets/` (ONNX models, templates, geo data).
- Build config: `build.gradle`, `settings.gradle`, `gradle.properties`.
- External artifacts: `boringssl-*`, `xray-*`, `artifacts/` (do not modify directly).
- Tools: `tools/` (Python scripts for training/exporting ONNX policy).

## Build, Test, and Development Commands
- Build debug APK: `./gradlew assembleDebug`
- Install debug: `./gradlew :app:installDebug`
- Clean: `./gradlew clean`
- Download geo data: `./gradlew :app:downloadGeoFiles`
- Train ONNX policy (optional): `./gradlew :app:trainOnnxModel`
Notes: Ensure Android SDK/NDK and JDK are configured (see `gradle.properties`).

## Coding Style & Naming Conventions
- Kotlin: Android Kotlin style; 4-space indent; prefer idiomatic coroutines.
- Packages: `com.hyperxray.*`
- Names: Classes/objects PascalCase; methods/vars camelCase; constants UPPER_SNAKE_CASE.
- XML resources: `lowercase_underscore`.
- Native C: follow `.clang-format` where present under `jni/`.

## Testing Guidelines
- Unit tests: `app/src/test/` (JUnit + Mockito/Kotlin test libs).
- Instrumented tests: `app/src/androidTest/` (Espresso/UI Automator).
- Naming: `FeatureNameTest.kt` or `FeatureNameInstrumentedTest.kt`.
- Aim for coverage on changed code; add tests for new features and bug fixes.

## Commit & Pull Request Guidelines
- **Language**: All commit messages must be in **English**.
- Conventional Commits: `feat:`, `fix:`, `refactor:`, `docs:`, `chore:`
  - Example: `fix: handle null telemetry in TelemetryStore`
  - Example: `feat: add DNS cache optimization for better performance`
  - Example: `refactor: simplify TProxyService connection handling`
- PRs must include:
  - Clear description, scope, and rationale
  - Linked issues (e.g., `Closes #123`)
  - Screenshots/logs for UI or behavior changes
  - Notes on migration/compatibility if applicable

## Security & Configuration Tips
- Never commit secrets (keystores, tokens, API keys). Review `store.properties` and CI vars.
- Do not edit `artifacts/`, `boringssl-*`, or `xray-*` unless explicitly required.
- Large assets/models belong in `app/src/main/assets/` with versioned manifests.

## Agent-Specific Instructions
- Scope: entire repo. Keep changes minimal, localized, and reversible.
- Prefer Gradle tasks over manual steps; avoid editing vendored/binary code.
- Coordinate changes touching native, protobuf, or assets via dedicated tasks.

