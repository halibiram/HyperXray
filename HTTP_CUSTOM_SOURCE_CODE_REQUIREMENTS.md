# HTTP Custom Kaynak Kod İhtiyaçları

## Psiphon Tunnel Başlatma İçin Gerekli Bilgiler

### 1. Psiphon Tunnel Çağrısı

**Dosya:** HTTP Custom'un Psiphon Tunnel'ı çağıran Java/Kotlin kodu
**İhtiyaç:**

- `ca.psiphon.PsiphonTunnel.runTun2Socks()` çağrısının tam kodu
- Parametrelerin nasıl hazırlandığı
- UDP gateway (`udpgwAddr`) parametresinin nasıl geçildiği
- Boş string veya NULL durumunda ne yapıldığı

**Örnek arama:**

```java
// Şunları arayın:
PsiphonTunnel.runTun2Socks
udpgwAddr
udpgwServer
UDP gateway
```

### 2. Psiphon Tunnel Native Fonksiyon İmzası

**Dosya:** Native C kodu (libsocks2tun.so kaynak kodu veya JNI wrapper)
**İhtiyaç:**

- `Java_ca_psiphon_PsiphonTunnel_runTun2Socks` fonksiyonunun tam imzası
- Return type: `void` mi `int` mi?
- UDP gateway parametresinin nasıl parse edildiği
- Boş string durumunda ne yapıldığı

**Örnek arama:**

```c
// Şunları arayın:
Java_ca_psiphon_PsiphonTunnel_runTun2Socks
BAddr_Parse2
udpgw
UDP gateway parsing
```

### 3. UDP Gateway Kullanımı

**Dosya:** HTTP Custom'un VPN servis kodu
**İhtiyaç:**

- UDP gateway adresinin nereden alındığı
- Boş veya NULL olduğunda ne yapıldığı
- Default değer var mı?

**Örnek arama:**

```java
// Şunları arayın:
udpgw
UDP gateway
127.0.0.1:7300
udpgwAddr
```

### 4. Transparent DNS Kullanımı

**Dosya:** HTTP Custom'un VPN konfigürasyon kodu
**İhtiyaç:**

- `transparentDNS` veya `udpgwTransparent` parametresinin nasıl kullanıldığı
- Default değeri nedir?

**Örnek arama:**

```java
// Şunları arayın:
transparentDNS
udpgwTransparent
transparent
```

## Öncelikli Dosyalar

1. **VPN Service/Manager Sınıfı**

   - Psiphon Tunnel'ı başlatan ana kod
   - Örnek: `VpnService.java`, `TunnelManager.java`, `PsiphonManager.java`

2. **Psiphon Tunnel Wrapper**

   - `ca.psiphon.PsiphonTunnel` sınıfı
   - Native çağrıları yapan kod

3. **Native C Kodu (libsocks2tun.so)**

   - `Java_ca_psiphon_PsiphonTunnel_runTun2Socks` implementasyonu
   - UDP gateway parsing kodu

4. **Konfigürasyon Dosyaları**
   - VPN parametrelerinin nasıl hazırlandığı
   - Default değerler

## Önemli Sorular

1. **UDP Gateway:**

   - Boş string geçildiğinde ne oluyor?
   - NULL geçilebilir mi?
   - Default değer var mı? (örn: "127.0.0.1:7300")

2. **Return Type:**

   - `runTun2Socks` `void` mi `int` mi döndürüyor?
   - Başarı kontrolü nasıl yapılıyor?

3. **Hata Yönetimi:**
   - "BAddr_Parse2 failed" hatası nasıl handle ediliyor?
   - UDP gateway parse hatası durumunda ne yapılıyor?

## Bulunması Gereken Kod Örnekleri

### Java/Kotlin Tarafı:

```java
// Örnek 1: Psiphon Tunnel başlatma
PsiphonTunnel.runTun2Socks(
    tunFd,
    mtu,
    vpnIp,
    netmask,
    socksServer,
    udpgwAddr,  // <-- Bu nasıl hazırlanıyor?
    transparentDNS
);

// Örnek 2: UDP gateway hazırlama
String udpgwAddr = ...; // <-- Bu nasıl belirleniyor?
if (udpgwAddr == null || udpgwAddr.isEmpty()) {
    // Ne yapılıyor?
}
```

### C Tarafı:

```c
// Örnek: Native fonksiyon
JNIEXPORT void JNICALL
Java_ca_psiphon_PsiphonTunnel_runTun2Socks(
    JNIEnv *env,
    jclass clazz,
    jint tunFd,
    jint mtu,
    jstring router,
    jstring netmask,
    jstring socksServer,
    jstring udpgwAddr,  // <-- Bu nasıl parse ediliyor?
    jint transparentDNS
) {
    // UDP gateway parsing kodu
    // BAddr_Parse2 çağrısı
}
```

## Mevcut Sorunlar

1. **"BAddr_Parse2 failed" hatası:**

   - UDP gateway boş string olduğunda oluşuyor
   - HTTP Custom bunu nasıl çözüyor?

2. **Return code kontrolü:**

   - Psiphon Tunnel başarılı mı başarısız mı nasıl anlaşılıyor?
   - Exception mı kontrol ediliyor, return code mu?

3. **Fallback mekanizması:**
   - Psiphon Tunnel başarısız olursa ne yapılıyor?
   - Başka bir kütüphane kullanılıyor mu?

## Notlar

- Özellikle `libsocks2tun.so` kaynak kodu çok önemli
- Psiphon Tunnel'ın orijinal kaynak koduna da bakılabilir (Psiphon projesi)


