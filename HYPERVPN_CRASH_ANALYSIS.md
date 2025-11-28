# HyperVpnService Crash Analizi

## Sorun Özeti

HyperVpnService başlatıldığında `UnsatisfiedLinkError` hatası ile crash oluyor. Hata mesajı:

```
java.lang.UnsatisfiedLinkError: No implementation found for int com.hyperxray.an.vpn.HyperVpnService.startHyperTunnel(int, java.lang.String, java.lang.String, java.lang.String, java.lang.String)
```

## Teknik Detaylar

### 1. Hata Konumu

- **Dosya**: `app/src/main/kotlin/com/hyperxray/an/vpn/HyperVpnService.kt`
- **Satır**: 171 (startVpn fonksiyonu içinde)
- **Fonksiyon**: `startHyperTunnel()` native method çağrısı

### 2. Root Cause Analizi

#### 2.1. JNI Fonksiyon Eşleştirme Sorunu

Go kütüphanesi (`libhyperxray.so`) C fonksiyonları olarak export ediyor:

- `StartHyperTunnel` (C formatı)
- `StopHyperTunnel`
- `GetTunnelStats`
- `NativeGeneratePublicKey`
- `FreeString`

Ancak JNI, Java fonksiyonlarını şu formatta arıyor:

- `Java_com_hyperxray_an_vpn_HyperVpnService_startHyperTunnel`
- `Java_com_hyperxray_an_vpn_HyperVpnService_stopHyperTunnel`
- vb.

**Sorun**: Go'dan export edilen C fonksiyonları ile JNI'nin beklediği fonksiyon formatı uyuşmuyor.

#### 2.2. JNI Wrapper Durumu

JNI wrapper (`app/src/main/jni/hyperxray-jni/hyperxray-jni.c`) oluşturuldu ve build edildi:

- ✅ Build başarılı: `app/build/intermediates/ndkBuild/debug/obj/local/arm64-v8a/libhyperxray.so` (31KB)
- ✅ APK'da mevcut: `lib/arm64-v8a/libhyperxray.so`
- ✅ Library yükleniyor: Loglarda "JNI wrapper (hyperxray) loaded successfully" görünüyor

**Ancak**: JNI fonksiyonları hala bulunamıyor.

#### 2.3. Olası Nedenler

1. **JNI Wrapper Override Edilmiş Olabilir**

   - APK'da hem `libhyperxray.so` (JNI wrapper) hem de `libhyperxray-go.so` (Go library) var
   - Eğer eski Go library (`libhyperxray.so`) hala `jniLibs` dizinindeyse, build edilen JNI wrapper override edilmiş olabilir

2. **dlopen Başarısız Oluyor**

   - JNI wrapper, Go library'yi `dlopen("libhyperxray-go.so")` ile yüklemeye çalışıyor
   - Android'de library search path'i doğru olmayabilir
   - Go library bulunamıyorsa, fonksiyon pointer'ları NULL kalıyor

3. **Symbol Resolution Başarısız**

   - Go library yüklenmiş olsa bile, `dlsym()` ile symbol'ler bulunamıyor olabilir
   - Go'nun export ettiği symbol isimleri farklı olabilir

4. **JNI Fonksiyonları Export Edilmemiş**
   - JNI wrapper build edilmiş ama JNI fonksiyonları (`Java_com_hyperxray_an_vpn_HyperVpnService_*`) export edilmemiş olabilir
   - Android.mk veya build konfigürasyonunda bir sorun olabilir

## Log Analizi

### Başarılı İşlemler

```
11-27 13:30:35.636 D HyperVpnService: JNI wrapper (hyperxray) loaded successfully
```

✅ JNI wrapper library başarıyla yüklendi.

### Hata

```
11-27 13:30:36.924 E om.hyperxray.an: No implementation found for int com.hyperxray.an.vpn.HyperVpnService.startHyperTunnel(...)
```

❌ JNI fonksiyonu bulunamıyor.

### Stack Trace

```
at com.hyperxray.an.vpn.HyperVpnService.startHyperTunnel(Native Method)
at com.hyperxray.an.vpn.HyperVpnService.access$startHyperTunnel(HyperVpnService.kt:42)
at com.hyperxray.an.vpn.HyperVpnService$startVpn$1.invokeSuspend(HyperVpnService.kt:171)
```

## Mevcut Durum

### ✅ Yapılanlar

1. JNI wrapper oluşturuldu (`hyperxray-jni.c`)
2. JNI wrapper build edildi (31KB)
3. JNI wrapper APK'ya dahil edildi
4. Library yükleme kodu eklendi (dlopen/dlsym)
5. HyperVpnService'te library yükleme sırası düzeltildi

### ❌ Kalan Sorunlar

1. **JNI fonksiyonları bulunamıyor**

   - JNI wrapper yükleniyor ama JNI fonksiyonları (`Java_com_hyperxray_an_vpn_HyperVpnService_*`) bulunamıyor
   - Bu, ya JNI wrapper'ın doğru build edilmediği ya da fonksiyonların export edilmediği anlamına geliyor

2. **dlopen başarılı mı bilinmiyor**

   - JNI wrapper'da `loadGoLibrary()` fonksiyonu çağrılıyor ama başarılı olup olmadığı loglanmıyor
   - Go library yüklenemiyorsa, fonksiyon pointer'ları NULL kalıyor

3. **Symbol resolution başarılı mı bilinmiyor**

   - `dlsym()` ile symbol'ler aranıyor ama bulunup bulunmadığı kontrol edilmiyor
   - Go library'deki symbol isimleri farklı olabilir

4. **JNI wrapper override edilmiş olabilir**
   - `jniLibs` dizinindeki eski `libhyperxray.so` (Go library) build edilen JNI wrapper'ı override ediyor olabilir
   - APK'da iki farklı `libhyperxray.so` dosyası var (biri Go library, biri JNI wrapper)

## Tespit Edilen Sorunlar

### Sorun 1: JNI Fonksiyon Export Sorunu

- JNI wrapper build edilmiş ama JNI fonksiyonları export edilmemiş olabilir
- `nm` komutu ile kontrol edilmeli: `nm -D libhyperxray.so | grep Java_com_hyperxray`

### Sorun 2: Library Naming Conflict

- `libhyperxray.so` hem Go library hem de JNI wrapper için kullanılıyor
- Go library `libhyperxray-go.so` olarak kopyalandı ama eski `libhyperxray.so` hala `jniLibs` dizininde olabilir
- Build sistemi hangi library'yi kullanacağını belirleyemiyor olabilir

### Sorun 3: dlopen Path Sorunu

- Android'de `dlopen("libhyperxray-go.so")` çağrısı library'yi bulamıyor olabilir
- Android'in library search path'i farklı olabilir
- Full path kullanılmalı veya `System.loadLibrary()` ile yüklenmeli

### Sorun 4: Symbol Name Mismatch

- Go'nun export ettiği symbol isimleri (`StartHyperTunnel`) ile `dlsym()` ile aranan isimler uyuşmuyor olabilir
- Go, C fonksiyonlarını export ederken bazen prefix ekleyebilir

### Sorun 5: Debug Logging Eksik

- `loadGoLibrary()` fonksiyonunda hata durumları loglanmıyor
- `dlopen()` ve `dlsym()` hataları görünmüyor
- Sorunun tam olarak nerede olduğu tespit edilemiyor

## Sonuç

JNI wrapper build edilmiş ve yükleniyor, ancak JNI fonksiyonları bulunamıyor. Bu, ya JNI wrapper'ın doğru build edilmediği (JNI fonksiyonları export edilmemiş), ya da Go library'nin dlopen ile yüklenemediği (fonksiyon pointer'ları NULL) anlamına geliyor. Debug loglama eklenmeden sorunun tam olarak nerede olduğu tespit edilemiyor.
