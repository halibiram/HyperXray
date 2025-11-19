# HTTP Custom JNI Interface Bulguları

## Analiz Sonuçları

### libsocks2tun.so

HTTP Custom, **Psiphon Tunnel** kütüphanesini kullanıyor (BadVPN tun2socks tabanlı):

```java
// Java package: ca.psiphon.PsiphonTunnel
Java_ca_psiphon_PsiphonTunnel_runTun2Socks
Java_ca_psiphon_PsiphonTunnel_terminateTun2Socks
Java_ca_psiphon_PsiphonTunnel_disableUdpGwKeepalive
Java_ca_psiphon_PsiphonTunnel_enableUdpGwKeepalive
```

**Gerçek JNI İmzası (Binary Analiz):**

```java
// runTun2Socks
private native void runTun2Socks(
    int tunFd,              // VPN arayüzünün dosya tanımlayıcısı
    int mtu,                 // Maximum Transmission Unit
    String router,           // Sanal ağ arayüzü IP adresi (örn: "10.0.0.2")
    String netmask,          // Subnet mask (örn: "255.255.255.0")
    String socksServer,      // Yerel SOCKS proxy adresi (örn: "127.0.0.1:1080")
    String udpgwAddr,        // UDP Gateway sunucu adresi (Oyunlar/VoIP için, opsiyonel)
    boolean udpgwTransparent // UDP transparent mode (opsiyonel)
);

// terminateTun2Socks
private native void terminateTun2Socks();

// UDP Gateway Keepalive
private native void enableUdpGwKeepalive();
private native void disableUdpGwKeepalive();
```

**Callback Mekanizması:**

- `logTun2Socks(String level, String tag, String message)` - Native'den Java'ya log aktarımı

### libtun2socks.so

**Bugs4U ProxyServer** kütüphanesini kullanıyor (BadVPN tun2socks 1.999.129 tabanlı):

```java
// Java package: org.bugs4u.proxyserver.core.Tun2Socks
Java_org_bugs4u_proxyserver_core_Tun2Socks_runTun2Socks
Java_org_bugs4u_proxyserver_core_Tun2Socks_terminateTun2Socks
```

**JNI İmzası (HTTP Custom Raporuna Göre):**

```java
// runTun2Socks
// HTTP Custom raporuna göre tam parametreler:
private static native int runTun2Socks(
    int tunFd,      // TUN file descriptor
    int mtu,        // MTU size
    String vpnIp,   // VPN IP address (e.g., "10.0.0.2")
    String netmask, // VPN netmask (e.g., "255.255.255.0")
    String socks,   // SOCKS5 server address:port (e.g., "127.0.0.1:1082")
    String dnsgw,   // DNS gateway
    String udpgw,   // UDP gateway
    int transparentDNS // Transparent DNS flag (0 or 1)
);

// terminateTun2Socks
private static native int terminateTun2Socks();
```

**Callback Mekanizması:**

- `logTun2Socks(String level, String tag, String message)` - Native'den Java'ya log aktarımı
  - İmza: `(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V`

**Teknik Detaylar:**

- **Proje Yolu**: `/Volumes/Expansion/CODING/ANDROID/PLAYSTORE/HTTP_Custom_Psiphon_v2ray/app/src/main/jni/badvpn/...`
- **Uygulama**: eProxy tun2socks, HTTP Custom
- **Altyapı**: BadVPN tun2socks 1.999.129

### libsocks.so

**BadVPN tun2socks** wrapper'ı (Dynamic Registration kullanıyor):

- **JNI Registration**: `JNI_OnLoad` ile dinamik kayıt
- **Java Class**: `com.github.shadowsocks.bg.Tun2Socks` (veya benzeri)
- **Method Signature**: `(I[Ljava/lang/String;)I` - File Descriptor ve String array alıyor
- **Parametreler**: Command-line argümanları olarak geçiliyor:
  - `--tunfd <fd>`: TUN file descriptor
  - `--netif-ipaddr <ipaddr>`: IPv4 address (örn: "10.0.0.2")
  - `--netif-netmask <mask>`: Subnet mask (örn: "255.255.255.0")
  - `--socks-server-addr <addr>`: SOCKS5 proxy adresi (örn: "127.0.0.1:1080")
  - `--netif-ip6addr <addr>`: IPv6 address (opsiyonel)
  - `--tunmtu <mtu>`: MTU size (örn: 1500)
  - `--dnsgw <addr>`: DNS gateway address
  - `--enable-udprelay`: UDP forwarding (Oyunlar/VoIP için)
  - `--loglevel <level>`: Log seviyesi (error, warning, notice, info, debug)

**Teknik Detaylar:**

- **Versiyon**: BadVPN tun2socks 1.999.130
- **Compiler**: Clang 12.0.8
- **Architecture**: ARM64

## JNI Fonksiyon İmzaları (Tahmini)

### PsiphonTunnel

```c
// libsocks2tun.so
JNIEXPORT jint JNICALL
Java_ca_psiphon_PsiphonTunnel_runTun2Socks(JNIEnv *env, jobject thiz, ...);

JNIEXPORT void JNICALL
Java_ca_psiphon_PsiphonTunnel_terminateTun2Socks(JNIEnv *env, jobject thiz);

JNIEXPORT void JNICALL
Java_ca_psiphon_PsiphonTunnel_disableUdpGwKeepalive(JNIEnv *env, jobject thiz);

JNIEXPORT void JNICALL
Java_ca_psiphon_PsiphonTunnel_enableUdpGwKeepalive(JNIEnv *env, jobject thiz);
```

### Tun2Socks

```c
// libtun2socks.so
JNIEXPORT jint JNICALL
Java_org_bugs4u_proxyserver_core_Tun2Socks_runTun2Socks(JNIEnv *env, jobject thiz, ...);

JNIEXPORT void JNICALL
Java_org_bugs4u_proxyserver_core_Tun2Socks_terminateTun2Socks(JNIEnv *env, jobject thiz);
```

## Kullanım Senaryosu

HTTP Custom muhtemelen şu şekilde kullanıyor:

1. **libsocks.so**: SOCKS5 proxy server (executable olarak çalışıyor)
2. **libsocks2tun.so**: Psiphon Tunnel ile SOCKS5'ten TUN'a dönüştürme
3. **libtun2socks.so**: Bugs4U Tun2Socks ile TUN'dan SOCKS5'e dönüştürme

## HyperXray Entegrasyonu İçin

**Çözüm: Doğrudan JNI Kullanımı ✅**

HTTP Custom kütüphaneleri doğrudan `TProxyService.kt` içinde kullanılıyor. Wrapper yerine native JNI fonksiyonları direkt çağrılıyor.

### Entegrasyon Özellikleri

1. **Otomatik Kütüphane Seçimi**:

   - Önce HTTP Custom kütüphaneleri deneniyor (`libsocks`, `libsocks2tun`, `libtun2socks`)
   - Başarısız olursa `hev-socks5-tunnel` fallback

2. **Psiphon Tunnel Öncelikli**:

   - `libsocks2tun.so` (Psiphon Tunnel) önce deneniyor
   - Başarısız olursa `libtun2socks.so` (Bugs4U) deneniyor
   - Her ikisi de başarısız olursa `hev-socks5-tunnel` kullanılıyor

3. **Doğru Parametreler**:

   - Psiphon Tunnel için: `tunFd`, `mtu`, `router`, `netmask`, `socksServer`, `udpgwAddr`, `udpgwTransparent`
   - Bugs4U için: `tunFd`, `mtu`, `vpnIp`, `vpnNetmask`, `socksServer`, `dnsgwServer`, `udpgwServer`, `transparentDNS` (HTTP Custom raporuna göre)

4. **Helper Fonksiyonlar**:
   - `prefixToNetmask()`: Prefix length'i netmask string'ine çevirir (örn: 24 -> "255.255.255.0")
   - `startTProxyService()`: Otomatik kütüphane seçimi ve parametre hazırlama
   - `stopTProxyService()`: Aktif kütüphaneyi durdurur

### Kullanım

Kütüphaneler `app/src/main/jniLibs/arm64-v8a/` dizininde olduğu sürece otomatik olarak yüklenir ve kullanılır.

## Kaynaklar

- **Psiphon**: https://github.com/Psiphon-Inc/psiphon-android
- **Bugs4U Tun2Socks**: Eski bir Tun2Socks implementasyonu
