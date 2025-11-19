# HTTP Custom Uygulaması - SOCKS Proxy Kullanımı Detaylı Raporu

## 1. Genel Bakış

HTTP Custom uygulaması, SOCKS5 proxy protokolünü kullanarak VPN servisi sağlar. Uygulama, TUN/TAP interface'i üzerinden gelen trafiği SOCKS proxy üzerinden yönlendirir. Bu, V2Ray veya diğer protokollere alternatif olarak kullanılabilir.

## 2. Mimari Yapı

### 2.1 Ana Bileşenler

SOCKS proxy implementasyonu aşağıdaki bileşenlerden oluşur:

1. **SocksVPNService**: Android VPN servis implementasyonu
2. **Tun2Socks**: TUN interface'i SOCKS proxy'ye bağlayan native kütüphane
3. **SOCKS Server (w8.b)**: Yerel SOCKS5 sunucu implementasyonu
4. **SOCKS Client Handler (w8.a)**: SOCKS5 bağlantı işleyicisi
5. **Socket Protector (v8.d)**: VPN socket koruması

### 2.2 Native Kütüphaneler

```
libsocks.so          # SOCKS protokolü implementasyonu
libtun2socks.so      # TUN to SOCKS bridge
libsocks2tun.so      # SOCKS to TUN bridge
```

## 3. Tun2Socks Native Bridge (`Tun2Socks.java`)

### 3.1 Sınıf Yapısı

```java
package org.bugs4u.proxyserver.core;

public class Tun2Socks {
    private static String mSocksServerAddress;        // SOCKS5 server address
    private static String mDnsgwServerAddress;        // DNS gateway
    private static String mUdpgwServerAddress;        // UDP gateway
    private static boolean mUdpgwTransparentDNS;      // Transparent DNS
    private static ParcelFileDescriptor mVpnInterfaceFileDescriptor; // TUN FD
    private static int mVpnInterfaceMTU;              // MTU
    private static String mVpnIpAddress;              // VPN IP
    private static String mVpnNetMask;                // VPN netmask

    public interface IProtectSocket {
        boolean doVpnProtect(Socket socket);
    }
}
```

### 3.2 Tun2Socks Başlatma

```java
public static void Start(
    ParcelFileDescriptor vpnInterface,  // TUN interface FD
    int mtu,                            // MTU size
    String vpnIp,                       // VPN IP (e.g., "10.0.0.2")
    String vpnNetmask,                  // VPN netmask (e.g., "255.255.255.0")
    String socksServer,                 // SOCKS5 server (e.g., "127.0.0.1:1082")
    String dnsgwServer,                 // DNS gateway
    String udpgwServer,                 // UDP gateway
    boolean transparentDNS              // Transparent DNS flag
) {
    // 1. Native library yükle
    if (!mLibLoaded) {
        System.loadLibrary("tun2socks");
        mLibLoaded = true;
    }

    // 2. Parametreleri sakla
    mVpnInterfaceFileDescriptor = vpnInterface;
    mVpnInterfaceMTU = mtu;
    mVpnIpAddress = vpnIp;
    mVpnNetMask = vpnNetmask;
    mSocksServerAddress = socksServer;
    mDnsgwServerAddress = dnsgwServer;
    mUdpgwServerAddress = udpgwServer;
    mUdpgwTransparentDNS = transparentDNS;

    // 3. Native fonksiyonu çağır
    if (vpnInterface != null) {
        runTun2Socks(
            vpnInterface.detachFd(),  // TUN file descriptor
            mtu,                       // MTU
            vpnIp,                     // VPN IP
            vpnNetmask,                // Netmask
            socksServer,               // SOCKS5 server
            dnsgwServer,               // DNS gateway
            udpgwServer,               // UDP gateway
            transparentDNS ? 1 : 0     // Transparent DNS
        );
    }
}

// Native C/C++ implementasyonu (libtun2socks.so)
private static native int runTun2Socks(
    int tunFd,      // TUN file descriptor
    int mtu,        // MTU size
    String vpnIp,   // VPN IP address
    String netmask, // VPN netmask
    String socks,   // SOCKS5 server address:port
    String dnsgw,   // DNS gateway
    String udpgw,   // UDP gateway
    int transparentDNS // Transparent DNS flag
);
```

### 3.3 Tun2Socks Durdurma

```java
public static void Stop() {
    // 1. Native library yükle (eğer yüklenmemişse)
    if (!mLibLoaded) {
        System.loadLibrary("tun2socks");
        mLibLoaded = true;
    }

    // 2. Native stop fonksiyonunu çağır
    terminateTun2Socks();
}

// Native C/C++ implementasyonu
private static native int terminateTun2Socks();
```

## 4. HyperXray Entegrasyonu

### 4.1 Güncellenmiş Parametreler

Rapora göre, `Bugs4U Tun2Socks` için gerekli parametreler:

```kotlin
callBugs4URunTun2Socks(
    tunFd: Int,              // TUN file descriptor
    mtu: Int,                // MTU size
    vpnIp: String,           // VPN IP (e.g., "10.0.0.2")
    vpnNetmask: String,     // VPN netmask (e.g., "255.255.255.0")
    socksServer: String,     // SOCKS5 server (e.g., "127.0.0.1:1082")
    dnsgwServer: String,     // DNS gateway
    udpgwServer: String,     // UDP gateway
    transparentDNS: Boolean  // Transparent DNS flag
): Int
```

### 4.2 Parametre Hazırlama

```kotlin
// Prepare parameters for Bugs4U Tun2Socks
val mtu = prefs.tunnelMtu
val vpnIp = prefs.tunnelIpv4Address
val vpnNetmask = prefixToNetmask(prefs.tunnelIpv4Prefix)
val socksServer = "${prefs.socksAddress}:${prefs.socksPort}"
val dnsgwServer = prefs.dnsIpv4.takeIf { it.isNotEmpty() } ?: "8.8.8.8"
val udpgwServer = "" // UDP gateway address (optional, empty for now)
val transparentDNS = false // Transparent DNS mode (optional)
```

### 4.3 Native JNI Fonksiyon İmzası

```kotlin
@JvmStatic
@Suppress("FunctionName")
private external fun nativeCallBugs4URunTun2Socks(
    tunFd: Int,
    mtu: Int,
    vpnIp: String,
    vpnNetmask: String,
    socksServer: String,
    dnsgwServer: String,
    udpgwServer: String,
    transparentDNS: Int  // 0 or 1
): Int
```

## 5. Çalışma Akışı

### 5.1 Bağlantı Akışı

```
1. Uygulama Başlatma
   ↓
2. TProxyService.startTProxyService()
   ↓
3. VPN Interface Oluştur (VpnService.Builder)
   - IP: 10.0.0.2
   - Netmask: 255.255.255.0
   - MTU: 1500
   ↓
4. Tun2Socks Başlat
   - TUN → SOCKS5 (127.0.0.1:1082)
   - Native library: libtun2socks.so
   ↓
5. Trafik İşleme
   TUN Interface
   ↓
   Tun2Socks (Native C++)
   ↓
   Local SOCKS5 Server (127.0.0.1:1082)
   ↓
   Remote SOCKS5 Server
   ↓
   Target Server
```

## 6. Konfigürasyon Formatı

### 6.1 SOCKS URL Formatı

```
socks://[username:password@]server:port
```

Örnekler:

```
socks://127.0.0.1:1080
socks://user:pass@example.com:1080
socks://192.168.1.1:8080
```

## 7. Port ve Adres Kullanımı

### 7.1 Varsayılan Portlar

- **Local SOCKS5 Server**: 1082
- **Fallback Port**: 8888
- **Standard SOCKS5**: 1080

### 7.2 Adresler

- **Local SOCKS5**: 127.0.0.1:1082
- **VPN Interface**: 10.0.0.2/24
- **Remote SOCKS5**: Konfigürasyondan alınır

## 8. Özellikler

### 8.1 Desteklenen Özellikler

1. **SOCKS5 Protokolü**: Tam SOCKS5 desteği
2. **Authentication**: Username/Password authentication
3. **IPv4/IPv6**: IPv4 ve IPv6 desteği
4. **Domain Names**: Domain name resolution
5. **UDP Support**: UDP forwarding (udpgw ile)
6. **Transparent DNS**: DNS forwarding

### 8.2 Limitasyonlar

1. **SOCKS4**: Desteklenmiyor (sadece SOCKS5)
2. **UDP**: Native UDP forwarding gerektirir (udpgw)
3. **MTU**: Sabit MTU (1500 byte)

## 9. Native Kütüphane Detayları

### 9.1 libtun2socks.so

**Görevler:**

- TUN interface'den paket okuma
- SOCKS5 protokolüne dönüştürme
- SOCKS5 sunucuya bağlantı
- Paket forwarding

**Parametreler:**

```
--netif-ipaddr: VPN IP address
--netif-netmask: VPN netmask
--socks-server-addr: SOCKS5 server address:port
--tunfd: TUN file descriptor
--loglevel: Log level
--sockbuf: Socket buffer size
--mtu: MTU size
```

### 9.2 libsocks.so

**Görevler:**

- SOCKS5 protokolü implementasyonu
- Handshake işleme
- Authentication
- Data forwarding

### 9.3 libsocks2tun.so

**Görevler:**

- SOCKS5'ten TUN'a dönüştürme
- Reverse proxy desteği

## 10. Sonuç

HTTP Custom uygulaması, SOCKS5 proxy protokolünü kullanarak güçlü bir VPN servisi sunar. Tun2Socks native kütüphanesi ile TUN interface trafiğini SOCKS5 proxy üzerinden yönlendirir. Uygulama, tam SOCKS5 desteği, authentication, ve transparent DNS gibi özellikler sağlar.

**Ana Özellikler:**

- ✅ SOCKS5 protokolü desteği
- ✅ Username/Password authentication
- ✅ TUN interface entegrasyonu
- ✅ Native performans (libtun2socks.so)
- ✅ Transparent DNS
- ✅ UDP forwarding

**Kullanım Senaryoları:**

- V2Ray'e alternatif olarak SOCKS proxy
- HTTP proxy ile kombinasyon
- Yerel SOCKS5 sunucu (port 1082)
- Remote SOCKS5 sunucu bağlantısı

---

**Rapor Tarihi**: 2024-11-19  
**Uygulama**: HTTP Custom (xyz.easypro.httpcustom)  
**Analiz Yöntemi**: APK decompilation (JADX)  
**Konu**: SOCKS Proxy Implementasyonu


