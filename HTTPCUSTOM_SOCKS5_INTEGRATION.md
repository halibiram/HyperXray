# HTTP Custom SOCKS5 Kütüphaneleri Entegrasyonu

## Eklenen Kütüphaneler

1. **libsocks.so** (150 KB) - SOCKS5 proxy implementasyonu
2. **libsocks2tun.so** (192 KB) - SOCKS5'ten TUN'a dönüştürme
3. **libtun2socks.so** (197 KB) - TUN'dan SOCKS5'e dönüştürme

## Konum

Kütüphaneler `app/src/main/jniLibs/arm64-v8a/` dizinine kopyalandı.

## Mevcut Durum

Proje şu anda `hev-socks5-tunnel` kullanıyor:

- `System.loadLibrary("hev-socks5-tunnel")` ile yükleniyor
- JNI fonksiyonları: `TProxyStartService`, `TProxyStopService`, `TProxyGetStats`

## Entegrasyon Planı

### Seçenek 1: HTTP Custom Kütüphanelerini Doğrudan Kullanma

HTTP Custom'un kütüphanelerinin JNI interface'ini bulmak gerekiyor. Muhtemelen farklı fonksiyon isimleri var.

**Gerekenler:**

- JNI fonksiyon isimlerini belirleme
- Native method signature'ları
- Config formatı

### Seçenek 2: Wrapper/Adapter Oluşturma

HTTP Custom kütüphanelerini hev-socks5-tunnel interface'i ile uyumlu hale getirmek.

### Seçenek 3: Hibrit Kullanım

- `libsocks.so` - SOCKS5 proxy için
- `libtun2socks.so` - TUN2SOCKS için
- `libsocks2tun.so` - SOCKS2TUN için

## Yapılan Değişiklikler

### ✅ TProxyService.kt - Doğrudan HTTP Custom Kullanımı

- HTTP Custom kütüphanelerini yüklemeyi deniyor (`libsocks`, `libsocks2tun`, `libtun2socks`)
- **Doğrudan JNI fonksiyonları kullanılıyor** (wrapper yok):
  - `Java_ca_psiphon_PsiphonTunnel_runTun2Socks` / `terminateTun2Socks`
  - `Java_org_bugs4u_proxyserver_core_Tun2Socks_runTun2Socks` / `terminateTun2Socks`
- Başarısız olursa `hev-socks5-tunnel`'a fallback yapıyor
- `startTProxyService()` ve `stopTProxyService()` wrapper metodları eklendi
- `isUsingHttpCustomLibs()` metodu eklendi

### Kütüphane Yükleme Sırası

1. Önce HTTP Custom kütüphaneleri deneniyor
2. Başarısız olursa hev-socks5-tunnel yükleniyor
3. Her ikisi de başarısız olursa hata fırlatılıyor

## Sonraki Adımlar

1. ✅ Kütüphaneleri jniLibs'e kopyalama
2. ✅ TProxyService'i güncelleme (doğrudan HTTP Custom JNI kullanımı)
3. ✅ JNI interface'ini analiz etme - **BULUNDU!**
4. ✅ Doğrudan JNI fonksiyonlarını entegre etme
5. ✅ Tüm TProxyStartService/StopService çağrılarını güncelleme
6. ⏳ Test etme

## JNI Interface Bulguları

Detaylı analiz için `HTTPCUSTOM_JNI_INTERFACE.md` dosyasına bakın.

**Bulunan JNI Fonksiyonları:**

### libsocks2tun.so (Psiphon Tunnel)

- `Java_ca_psiphon_PsiphonTunnel_runTun2Socks`
- `Java_ca_psiphon_PsiphonTunnel_terminateTun2Socks`
- `Java_ca_psiphon_PsiphonTunnel_disableUdpGwKeepalive`
- `Java_ca_psiphon_PsiphonTunnel_enableUdpGwKeepalive`

### libtun2socks.so (Bugs4U Tun2Socks)

- `Java_org_bugs4u_proxyserver_core_Tun2Socks_runTun2Socks`
- `Java_org_bugs4u_proxyserver_core_Tun2Socks_terminateTun2Socks`

## Notlar

⚠️ **Önemli**: HTTP Custom kütüphanelerinin JNI interface'i henüz bilinmiyor.

- Mevcut `TProxyStartService`, `TProxyStopService`, `TProxyGetStats` fonksiyonları hev-socks5-tunnel için
- HTTP Custom'un kendi JNI fonksiyon isimleri olabilir
- JNI interface'i bulunana kadar hev-socks5-tunnel kullanılacak
- HTTP Custom'un kütüphaneleri ARM64 için derlenmiş
- Mevcut hev-socks5-tunnel submodule'ü korunabilir (fallback için)

## Kod Değişiklikleri

### TProxyService.kt - init bloğu

```kotlin
init {
    // Try to load HTTP Custom libraries first, fallback to hev-socks5-tunnel
    try {
        System.loadLibrary("socks")
        System.loadLibrary("socks2tun")
        System.loadLibrary("tun2socks")
        useHttpCustomLibs = true
        Log.d(TAG, "Loaded HTTP Custom SOCKS5 libraries")
    } catch (e: UnsatisfiedLinkError) {
        Log.w(TAG, "HTTP Custom libraries not found, using hev-socks5-tunnel")
        System.loadLibrary("hev-socks5-tunnel")
        useHttpCustomLibs = false
    }
}
```
