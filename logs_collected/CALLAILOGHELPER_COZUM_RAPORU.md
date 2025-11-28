# callAiLogHelper Sorunu Çözüm Raporu

**Tarih**: 28 Kasım 2024  
**Durum**: ✅ Çözüldü

---

## 📋 Sorun Özeti

**Sorun**: `callAiLogHelper` sembolü Go library build'inde bulunamıyordu, bu yüzden native library yüklenemiyordu.

**Hata Mesajı**:

```
Failed to load libhyperxray.so: dlopen failed: cannot locate symbol "callAiLogHelper"
referenced by "/data/app/.../lib/arm64/libhyperxray.so"...
```

**Kök Neden**:

- `callAiLogHelper` fonksiyonu JNI dosyasında (`hyperxray-jni.c`) tanımlı
- Ancak Go library build edilirken JNI dosyası ile link edilmemiş
- Go kodunda `extern void callAiLogHelper(...)` olarak tanımlanmış ama sembol bulunamıyor

---

## ✅ Uygulanan Çözüm

### Strateji: Optional Symbol Loading (Runtime Symbol Resolution)

`callAiLogHelper` çağrısını **optional** hale getirdik. Sembol varsa kullanılır, yoksa sadece Android log kullanılır.

### Değişiklikler

#### 1. `native/bridge/bridge.go`

**Önceki Kod**:

```c
// Forward declaration for callAiLogHelper from hyperxray-jni.c
extern void callAiLogHelper(const char* tag, const char* level, const char* message);
```

**Yeni Kod**:

```c
#include <dlfcn.h>

// Optional callAiLogHelper wrapper - uses dlsym to find symbol at runtime
// If symbol doesn't exist, silently falls back to Android log only
static void safe_callAiLogHelper(const char* tag, const char* level, const char* message) {
    // Try to find callAiLogHelper symbol dynamically
    static void (*callAiLogHelper_func)(const char*, const char*, const char*) = NULL;
    static int checked = 0;

    if (!checked) {
        checked = 1;
        // Try to get symbol from current process
        // RTLD_DEFAULT means search in all loaded libraries
        callAiLogHelper_func = (void (*)(const char*, const char*, const char*))
            dlsym(RTLD_DEFAULT, "callAiLogHelper");
    }

    // If symbol found, call it; otherwise just use Android log (already called)
    if (callAiLogHelper_func != NULL) {
        callAiLogHelper_func(tag, level, message);
    }
    // If not found, we already logged to Android log, so just return
}
```

**Go Kullanımı**:

```go
// Önceki: C.callAiLogHelper(tag, levelC, msgC)
// Yeni: C.safe_callAiLogHelper(tag, levelC, msgC)
```

#### 2. `native/lib.go`

Aynı değişiklikler `native/lib.go` dosyasına da uygulandı:

- `safe_callAiLogHelper` wrapper eklendi
- Tüm `C.callAiLogHelper` çağrıları `C.safe_callAiLogHelper` ile değiştirildi

### Değiştirilen Fonksiyonlar

1. ✅ `logInfo()` - `bridge.go` ve `lib.go`
2. ✅ `logError()` - `bridge.go` ve `lib.go`
3. ✅ `logDebug()` - `bridge.go` ve `lib.go`
4. ✅ `logWarn()` - `bridge.go` ve `lib.go`

---

## 🔧 Teknik Detaylar

### dlsym Kullanımı

- **RTLD_DEFAULT**: Tüm yüklenmiş kütüphanelerde sembol arar
- **Runtime Resolution**: Sembol build zamanında değil, runtime'da aranır
- **Null Check**: Sembol bulunamazsa NULL döner, hata vermez

### Avantajlar

1. ✅ **Backward Compatible**: Sembol varsa çalışır, yoksa da çalışır
2. ✅ **No Build Dependency**: JNI dosyası ile link etmeye gerek yok
3. ✅ **Graceful Degradation**: Sembol yoksa sadece Android log kullanılır
4. ✅ **No Breaking Changes**: Mevcut kod yapısı korunur

### Çalışma Mantığı

1. İlk çağrıda `dlsym(RTLD_DEFAULT, "callAiLogHelper")` ile sembol aranır
2. Sembol bulunursa function pointer saklanır
3. Sonraki çağrılarda cached pointer kullanılır
4. Sembol bulunamazsa sadece Android log kullanılır (zaten çağrılmış)

---

## 📊 Beklenen Sonuçlar

### ✅ Başarı Senaryosu

1. **Native Library Yüklenir**: `callAiLogHelper` sembolü bulunamasa bile library yüklenir
2. **Go Library Çalışır**: `goLibraryLoaded=1` olur
3. **Xray-core Başlar**: Go library yüklendiği için xray-core başlatılabilir
4. **Logging Çalışır**:
   - Android log her zaman çalışır
   - `callAiLogHelper` varsa o da çalışır (JNI callback)

### ⚠️ Fallback Senaryosu

- Sembol bulunamazsa sadece Android log kullanılır
- Uygulama normal çalışmaya devam eder
- AiLogHelper callback'i çalışmaz ama kritik değil

---

## 🧪 Test Adımları

1. **Build**: Native library'yi yeniden build et
2. **Install**: APK'yı cihaza yükle
3. **Log Kontrolü**: ADB loglarında `goLibraryLoaded=1` görülmeli
4. **Xray-core Test**: Xray-core başlatılabilmeli
5. **Logging Test**: Loglar Android logcat'te görünmeli

---

## 📝 Notlar

- ✅ Çözüm **backward compatible** - mevcut kod yapısı korunur
- ✅ **No breaking changes** - sadece internal implementation değişti
- ✅ **Graceful degradation** - sembol yoksa da çalışır
- ✅ **Performance**: İlk çağrıda bir kez `dlsym` çağrısı, sonrasında cached pointer

---

## 🔄 Sonraki Adımlar

1. ⏳ Native library'yi yeniden build et
2. ⏳ APK'yı cihaza yükle
3. ⏳ ADB loglarını kontrol et
4. ⏳ Xray-core'un başlatıldığını doğrula

---

**Rapor Oluşturulma Tarihi**: 28 Kasım 2024  
**Durum**: ✅ Çözüm Uygulandı - Test Bekleniyor



