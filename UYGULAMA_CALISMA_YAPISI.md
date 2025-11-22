# HyperXray Uygulama Çalışma Yapısı - Kapsamlı Mimari Şema

## Genel Bakış

HyperXray, Xray-core tabanlı bir Android VPN proxy istemcisidir. Uygulama, modern Android mimarisi (Clean Architecture, MVVM) kullanarak geliştirilmiş ve AI/ML destekli optimizasyon özellikleri içermektedir.

---

## 1. Uygulama Başlatma ve Başlangıç Akışı

```
┌─────────────────────────────────────────────────────────────────┐
│                    Android System                                │
│                    (Process Start)                               │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│              HyperXrayApplication.onCreate()                     │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ 1. Global Exception Handler Kurulumu                      │  │
│  │ 2. DI Container (DefaultAppContainer) İnisiyalizasyonu    │  │
│  │ 3. AiLogHelper İnisiyalizasyonu                          │  │
│  │ 4. WorkManager İnisiyalizasyonu                           │  │
│  │ 5. NetworkModule İnisiyalizasyonu                         │  │
│  │    - Conscrypt TLS Hızlandırma                            │  │
│  │    - Cronet HTTP/2, HTTP/3 Desteği                       │  │
│  │ 6. AppInitializer İnisiyalizasyonu                        │  │
│  │    - AI Optimizer Bileşenleri                             │  │
│  │    - ONNX Model Yükleme                                   │  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                    MainActivity.onCreate()                      │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ 1. MainViewModel Oluşturma                                 │  │
│  │ 2. IntentHandler İnisiyalizasyonu                          │  │
│  │ 3. UI İnisiyalizasyonu (Compose)                          │  │
│  │ 4. Navigation Host Kurulumu                                │  │
│  │ 5. Share Intent İşleme (Config Paylaşımı)                 │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. Modüler Mimari Yapısı

```
┌─────────────────────────────────────────────────────────────────┐
│                        app/ (Ana Modül)                        │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ • MainActivity                                           │  │
│  │ • TProxyService (VPN Servisi)                            │  │
│  │ • ViewModels (MVVM)                                      │  │
│  │ • UI Screens (Jetpack Compose)                           │  │
│  │ • Native Libraries (JNI)                                 │  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────────┬────────────────────────────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
        ▼                    ▼                    ▼
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ core-di      │    │ core-network │    │ core-database│
│ (DI Container)│    │ (Network Stack)  │ (Room DB)     │
└──────────────┘    └──────────────┘    └──────────────┘
        │                    │                    │
        ▼                    ▼                    ▼
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│core-telemetry│    │core-design    │    │xray-runtime  │
│ (Telemetry)  │    │ (UI Theme)    │    │ (Xray Service)│
└──────────────┘    └──────────────┘    └──────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────────────┐
│                    Feature Modülleri                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  │
│  │dashboard │  │profiles  │  │vless     │  │reality    │  │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘  │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                │
│  │hysteria2 │  │routing   │  │policy-ai │                │
│  └──────────┘  └──────────┘  └──────────┘                │
│  ┌──────────┐                                              │
│  │telegram  │                                              │
│  └──────────┘                                              │
└──────────────────────────────────────────────────────────────┘
```

---

## 3. VPN Bağlantı Akışı (TProxyService)

```
┌─────────────────────────────────────────────────────────────────┐
│                    Kullanıcı "Bağlan" Butonuna Tıklar         │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                    MainViewModel                                │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ • Config Dosyası Seçimi                                  │  │
│  │ • Config Validasyonu                                     │  │
│  │ • Intent Oluşturma (ACTION_START)                        │  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                    TProxyService.onStartCommand()              │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ ADIM 1: VPN Servisi Başlatma                             │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • VpnService.Builder() ile TUN Interface Oluşturma│  │  │
│  │  │ • ParcelFileDescriptor Alınması                    │  │  │
│  │  │ • IP Adresi Atama (10.0.0.2/24)                    │  │  │
│  │  │ • DNS Sunucu Atama (127.0.0.1:5353)                │  │  │
│  │  │ • Route Ekleme (0.0.0.0/0)                         │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ADIM 2: SystemDnsCacheServer Başlatma                   │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • DNS Cache Server (Port 5353)                     │  │  │
│  │  │ • DoH (DNS over HTTPS) Fallback                    │  │  │
│  │  │ • DNS Cache Manager İnisiyalizasyonu               │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ADIM 3: Xray-core Process Başlatma                      │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • XrayRuntimeService.startXray()                     │  │  │
│  │  │ • ProcessBuilder ile libxray.so Çalıştırma          │  │  │
│  │  │ • Config Dosyası stdin'e Yazma                      │  │  │
│  │  │ • Process Output Okuma (Log Streaming)              │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ADIM 4: SOCKS5 Tunnel Başlatma                          │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • Native Library Yükleme (hev-socks5-tunnel)      │  │  │
│  │  │   veya HTTP Custom Libraries (libsocks2tun.so)    │  │  │
│  │  │ • TUN → SOCKS5 Bridge Kurulumu                     │  │  │
│  │  │ • SOCKS5 Readiness Check                           │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ADIM 5: AI Optimizer Başlatma                          │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • TProxyAiOptimizer İnisiyalizasyonu              │  │  │
│  │  │ • XrayCoreAiOptimizer İnisiyalizasyonu              │  │  │
│  │  │ • CoreStatsClient Bağlantısı (gRPC)                 │  │  │
│  │  │ • Optimizasyon Döngüsü Başlatma                    │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ADIM 6: Log Streaming Başlatma                          │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • Process Output Okuma (stdout/stderr)             │  │  │
│  │  │ • Log Parsing (DNS, SNI, Connection)               │  │  │
│  │  │ • Broadcast Intent (ACTION_LOG_UPDATE)            │  │  │
│  │  │ • DNS Cache Entegrasyonu                           │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. Xray-core Process Yönetimi

```
┌─────────────────────────────────────────────────────────────────┐
│                    XrayRuntimeService                          │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ Process Başlatma:                                         │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ ProcessBuilder("/system/bin/linker64")            │  │  │
│  │  │   .command("libxray.so")                           │  │  │
│  │  │   .start()                                          │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ Config Yazma:                                            │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • Config JSON Hazırlama                           │  │  │
│  │  │ • Config Optimizasyonu (DNS, Policy, Routing)      │  │  │
│  │  │ • Config stdin'e Yazma                             │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ Log Okuma:                                               │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • BufferedReader(process.inputStream)             │  │  │
│  │  │ • Log Line Parsing                                 │  │  │
│  │  │ • DNS Query Extraction                             │  │  │
│  │  │ • SNI Extraction                                   │  │  │
│  │  │ • Connection Status Tracking                       │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ gRPC İletişim:                                           │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • CoreStatsClient (127.0.0.1:API_PORT)            │  │  │
│  │  │ • Stats API Query (GetStats)                       │  │  │
│  │  │ • Hot Reload (UpdateConfig)                        │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 5. Native Kod Entegrasyonu

```
┌─────────────────────────────────────────────────────────────────┐
│                    Native Libraries (JNI)                      │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ 1. hev-socks5-tunnel (TUN ↔ SOCKS5 Bridge)              │  │
│  │    ┌──────────────────────────────────────────────────┐  │  │
│  │    │ • TProxyStartService(tunFd, mtu, socksServer)   │  │  │
│  │    │ • TProxyStopService()                             │  │  │
│  │    │ • TProxyGetStats()                                │  │  │
│  │    └──────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ 2. HTTP Custom Libraries (Alternatif)                   │  │
│  │    ┌──────────────────────────────────────────────────┐  │  │
│  │    │ • libsocks.so (SOCKS5 Proxy)                      │  │  │
│  │    │ • libsocks2tun.so (Psiphon Tunnel)                │  │  │
│  │    │ • libtun2socks.so (Bugs4U Tun2Socks)              │  │  │
│  │    └──────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ 3. libxray.so (Xray-core Binary)                        │  │
│  │    ┌──────────────────────────────────────────────────┐  │  │
│  │    │ • Process olarak çalışır (JNI değil)             │  │  │
│  │    │ • libxray-wrapper.so ile başlatılır              │  │  │
│  │    │ • stdin/stdout/stderr ile iletişim               │  │  │
│  │    └──────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 6. DNS Yönetimi ve Cache Sistemi

```
┌─────────────────────────────────────────────────────────────────┐
│                    DNS Çözümleme Akışı                         │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ 1. DNS Query Kaynağı                                      │ │
│  │    ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │ │
│  │    │Xray Log DNS │  │Xray Sniffing │  │System DNS    │ │ │
│  │    │Query Parse  │  │Domain Extract│  │Request       │ │ │
│  │    └──────┬───────┘  └──────┬───────┘  └──────┬───────┘ │ │
│  │           │                │                  │         │ │
│  │           └────────────────┼──────────────────┘         │ │
│  │                            ▼                             │ │
│  │                 ┌─────────────────────┐                 │ │
│  │                 │  DnsCacheManager    │                 │ │
│  │                 │  (Cache Check)      │                 │ │
│  │                 └──────────┬──────────┘                 │ │
│  │                            │                             │ │
│  │                    ┌──────┴──────┐                      │ │
│  │                    │             │                       │ │
│  │              Cache Hit      Cache Miss                 │ │
│  │                    │             │                       │ │
│  │                    ▼             ▼                       │ │
│  │            ┌───────────┐  ┌──────────────────────┐   │ │
│  │            │Return      │  │SystemDnsCacheServer    │   │ │
│  │            │Cached IP   │  │(Port 5353)            │   │ │
│  │            └───────────┘  └──────────┬─────────────┘   │ │
│  │                                     │                  │ │
│  │                                     ▼                  │ │
│  │                            ┌──────────────────┐        │ │
│  │                            │ DNS Resolution  │        │ │
│  │                            │ 1. System DNS   │        │ │
│  │                            │ 2. DoH Fallback │        │ │
│  │                            │    (Cloudflare, │        │ │
│  │                            │     Google)     │        │ │
│  │                            └────────┬─────────┘        │ │
│  │                                     │                  │ │
│  │                                     ▼                  │ │
│  │                            ┌──────────────────┐        │ │
│  │                            │Cache'a Kaydet    │        │ │
│  │                            │(DnsCacheManager) │        │ │
│  │                            └──────────────────┘        │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ SystemDnsCacheServer Özellikleri:                       │ │
│  │ • Port 5353'te DNS Server                                │ │
│  │ • DoH (DNS over HTTPS) Fallback                          │ │
│  │ • Retry Mekanizması                                      │ │
│  │ • Cache Entegrasyonu                                     │ │
│  │ • Root Gerektirmez                                       │ │
│  └──────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

## 7. AI/ML Optimizasyon Sistemi

```
┌─────────────────────────────────────────────────────────────────┐
│                    AI Optimizer Katmanı                        │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ 1. TProxyAiOptimizer                                     │ │
│  │    ┌──────────────────────────────────────────────────┐  │ │
│  │    │ • TProxy Servisi Optimizasyonu                   │  │ │
│  │    │ • Connection Pool Ayarları                      │  │ │
│  │    │ • Timeout Optimizasyonu                         │  │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 2. XrayCoreAiOptimizer                                   │ │
│  │    ┌──────────────────────────────────────────────────┐  │ │
│  │    │ • Xray Config Optimizasyonu                       │  │ │
│  │    │ • CoreStatsClient (gRPC) ile Metrik Toplama     │  │ │
│  │    │ • ONNX Model Inference                           │  │ │
│  │    │ • Config Hot Reload                              │  │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 3. DeepPolicyModel (ONNX)                               │ │
│  │    ┌──────────────────────────────────────────────────┐  │ │
│  │    │ • Model Yükleme (assets/models/*.onnx)          │  │ │
│  │    │ • Context Normalization                          │  │ │
│  │    │ • Inference (OnnxRuntime)                        │  │ │
│  │    │ • Server Selection                               │  │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 4. RealityBandit (Multi-Armed Bandit)                  │ │
│  │    ┌──────────────────────────────────────────────────┐  │ │
│  │    │ • Epsilon-Greedy Algorithm                       │  │ │
│  │    │ • Exploration vs Exploitation                     │  │ │
│  │    │ • Reward Tracking                               │  │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 5. OptimizerOrchestrator                                 │ │
│  │    ┌──────────────────────────────────────────────────┐  │ │
│  │    │ • Bandit ve Deep Model Koordinasyonu             │  │ │
│  │    │ • Policy Generation                              │  │ │
│  │    │ • Feedback Collection                            │  │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 6. TLS SNI Optimizer v5                                 │ │
│  │    ┌──────────────────────────────────────────────────┐  │ │
│  │    │ • TlsSniModel (ONNX)                             │  │ │
│  │    │ • BanditRouter (Routing Decisions)              │  │ │
│  │    │ • FeedbackManager (Metrics)                     │  │ │
│  │    │ • TlsRuntimeWorker (Periodic Optimization)       │  │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  └──────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

## 8. Veri Akışı ve İletişim Mekanizmaları

```
┌─────────────────────────────────────────────────────────────────┐
│                    Veri Akışı Diyagramı                        │
│                                                                 │
│  ┌──────────────┐                                              │
│  │   UI Layer   │                                              │
│  │ (Compose)    │                                              │
│  └──────┬───────┘                                              │
│         │                                                       │
│         │ StateFlow / LiveData                                 │
│         ▼                                                       │
│  ┌──────────────┐                                              │
│  │ ViewModel    │                                              │
│  │  Layer       │                                              │
│  └──────┬───────┘                                              │
│         │                                                       │
│         │ Intent / Broadcast                                   │
│         ▼                                                       │
│  ┌──────────────┐                                              │
│  │ Service      │                                              │
│  │  Layer       │                                              │
│  │(TProxyService)│                                             │
│  └──────┬───────┘                                              │
│         │                                                       │
│         │ stdin/stdout/stderr                                  │
│         ▼                                                       │
│  ┌──────────────┐                                              │
│  │ Xray-core    │                                              │
│  │  Process     │                                              │
│  └──────┬───────┘                                              │
│         │                                                       │
│         │ gRPC (Protobuf)                                      │
│         ▼                                                       │
│  ┌──────────────┐                                              │
│  │ CoreStats    │                                              │
│  │  Client      │                                              │
│  └──────┬───────┘                                              │
│         │                                                       │
│         │ Stats Data                                           │
│         ▼                                                       │
│  ┌──────────────┐                                              │
│  │ AI Optimizer │                                              │
│  │  Components  │                                              │
│  └──────┬───────┘                                              │
│         │                                                       │
│         │ Optimized Config                                     │
│         ▼                                                       │
│  ┌──────────────┐                                              │
│  │ Config Hot   │                                              │
│  │  Reload      │                                              │
│  └──────────────┘                                              │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ Log Akışı:                                                │ │
│  │ Xray Process → TProxyService → Broadcast Intent →        │ │
│  │ LogViewModel → UI (LogScreen)                            │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ DNS Akışı:                                                │ │
│  │ Xray Log → DNS Query Extract → DnsCacheManager →         │ │
│  │ SystemDnsCacheServer → DoH Fallback → Cache Save         │ │
│  └──────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

## 9. Network Stack ve Optimizasyonlar

```
┌─────────────────────────────────────────────────────────────────┐
│                    Network Stack                                │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ 1. HTTP Client Factory                                    │ │
│  │    ┌──────────────────────────────────────────────────┐  │ │
│  │    │ • OkHttp Client (Temel)                         │  │ │
│  │    │ • Conscrypt TLS Provider (Hızlandırma)         │  │ │
│  │    │ • Cronet Engine (HTTP/2, HTTP/3, QUIC)         │  │ │
│  │    │ • Connection Pooling                            │  │ │
│  │    │ • DNS Cache Integration                         │  │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 2. DNS Resolution                                        │ │
│  │    ┌──────────────────────────────────────────────────┐  │ │
│  │    │ • SystemDnsCacheServer (Port 5353)              │  │ │
│  │    │ • DoH (DNS over HTTPS) Fallback                  │  │ │
│  │    │ • Parallel DNS Queries                           │  │ │
│  │    │ • DNS Cache Manager                             │  │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 3. TLS Optimizations                                    │ │
│  │    ┌──────────────────────────────────────────────────┐  │ │
│  │    │ • Conscrypt Provider (Native TLS)               │  │ │
│  │    │ • Session Reuse                                  │  │ │
│  │    │ • ALPN Support                                   │  │ │
│  │    │ • Certificate Pinning                            │  │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 4. Connection Optimizations                             │ │
│  │    ┌──────────────────────────────────────────────────┐  │ │
│  │    │ • TCP Buffer Sizing (128KB)                     │  │ │
│  │    │ • Keep-Alive Configuration                      │  │ │
│  │    │ • Connection Pooling (120 max connections)      │  │ │
│  │    │ • Timeout Optimization                          │  │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  └──────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

## 10. Core Modüller Detayları

```
┌─────────────────────────────────────────────────────────────────┐
│                    Core Modüller                                │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ core-di (Dependency Injection)                           │ │
│  │    • AppContainer Interface                              │ │
│  │    • DefaultAppContainer Implementation                 │ │
│  │    • Module Dependencies                                 │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ core-network (Network Infrastructure)                   │ │
│  │    • HttpClientFactory                                   │ │
│  │    • SystemDnsCacheServer                                │ │
│  │    • DnsCacheManager                                     │ │
│  │    • NetworkModule                                       │ │
│  │    • TLSFeatureEncoder                                   │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ core-database (Database Layer)                           │ │
│  │    • Room Database Setup                                 │ │
│  │    • Entity Definitions                                  │ │
│  │    • DAO Interfaces                                      │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ core-telemetry (Telemetry & Analytics)                  │ │
│  │    • Telemetry Collection                                │ │
│  │    • Metrics Aggregation                                 │ │
│  │    • Performance Tracking                                │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ core-designsystem (UI Components)                        │ │
│  │    • Material3 Theme                                     │ │
│  │    • Design Tokens                                       │ │
│  │    • Reusable Components                                 │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ xray-runtime-service (Xray Service)                      │ │
│  │    • XrayRuntimeService                                  │ │
│  │    • MultiXrayCoreManager                                │ │
│  │    • CoreStatsClient (gRPC)                              │ │
│  │    • Process Lifecycle Management                        │ │
│  └──────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

## 11. Feature Modülleri Detayları

```
┌─────────────────────────────────────────────────────────────────┐
│                    Feature Modülleri                           │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ feature-dashboard                                        │ │
│  │    • Dashboard Screen                                    │ │
│  │    • Connection Status                                   │ │
│  │    • Statistics Display                                   │ │
│  │    • Quick Actions                                       │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ feature-profiles                                          │ │
│  │    • Profile Management                                   │ │
│  │    • Config Import/Export                                │ │
│  │    • IntentHandler (Share Intent)                        │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ feature-vless                                             │ │
│  │    • VLESS Protocol Support                               │ │
│  │    • VLESS Link Parser                                   │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ feature-reality                                           │ │
│  │    • Reality Protocol Support                             │ │
│  │    • Reality Config Generator                            │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ feature-hysteria2                                        │ │
│  │    • Hysteria2 Protocol Support                          │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ feature-routing                                           │ │
│  │    • Routing Rules Management                            │ │
│  │    • Domain/IP Routing                                   │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ feature-policy-ai                                         │ │
│  │    • AI Policy Management                                │ │
│  │    • OnnxRuntimeRoutingEngine                            │ │
│  │    • AiOptimizerInitializer                              │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ feature-telegram                                          │ │
│  │    • Telegram Integration                                │ │
│  │    • Notification Management                            │ │
│  │    • TelegramNotificationManager                         │ │
│  └──────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

## 12. Bağlantı Kesme ve Temizleme Akışı

```
┌─────────────────────────────────────────────────────────────────┐
│                    Bağlantı Kesme Akışı                         │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ 1. Kullanıcı "Bağlantıyı Kes" Butonuna Tıklar            │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 2. MainViewModel                                         │ │
│  │    • Intent Oluşturma (ACTION_STOP)                     │ │
│  │    • TProxyService'e Gönderme                            │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 3. TProxyService.stopService()                           │ │
│  │    ┌──────────────────────────────────────────────────┐  │ │
│  │    │ ADIM 1: SystemDnsCacheServer Durdurma            │  │ │
│  │    │ ADIM 2: AI Optimizer Durdurma                     │  │ │
│  │    │ ADIM 3: Log Streaming Durdurma                    │  │ │
│  │    │ ADIM 4: SOCKS5 Tunnel Durdurma                    │  │ │
│  │    │ ADIM 5: Xray Process Durdurma                     │  │ │
│  │    │ ADIM 6: VPN Interface Kapatma                     │  │ │
│  │    │ ADIM 7: WakeLock Release                          │  │ │
│  │    │ ADIM 8: Service Stop                              │  │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 4. XrayRuntimeService.stopXray()                        │ │
│  │    • Process stdin Kapatma                               │ │
│  │    • Graceful Shutdown Bekleme                           │ │
│  │    • Process Destroy (Gerekirse)                          │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 5. UI Güncelleme                                         │ │
│  │    • Connection State Broadcast                          │ │
│  │    • MainViewModel State Update                          │ │
│  │    • UI Recomposition                                    │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  └──────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

## 13. Hata Yönetimi ve Kurtarma Mekanizmaları

```
┌─────────────────────────────────────────────────────────────────┐
│                    Hata Yönetimi                                │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ 1. Global Exception Handler                               │ │
│  │    • HyperXrayApplication'da Kurulu                      │ │
│  │    • Crash Log Kaydetme                                  │ │
│  │    • Default Handler Çağırma                            │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 2. Process Crash Recovery                                │ │
│  │    • Xray Process Monitoring                             │ │
│  │    • Process Exit Detection                              │ │
│  │    • Automatic Restart (Gerekirse)                        │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 3. DNS Fallback Mechanisms                               │ │
│  │    • System DNS → DoH Fallback                          │ │
│  │    • Retry Mechanism                                    │ │
│  │    • Cache Miss Handling                                │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 4. Network Error Recovery                                │ │
│  │    • Connection Reset Detection                          │ │
│  │    • UDP Error Recovery                                  │ │
│  │    • Service Restart Throttling                          │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 5. AI Model Fallback                                     │ │
│  │    • Model Load Failure → Fallback Handler               │ │
│  │    • Inference Error → Fallback Selection                │ │
│  │    • Model Signature Verification                        │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  └──────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

## 14. Performans Optimizasyonları

```
┌─────────────────────────────────────────────────────────────────┐
│                    Performans Optimizasyonları                 │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ 1. Memory Optimization                                   │ │
│  │    • Object Pooling                                      │ │
│  │    • Lazy Initialization                                 │ │
│  │    • Weak References                                     │ │
│  │    • Log Buffer Management                               │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 2. CPU Optimization                                      │ │
│  │    • Background Thread Usage                             │ │
│  │    • Coroutine Scope Management                          │ │
│  │    • ONNX Inference Optimization                         │ │
│  │    • Zero-Copy Tensor Operations                         │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 3. Network Optimization                                  │ │
│  │    • DNS Caching                                         │ │
│  │    • Connection Pooling                                  │ │
│  │    • Request Deduplication                              │ │
│  │    • Compression (gzip, brotli)                          │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 4. UI Performance                                        │ │
│  │    • Recomposition Optimization                         │ │
│  │    • LazyColumn/LazyRow Usage                           │ │
│  │    • State Hoisting                                      │ │
│  │    • Remember/DerivedStateOf                            │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 5. Battery Optimization                                 │ │
│  │    • WorkManager Usage                                  │ │
│  │    • Background Task Optimization                        │ │
│  │    • Wake Lock Management                               │ │
│  │    • Network Request Batching                           │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  └──────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

## 15. Güvenlik Özellikleri

```
┌─────────────────────────────────────────────────────────────────┐
│                    Güvenlik Özellikleri                        │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ 1. Config Security                                       │ │
│  │    • Config stdin'e Yazma (File System'de Değil)         │ │
│  │    • Path Validation                                     │ │
│  │    • Config Encryption (Gerekirse)                       │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 2. Process Isolation                                     │ │
│  │    • Xray-core Ayrı Process'te Çalışır                   │ │
│  │    • Memory Space Isolation                              │ │
│  │    • Process Crash Isolation                             │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 3. Network Security                                      │ │
│  │    • TLS 1.3 Enforcement                                │ │
│  │    • Certificate Pinning                                 │ │
│  │    • SSL/TLS Configuration                               │ │
│  │    • Network Security Config                            │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 4. Data Security                                         │ │
│  │    • Encrypted Storage (Keystore)                       │ │
│  │    • Secure Key Management                               │ │
│  │    • PII Protection                                      │ │
│  │    • SNI Redaction in Logs                               │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  │                                                           │ │
│  │ 5. Code Security                                         │ │
│  │    • ProGuard/R8 Obfuscation                            │ │
│  │    • Native Library Protection                           │ │
│  │    • API Key Management                                  │ │
│  │    └──────────────────────────────────────────────────┘  │ │
│  └──────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

## 16. Özet: Tüm Sistemin Entegrasyonu

```
┌─────────────────────────────────────────────────────────────────┐
│                    Sistem Entegrasyonu                         │
│                                                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │   Android    │  │   Native    │  │   Network    │          │
│  │   App Layer  │  │   Layer     │  │   Stack     │          │
│  │              │  │              │  │              │          │
│  │ • UI (Compose)│  │ • Xray-core │  │ • DNS Cache │          │
│  │ • ViewModels │  │ • SOCKS5    │  │ • HTTP/2,3   │          │
│  │ • Services   │  │ • TUN Bridge│  │ • TLS Accel. │          │
│  └──────┬───────┘  └──────┬──────┘  └──────┬───────┘          │
│         │                 │                 │                  │
│         └─────────────────┼─────────────────┘                  │
│                           │                                    │
│                           ▼                                    │
│                  ┌──────────────┐                             │
│                  │  AI/ML Layer │                             │
│                  │              │                             │
│                  │ • ONNX Models│                             │
│                  │ • Bandit Alg.│                             │
│                  │ • Optimizers │                             │
│                  └──────┬───────┘                             │
│                         │                                      │
│                         ▼                                      │
│                  ┌──────────────┐                             │
│                  │  Core Layer  │                             │
│                  │              │                             │
│                  │ • DI         │                             │
│                  │ • Network    │                             │
│                  │ • Database  │                             │
│                  │ • Telemetry │                             │
│                  └──────────────┘                             │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ Veri Akışı:                                              │ │
│  │ UI ↔ ViewModel ↔ Service ↔ Xray Process ↔ Network      │ │
│  │                                                          │ │
│  │ Optimizasyon Akışı:                                      │ │
│  │ Metrics → AI Models → Optimized Config → Hot Reload     │ │
│  │                                                          │ │
│  │ DNS Akışı:                                              │ │
│  │ Query → Cache Check → SystemDnsCacheServer → DoH       │ │
│  └──────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

## Sonuç

Bu şema, HyperXray uygulamasının tüm çalışma yapısını ve bileşenler arasındaki ilişkileri göstermektedir. Uygulama:

1. **Modüler mimari** ile geliştirilmiştir (core modüller + feature modüller)
2. **MVVM pattern** kullanarak UI ve business logic'i ayırmıştır
3. **Native kod entegrasyonu** ile yüksek performans sağlamaktadır
4. **AI/ML optimizasyonları** ile akıllı bağlantı yönetimi yapmaktadır
5. **DNS caching** ve **network optimizasyonları** ile hızlı ve verimli çalışmaktadır
6. **Güvenlik özellikleri** ile kullanıcı verilerini korumaktadır

Tüm bu bileşenler birbirleriyle entegre çalışarak, kullanıcıya güvenli, hızlı ve optimize edilmiş bir VPN deneyimi sunmaktadır.



