# HTTP Custom v2ray Bağlantı Kurulum Analizi

## 🔍 Bulgular

### 1. Uygulama Bilgileri

- **Paket Adı**: `xyz.easypro.httpcustom`
- **Process ID**: 8084 (çalışıyor)
- **Servis**: `team.dev.epro.apkcustom.sockets.v2ray.V2RayVpnService`
- **Native Library**: `libv2ray.so` (arm64)

### 2. HTTP Custom v2ray Bağlantı Mekanizması

#### A. Native Library Yükleme

Loglardan görüldüğü üzere:

```
Load /data/app/.../lib/arm64/libv2ray.so using class loader
```

HTTP Custom, v2ray core'u **native library** (`libv2ray.so`) olarak yükler ve JNI üzerinden çağırır.

#### B. VPN Servis Başlatma

```
Intent { act=start cmp=xyz.easypro.httpcustom/team.dev.epro.apkcustom.sockets.v2ray.V2RayVpnService }
```

1. **V2RayVpnService** başlatılır
2. VPN izni alınır
3. Native `libv2ray.so` yüklenir
4. v2ray core başlatılır

#### C. SOCKS5 Proxy Oluşturma

Port 10808'de SOCKS5 proxy dinleniyor:

```
tcp6  ... ::ffff:127.0.0.1:10808  CLOSE_WAIT
```

### 3. Bağlantı Akışı

```
[HTTP Custom App]
    ↓
[V2RayVpnService.start()]
    ↓
[Native libv2ray.so yükle] → [JNI çağrıları]
    ↓
[v2ray Core başlat]
    ↓
[SOCKS5 Proxy: 127.0.0.1:10808]
    ↓
[VPN TUN interface oluştur]
    ↓
[Trafik → TUN → SOCKS5 → v2ray Core → Sunucu]
```

### 4. HyperXray ile Farklar

| Özellik            | HTTP Custom       | HyperXray            |
| ------------------ | ----------------- | -------------------- |
| **v2ray Başlatma** | JNI (libv2ray.so) | Process (libxray.so) |
| **İletişim**       | JNI çağrıları     | IPC (stdin/stdout)   |
| **İzolasyon**      | Aynı process      | Ayrı child process   |
| **Stabilite**      | JNI riskleri      | Daha izole           |

### 5. ADB ile İzleme Komutları

```bash
# 1. HTTP Custom process'ini kontrol et
adb shell "ps -A | grep httpcustom"

# 2. Native library'yi kontrol et
adb shell "ls -la /data/app/*/xyz.easypro.httpcustom*/lib/arm64/"

# 3. SOCKS5 portunu kontrol et
adb shell "netstat -tuln | grep 10808"

# 4. VPN servis durumunu kontrol et
adb shell "dumpsys activity services xyz.easypro.httpcustom"

# 5. Logları izle
adb logcat | grep -iE "V2RayVpnService|libv2ray|socks5"
```

### 6. Önemli Noktalar

1. **JNI Kullanımı**: HTTP Custom, v2ray core'u JNI üzerinden çağırır
2. **Native Library**: `libv2ray.so` uygulama içinde gömülü
3. **VPN Servis**: Android VPN API kullanarak TUN interface oluşturur
4. **SOCKS5**: Yerel SOCKS5 proxy (127.0.0.1:10808) üzerinden trafik yönlendirir

### 7. Bağlantı Testi

```bash
# SOCKS5 bağlantısını test et
adb shell "curl --socks5 127.0.0.1:10808 http://www.google.com"

# Port dinleme durumunu kontrol et
adb shell "netstat -tuln | grep 10808"
```

### 8. Log Analizi

HTTP Custom'un bağlantı loglarını görmek için:

```bash
# Tüm v2ray ilgili loglar
adb logcat -d | grep -iE "httpcustom|V2RayVpnService|libv2ray"

# Canlı log izleme
adb logcat -c
adb logcat | grep -iE "V2RayVpnService|socks5|connect"
```

### 9. Kaynak Kod Yapısı (Tahmini)

HTTP Custom'un kaynak kodunda muhtemelen:

```
xyz.easypro.httpcustom/
├── team.dev.epro.apkcustom.sockets.v2ray/
│   └── V2RayVpnService.java/kt
├── native/
│   └── libv2ray.so (arm64, arm, x86, x86_64)
└── JNI bağlantıları
    └── v2ray core çağrıları
```

### 10. JNI vs Process-Based Yaklaşım Karşılaştırması

#### JNI Yaklaşımı (HTTP Custom)

**✅ Avantajlar:**

- **Daha Hızlı**: Process içi çağrılar, IPC overhead yok
- **Daha Az Kaynak**: Tek process, daha az memory kullanımı
- **Daha Basit**: Doğrudan native fonksiyon çağrıları
- **Daha Az Latency**: IPC gecikmesi yok

**❌ Dezavantajlar:**

- **Stabilite Riski**: Native kod hatası tüm uygulamayı çökertir
- **Memory Yönetimi**: Native memory leak'ler uygulamayı etkiler
- **Debugging Zor**: JNI crash'leri debug etmek zor
- **İzolasyon Yok**: Core hataları UI'ı etkiler
- **Signal Handling**: Native signal'ler (SIGSEGV, SIGABRT) uygulamayı kapatır

#### Process-Based Yaklaşım (HyperXray)

**✅ Avantajlar:**

- **Daha Stabil**: Core crash'i sadece child process'i etkiler
- **İzolasyon**: Core ve app ayrı process'ler, birbirini etkilemez
- **Kolay Recovery**: Core crash'te app çalışmaya devam eder, yeniden başlatabilir
- **Memory İzolasyonu**: Core memory leak'leri app'i etkilemez
- **Debugging Kolay**: Process logları ayrı, daha kolay debug
- **Signal İzolasyonu**: Native signal'ler sadece child process'i etkiler

**❌ Dezavantajlar:**

- **IPC Overhead**: Process arası iletişim biraz daha yavaş
- **Daha Fazla Memory**: İki process = daha fazla memory
- **Daha Karmaşık**: Process yönetimi ve IPC gerektirir

### 11. Sonuç: Hangisi Daha İyi?

**Genel Kullanım İçin: Process-Based (HyperXray) Daha İyi**

**Neden?**

1. **Stabilite Öncelikli**: VPN uygulamaları için stabilite kritik
2. **Kullanıcı Deneyimi**: Core crash'te uygulama çalışmaya devam eder
3. **Güvenilirlik**: Production ortamında daha güvenilir
4. **Maintenance**: Hata ayıklama ve bakım daha kolay

**JNI Ne Zaman Tercih Edilmeli?**

- Performans kritik olduğunda (mikrosaniye seviyesinde)
- Memory çok kısıtlı olduğunda
- Basit, küçük native kodlar için
- **Ancak**: VPN/proxy gibi kritik uygulamalarda riskli

**HyperXray'un Tercih Sebebi:**

> "By running Xray-core as an independent child process, HyperXray avoids JNI complexities, potential memory issues, and app crashes linked to core library failures. This isolation significantly improves reliability."

### 12. Performans Karşılaştırması

| Metrik                      | JNI               | Process-Based             | Fark              |
| --------------------------- | ----------------- | ------------------------- | ----------------- |
| **Fonksiyon Çağrı Latency** | ~1-5 μs           | ~50-200 μs                | 10-40x daha yavaş |
| **Memory Overhead**         | ~10-50 MB         | ~20-100 MB                | 2x daha fazla     |
| **Crash Recovery**          | ❌ Uygulama çöker | ✅ Yeniden başlatılabilir | Çok önemli        |
| **Stabilite**               | ⚠️ Riskli         | ✅ İzole                  | Kritik fark       |
| **Debug Kolaylığı**         | ⚠️ Zor            | ✅ Kolay                  | Önemli            |

**Not**: Latency farkı genelde fark edilmez çünkü:

- VPN trafiği zaten network-bound (milisaniye seviyesinde)
- IPC overhead (~0.1ms) network latency (~50-200ms) yanında önemsiz
- Kullanıcı deneyiminde fark yok
