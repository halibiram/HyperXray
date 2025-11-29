# 🎯 Active Context - HyperXray Development

## Şu Anki Odak Alanı
**Modül:** Core VPN Service + Xray Integration
**Durum:** Development Phase - Optimization & Security
**Son Aktivite:** $(date)

## Teknik Detaylar

### Aktif Dosyalar
- **Core Service:** `app/src/main/kotlin/com/hyperxray/service/VpnService.kt`
- **JNI Bridge:** `native/bridge/bridge.go`
- **Xray Integration:** `xray/xray-runtime-service/`
- **ViewModel:** `app/src/main/kotlin/com/hyperxray/an/viewmodel/CoreState.kt`

### Aktif Konfigürasyon
```json
{
  "protocol": "vless",
  "security": "reality",
  "transport": "tcp",
  "features": ["fragment", "mux", "tls"]
}
```

### Debug Bilgileri
- **Build Variant:** debug
- **Target SDK:** 34 (Android 14)
- **Min SDK:** 24 (Android 7.0)
- **Architecture:** arm64-v8a, armeabi-v7a

## Açık Issues
- [ ] Memory leak in JNI bridge (high priority)
- [ ] Battery optimization needed
- [ ] Security audit pending
- [ ] Performance benchmarking required

## Son Değişiklikler
- **2025-01-XX:** Xray config validation eklendi
- **2025-01-XX:** JNI error handling improved
- **2025-01-XX:** Network security config updated

## Development Notes
- **Architecture Decision:** MVVM pattern kullanılıyor
- **Testing Strategy:** Unit tests + Integration tests
- **Code Style:** Kotlin coding conventions + Google style
- **Security:** Certificate pinning aktif, input validation zorunlu

## Next Steps
1. Security audit (@Security modunda)
2. Performance profiling
3. Memory optimization
4. Error handling improvements

---
*Bu dosya otomatik olarak güncellenir. Aktif development context'ini takip eder.*


