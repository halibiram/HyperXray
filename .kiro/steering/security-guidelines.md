---
inclusion: manual
---

# Security Guidelines

## Sensitive Data Handling

### Never Commit
- API keys, tokens, passwords
- Private keys, certificates
- User credentials
- Production database URLs
- `.env` files with real values

### Use Environment Variables
```kotlin
// Good
val apiKey = BuildConfig.API_KEY

// Bad - Never do this
val apiKey = "sk-1234567890abcdef"
```

### Encrypted Storage
```kotlin
// Use EncryptedSharedPreferences for sensitive data
val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

val encryptedPrefs = EncryptedSharedPreferences.create(
    context,
    "secure_prefs",
    masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)
```

## Network Security

### Certificate Pinning
- Pin certificates for critical API endpoints
- Use OkHttp CertificatePinner
- Update pins before certificate rotation

### TLS Configuration
- Minimum TLS 1.2
- Prefer TLS 1.3 when available
- Use Conscrypt for modern TLS support

## Input Validation

### Always Validate
- User inputs before processing
- Server responses before parsing
- File paths before access
- URLs before navigation

### Sanitization
```kotlin
// Sanitize user input
fun sanitizeInput(input: String): String {
    return input
        .trim()
        .replace(Regex("[<>\"'&]"), "")
        .take(MAX_INPUT_LENGTH)
}
```

## VPN Security

### Socket Protection
- All sockets must be protected via VpnService
- Verify protection before data transmission
- Handle protection failures gracefully

### DNS Leak Prevention
- Route all DNS through VPN tunnel
- Use DoH/DoT for DNS queries
- Block DNS requests outside tunnel

## Code Security

### ProGuard/R8
- Enable for release builds
- Obfuscate sensitive class names
- Remove debug logs in release

### Native Code
- Validate JNI parameters
- Check buffer boundaries
- Free allocated memory properly
- Avoid storing secrets in native code

## Audit Logging

### Log Security Events
- Authentication attempts
- Permission changes
- Configuration modifications
- Error conditions

### Never Log
- Passwords or tokens
- Personal user data
- Full request/response bodies with sensitive data
