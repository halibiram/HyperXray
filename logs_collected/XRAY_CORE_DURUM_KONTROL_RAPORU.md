# Xray-core Durum Kontrol Raporu

**Tarih**: 28 Kasım 2024 09:34  
**Durum**: ❌ Xray-core Çalışmıyor - Native Library Yüklenemedi

---

## 📋 Özet

ADB logları analiz edildi. Xray-core çalışmıyor çünkü native Go library yüklenemedi. `callAiLogHelper` sembolü bulunamıyor.

---

## ❌ Kritik Sorun: Native Library Yüklenemedi

### Hata Detayları

```
11-28 09:34:23.502 27778 27778 I HyperXray-JNI: JNI_OnLoad called
11-28 09:34:23.502 27778 27778 I HyperXray-JNI: Loading Go library...
11-28 09:34:23.502 27778 27778 D HyperXray-JNI: Attempting to load: libhyperxray.so
11-28 09:34:23.508 27778 27778 E HyperXray-JNI: Failed to load libhyperxray.so: dlopen failed: cannot locate symbol "callAiLogHelper" referenced by "/data/app/~~FzKorKP7Fm1kFiDalb2mHQ==/com.hyperxray.an-zpC_SQEQttZaicaQk8Tvtg==/lib/arm64/libhyperxray.so"...
11-28 09:34:23.508 27778 27778 D HyperXray-JNI: Attempting to load: hyperxray
11-28 09:34:23.508 27778 27778 E HyperXray-JNI: Failed to load hyperxray: dlopen failed: library "hyperxray" not found
11-28 09:34:23.508 27778 27778 E HyperXray-JNI: CRITICAL: Could not load Go library with any name!
11-28 09:34:23.508 27778 27778 E HyperXray-JNI: Failed to load Go library during JNI_OnLoad
11-28 09:34:23.893 27778 28023 D HyperXray-JNI: isNativeLibraryReady called, goLibraryLoaded=0
```

**Analiz**:

- ❌ `libhyperxray.so` yüklenmeye çalışıldı
- ❌ `callAiLogHelper` sembolü bulunamadı
- ❌ Alternatif `hyperxray` library de bulunamadı
- ❌ Go library hiçbir şekilde yüklenemedi
- ❌ `goLibraryLoaded=0` - Library yüklenmedi

---

## ✅ Çalışan Bileşenler

### 1. ✅ VPN Servisi Çalışıyor

```
11-28 09:34:23.889  3688  6456 I Vpn     : Established by com.hyperxray.an on tun0
11-28 09:34:23.889  3688  6456 D ConnectivityService: registerNetworkAgent NetworkAgentInfo{network{238}  handle{1025607913485}  ni{VPN CONNECTING extra: VPN:com.hyperxray.an} created=2025-11-28T06:34:23.889Z Score(Policies : IS_VPN ; KeepConnected : 0)   lp{{InterfaceName: tun0 LinkAddresses: [ 10.0.0.2/30 ] DnsAddresses: [ ] Domains:  MTU: 1500 Routes: [ 0.0.0.0/0 -> 0.0.0.0 tun0 mtu 0,::/0 unreachable mtu 0 ]}}
```

**Analiz**:

- ✅ VPN servisi başarıyla başlatıldı
- ✅ `tun0` interface oluşturuldu
- ✅ IP adresi atandı: `10.0.0.2/30`
- ✅ Routing yapılandırıldı

### 2. ✅ Native Process Çalışıyor

```
u0_a570      27778  1674   18735736 269924 0                   0 S com.hyperxray.an:native
```

**Analiz**:

- ✅ Native process çalışıyor (PID: 27778)
- ✅ Memory kullanımı: ~270 MB
- ⚠️ Ancak Go library yüklenemediği için işlevsel değil

### 3. ✅ Ana Uygulama Çalışıyor

```
u0_a570      16638  1674   28005528 782912 0                   0 S com.hyperxray.an
```

**Analiz**:

- ✅ Ana uygulama çalışıyor (PID: 16638)
- ✅ Memory kullanımı: ~783 MB

---

## 🔬 Kök Neden Analizi

### Sorun: `callAiLogHelper` Sembolü Bulunamıyor

**Olası Nedenler**:

1. **Build Sorunu**: `libhyperxray.so` build edilirken `callAiLogHelper` export edilmemiş
2. **Link Sorunu**: Go library ile JNI arasında link sorunu var
3. **Sembol Eksikliği**: `callAiLogHelper` fonksiyonu Go tarafında tanımlanmamış veya export edilmemiş
4. **ABI Uyumsuzluğu**: Native library ABI uyumsuzluğu

**Etki**:

- ❌ Go library yüklenemiyor
- ❌ Xray-core başlatılamıyor
- ❌ Bridge çalışmıyor
- ❌ Tunnel oluşturulamıyor

---

## 💡 Çözüm Önerileri

### 1. 🔧 `callAiLogHelper` Sembolü Sorunu

**Durum**:

- ✅ `callAiLogHelper` fonksiyonu JNI dosyasında tanımlı (`hyperxray-jni.c:1171`)
- ❌ Ancak Go library build edilirken bu sembol export edilmemiş
- ❌ Go library `callAiLogHelper` sembolünü bulamıyor

**Kök Neden**:

Go library (`libhyperxray.so`) build edilirken JNI dosyası (`hyperxray-jni.c`) ile link edilmemiş. Bu yüzden `callAiLogHelper` sembolü Go library'de mevcut değil.

**Çözüm**:

1. **Seçenek 1**: JNI dosyasını Go library build'ine dahil et

   - `hyperxray-jni.c` dosyasını Go build'e dahil et
   - CGO ile JNI dosyasını link et

2. **Seçenek 2**: `callAiLogHelper`'ı optional yap

   - Go kodunda `callAiLogHelper` çağrısını optional yap
   - Sembol yoksa sadece Android log kullan

3. **Seçenek 3**: Build script'lerini düzelt
   - CMakeLists.txt veya build script'lerini kontrol et
   - JNI dosyasını Go library ile link et

**Kontrol Edilecek Dosyalar**:

- `app/src/main/jni/hyperxray-jni/hyperxray-jni.c` - `callAiLogHelper` tanımı (satır 1171)
- `native/bridge/bridge.go` - `callAiLogHelper` kullanımı
- `CMakeLists.txt` veya build script'leri - Link ayarları

### 2. 🔧 Native Library Build'i Kontrol Et

**Adımlar**:

1. Native library'nin doğru build edildiğinden emin ol
2. Sembol export'larını kontrol et
3. ABI uyumluluğunu kontrol et
4. Library'nin doğru yüklendiğinden emin ol

**Kontrol Komutları**:

```bash
# Sembol kontrolü
nm -D libhyperxray.so | grep callAiLogHelper

# Library bağımlılıkları
ldd libhyperxray.so

# Library bilgisi
readelf -d libhyperxray.so
```

### 3. 🔧 JNI Binding'leri Kontrol Et

**Adımlar**:

1. JNI binding'lerinin doğru tanımlandığından emin ol
2. `JNI_OnLoad` fonksiyonunu kontrol et
3. Sembol çözümlemesini kontrol et

### 4. 🔧 Build Script'lerini Kontrol Et

**Adımlar**:

1. Build script'lerinin doğru çalıştığından emin ol
2. Go build flag'lerini kontrol et
3. CGO ayarlarını kontrol et
4. Link ayarlarını kontrol et

---

## 📝 Sonraki Adımlar

### Acil Eylemler

1. ⏳ `callAiLogHelper` fonksiyonunu Go kodunda kontrol et
2. ⏳ Native library build'ini kontrol et
3. ⏳ Sembol export'larını kontrol et
4. ⏳ JNI binding'lerini kontrol et

### Test ve Doğrulama

1. ⏳ Native library'yi yeniden build et
2. ⏳ Sembol export'larını doğrula
3. ⏳ Library yüklemesini test et
4. ⏳ Xray-core başlatmayı test et

### Beklenen Sonuçlar

**Native Library Yüklendiğinde**:

- ✅ `goLibraryLoaded=1`
- ✅ Xray-core başlatılabilir
- ✅ Bridge çalışır
- ✅ Tunnel oluşturulabilir

---

## 📊 Durum Özeti

| Bileşen        | Durum          | Detay                                           |
| -------------- | -------------- | ----------------------------------------------- |
| VPN Servisi    | ✅ Çalışıyor   | tun0 interface oluşturuldu                      |
| Native Process | ✅ Çalışıyor   | PID: 27778                                      |
| Ana Uygulama   | ✅ Çalışıyor   | PID: 16638                                      |
| Go Library     | ❌ Yüklenemedi | `callAiLogHelper` sembolü bulunamadı            |
| Xray-core      | ❌ Çalışmıyor  | Go library yüklenemediği için başlatılamıyor    |
| Bridge         | ❌ Çalışmıyor  | Go library yüklenemediği için çalışmıyor        |
| Tunnel         | ⚠️ Kısmen      | VPN interface oluşturuldu ama tunnel çalışmıyor |

---

## 📌 Notlar

- ❌ **Kritik**: Native Go library yüklenemediği için xray-core çalışmıyor
- ⚠️ VPN servisi çalışıyor ama tunnel işlevsel değil
- ⚠️ `callAiLogHelper` sembolü bulunamıyor - build sorunu olabilir
- ⚠️ Native library build'i ve sembol export'ları kontrol edilmeli

---

**Rapor Oluşturulma Tarihi**: 28 Kasım 2024 09:34  
**Son Güncelleme**: 28 Kasım 2024 09:34  
**Durum**: ❌ Xray-core Çalışmıyor - Native Library Yüklenemedi
