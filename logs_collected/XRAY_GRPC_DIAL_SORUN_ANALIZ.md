# Xray gRPC Dial Sorunu Analiz Raporu

**Tarih**: 30 Kasım 2024  
**Sorun**: gRPC API çalışmıyor, dial xray başarısız

---

## 📋 Sorun Özeti

**Kritik Bulgular:**
1. `Native gRPC available: false` - gRPC client oluşturulmuyor
2. `QueryStats only works its own stats.Manager` - Stats manager yapılandırılmamış
3. Go runtime stats alınamıyor
4. Xray çalışıyor ama gRPC client nil

---

## 🔍 Tespit Edilen Sorunlar

### 1. gRPC Client Oluşturulmuyor

**Loglar:**
```
Native gRPC available: false
IsXrayGrpcAvailableNative() false dönüyor
```

**Kod Yeri:**
- `native/lib.go:524-546` - `IsXrayGrpcAvailable()` fonksiyonu
- `native/bridge/xray.go:373-384` - `NewXrayGrpcClient()` çağrısı

**Sorun:**
- Xray başlatılırken gRPC client oluşturulmuyor
- `NewXrayGrpcClient()` hata dönüyor veya hiç çağrılmıyor
- `IsXrayGrpcAvailableNative()` false dönüyor çünkü `grpcClient == nil`

### 2. Stats Manager Yapılandırılmamış

**Loglar:**
```
CoreStatsClient: queryStats failed: UNKNOWN - app/stats/command: QueryStats only works its own stats.Manager.
```

**Kod Yeri:**
- `app/src/main/kotlin/com/hyperxray/an/core/config/utils/ConfigInjector.kt:95` - `jsonObject.put("stats", JSONObject())`

**Sorun:**
- ConfigInjector'da sadece boş stats objesi ekleniyor
- Stats manager aktif değil
- QueryStats çalışmıyor çünkü stats manager yapılandırılmamış

### 3. Xray Başlatma Logları Eksik

**Loglar:**
- Xray başlatma logları görünmüyor
- gRPC client oluşturma logları görünmüyor
- `[XrayGrpc] Creating gRPC client` logu yok

**Sorun:**
- Xray başlatılırken gRPC client oluşturulmuyor olabilir
- veya hata oluşuyor ama loglanmıyor

---

## ✅ Çözüm Önerileri

### 1. Stats Manager Yapılandırması

**Dosya:** `app/src/main/kotlin/com/hyperxray/an/core/config/utils/ConfigInjector.kt`

**Değişiklik:**
```kotlin
// Stats object - boş değil, aktif stats manager ile
val statsStartTime = System.currentTimeMillis()
// Stats manager'ı aktif etmek için boş obje yeterli değil
// Xray-core stats manager'ı otomatik olarak aktif eder, ama config'de stats objesi olmalı
jsonObject.put("stats", JSONObject()) // Bu yeterli, ama policy'de statsOutboundUplink/Downlink true olmalı
```

**Not:** Policy'de `statsOutboundUplink` ve `statsOutboundDownlink` zaten `true` olarak ayarlanmış (satır 102-103).

### 2. gRPC Client Oluşturma Kontrolü

**Dosya:** `native/bridge/xray.go`

**Kontrol:**
- `NewXrayGrpcClient()` çağrısı yapılıyor mu?
- Hata oluşuyor mu?
- Loglar görünüyor mu?

**Düzeltme:**
- Xray başlatılırken gRPC client oluşturulmasını sağlamak
- Hata durumunda detaylı log eklemek
- gRPC client oluşturulana kadar beklemek

### 3. Xray Başlatma Logları

**Kontrol:**
- Xray başlatılırken loglar görünüyor mu?
- gRPC client oluşturma logları var mı?

**Düzeltme:**
- Xray başlatma loglarını kontrol etmek
- gRPC client oluşturma loglarını eklemek

---

## 🔧 Uygulanacak Düzeltmeler

1. **Stats Manager Kontrolü:**
   - ConfigInjector'da stats objesi zaten ekleniyor
   - Policy'de statsOutboundUplink/Downlink zaten true
   - Sorun başka bir yerde olabilir

2. **gRPC Client Oluşturma:**
   - Xray başlatılırken gRPC client oluşturulmasını sağlamak
   - Hata durumunda detaylı log eklemek
   - gRPC client oluşturulana kadar beklemek

3. **Log İyileştirmeleri:**
   - Xray başlatma loglarını eklemek
   - gRPC client oluşturma loglarını eklemek
   - Hata durumlarında detaylı log eklemek

---

## 📊 Durum

- ✅ Xray çalışıyor (dial logları görünüyor)
- ❌ gRPC client oluşturulmuyor
- ❌ Stats manager yapılandırılmamış (QueryStats çalışmıyor)
- ❌ Go runtime stats alınamıyor

---

## 🎯 Sonraki Adımlar

1. Xray başlatma kodunu kontrol et
2. gRPC client oluşturma kodunu kontrol et
3. Stats manager yapılandırmasını kontrol et
4. Logları iyileştir
5. Test et


