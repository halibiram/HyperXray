# VPN Bağlantı Aşamaları - Detaylı Şema

## Genel Bakış

Bu dokümantasyon, HyperXray uygulamasının VPN bağlantı sürecinin tüm aşamalarını detaylı olarak açıklamaktadır. Bağlantı süreci, kullanıcının "Bağlan" butonuna tıklamasından başlayarak, tam fonksiyonel bir VPN bağlantısının kurulmasına kadar tüm adımları içermektedir.

---

## 1. Başlangıç: Kullanıcı Aksiyonu

```
┌─────────────────────────────────────────────────────────────────┐
│                    Kullanıcı "Bağlan" Butonuna Tıklar           │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                    MainViewModel.connect()                       │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ 1. Config Dosyası Kontrolü                                │  │
│  │    • selectedConfigPath kontrolü                           │  │
│  │    • Config dosyası varlık kontrolü                       │  │
│  │                                                           │  │
│  │ 2. Intent Oluşturma                                       │  │
│  │    • ACTION: TProxyService.ACTION_START                   │  │
│  │    • EXTRA: configPath                                    │  │
│  │                                                           │  │
│  │ 3. Service Başlatma                                       │  │
│  │    • startForegroundService(intent)                       │  │
│  │    • Connection State: CONNECTING                         │  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│              TProxyService.onStartCommand()                     │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ • Intent ACTION kontrolü                                 │  │
│  │ • Log dosyası temizleme (opsiyonel)                       │  │
│  │ • startXray() çağrısı                                    │  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────────┬────────────────────────────────────┘
```

---

## 2. ADIM 1: VPN Servisi Başlatma (TUN Interface)

```
┌─────────────────────────────────────────────────────────────────┐
│              TProxyService.startService()                      │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                                                           │  │
│  │ ALT ADIM 1.1: SystemDnsCacheServer Başlatma             │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • SystemDnsCacheServer.getInstance()               │  │  │
│  │  │ • systemDnsCacheServer.start()                     │  │  │
│  │  │ • Port 5353'te DNS Server başlatma                 │  │  │
│  │  │ • DoH (DNS over HTTPS) Fallback hazırlığı          │  │  │
│  │  │ • DNS Cache Manager entegrasyonu                    │  │  │
│  │  │                                                      │  │  │
│  │  │ Sonuç: DNS Cache Server hazır                       │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 1.2: VPN Builder Oluşturma                     │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ getVpnBuilder(prefs, systemDnsCacheServer)        │  │  │
│  │  │                                                      │  │  │
│  │  │ • setBlocking(false) - Non-blocking mode          │  │  │
│  │  │ • setMtu(prefs.tunnelMtu) - MTU ayarı             │  │  │
│  │  │                                                      │  │  │
│  │  │ IPv4 Konfigürasyonu:                               │  │  │
│  │  │ • addAddress("10.0.0.2", 24) - VPN IP             │  │  │
│  │  │ • addRoute("0.0.0.0", 0) - Tüm trafik              │  │  │
│  │  │ • addDnsServer("127.0.0.1") - DNS Cache Server    │  │  │
│  │  │                                                      │  │  │
│  │  │ IPv6 Konfigürasyonu (opsiyonel):                   │  │  │
│  │  │ • addAddress(ipv6Address, prefix)                   │  │  │
│  │  │ • addRoute("::", 0)                                 │  │  │
│  │  │ • addDnsServer("::1")                               │  │  │
│  │  │                                                      │  │  │
│  │  │ LAN Bypass (opsiyonel):                             │  │  │
│  │  │ • addRoute("10.0.0.0", 8)                           │  │  │
│  │  │ • addRoute("172.16.0.0", 12)                        │  │  │
│  │  │ • addRoute("192.168.0.0", 16)                       │  │  │
│  │  │                                                      │  │  │
│  │  │ HTTP Proxy (opsiyonel):                             │  │  │
│  │  │ • setHttpProxy(ProxyInfo)                           │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 1.3: TUN Interface Oluşturma                   │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ builder.establish()                                │  │  │
│  │  │                                                      │  │  │
│  │  │ • Android VpnService.establish() çağrısı          │  │  │
│  │  │ • TUN interface oluşturma                          │  │  │
│  │  │ • ParcelFileDescriptor döndürme                    │  │  │
│  │  │ • File descriptor (fd) alma                       │  │  │
│  │  │                                                      │  │  │
│  │  │ Hata Durumu:                                        │  │  │
│  │  │ • null dönerse → stopXray() → Hata Broadcast       │  │  │
│  │  │ • Telegram notification gönderimi                  │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 1.4: TProxy Config Dosyası Oluşturma           │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ File(cacheDir, "tproxy.conf")                      │  │  │
│  │  │                                                      │  │  │
│  │  │ • getTproxyConf(prefs) ile config içeriği         │  │  │
│  │  │ • FileOutputStream ile dosyaya yazma               │  │  │
│  │  │ • Config içeriği:                                  │  │  │
│  │  │   - TUN file descriptor                            │  │  │
│  │  │   - MTU değeri                                      │  │  │
│  │  │   - SOCKS5 server adresi (127.0.0.1:port)         │  │  │
│  │  │   - UDP gateway adresi                             │  │  │
│  │  │   - VPN IP ve netmask                              │  │  │
│  │  │                                                      │  │  │
│  │  │ Hata Durumu:                                        │  │  │
│  │  │ • IOException → stopXray() → Hata Broadcast        │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 1.5: Native TProxy Service Başlatma           │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ TProxyStartService(tproxyFile.absolutePath, fd)     │  │  │
│  │  │                                                      │  │  │
│  │  │ Native Library Seçimi:                              │  │  │
│  │  │ 1. HTTP Custom Libraries (öncelikli):              │  │  │
│  │  │    • libsocks2tun.so (Psiphon Tunnel)              │  │  │
│  │  │    • libtun2socks.so (Bugs4U Tun2Socks)            │  │  │
│  │  │                                                      │  │  │
│  │  │ 2. hev-socks5-tunnel (fallback):                    │  │  │
│  │  │    • TProxyStartService() JNI çağrısı              │  │  │
│  │  │                                                      │  │  │
│  │  │ İşlem:                                               │  │  │
│  │  │ • TUN interface'i SOCKS5 proxy'ye bağlama          │  │  │
│  │  │ • UDP gateway kurulumu                             │  │  │
│  │  │ • TCP/UDP trafik yönlendirme                       │  │  │
│  │  │                                                      │  │  │
│  │  │ Sonuç: TUN ↔ SOCKS5 bridge kuruldu                 │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 1.6: AI Optimizer Hazırlığı                    │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ tproxyAiOptimizer başlatma                          │  │  │
│  │  │                                                      │  │  │
│  │  │ • Configuration callback kurulumu                    │  │  │
│  │  │ • Optimizasyon döngüsü hazırlığı                   │  │  │
│  │  │ • (Gerçek optimizasyon Xray başladıktan sonra)     │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 1.7: Başarı Broadcast                          │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ Intent(ACTION_START) broadcast                      │  │  │
│  │  │ • MainViewModel state güncelleme                    │  │  │
│  │  │ • UI güncelleme (CONNECTING → CONNECTED)           │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
                    VPN Servisi Hazır ✓
```

---

## 3. ADIM 2: Xray-core Process Başlatma

```
┌─────────────────────────────────────────────────────────────────┐
│              TProxyService.runXrayProcess()                     │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                                                           │  │
│  │ ALT ADIM 2.1: Config Validasyonu                         │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • selectedConfigPath kontrolü                       │  │  │
│  │  │ • validateConfigPath() - Path validation           │  │  │
│  │  │   - Dosya varlık kontrolü                         │  │  │
│  │  │   - Path traversal saldırı kontrolü               │  │  │
│  │  │   - Private directory kontrolü                     │  │  │
│  │  │   - Okunabilirlik kontrolü                         │  │  │
│  │  │                                                      │  │  │
│  │  │ • readConfigContentSecurely() - Güvenli okuma       │  │  │
│  │  │   - TOCTOU race condition önleme                   │  │  │
│  │  │   - Atomic read işlemi                             │  │  │
│  │  │                                                      │  │  │
│  │  │ Hata Durumu:                                        │  │  │
│  │  │ • Config yoksa → ACTION_ERROR broadcast            │  │  │
│  │  │ • stopXray() çağrısı                               │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 2.2: Server Address Pre-Resolution             │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ extractServerAddressFromConfig(configContent)        │  │  │
│  │  │                                                      │  │  │
│  │  │ • Config'den server adresi çıkarma                  │  │  │
│  │  │ • IP adresi kontrolü (isValidIpAddress)             │  │  │
│  │  │                                                      │  │  │
│  │  │ Eğer Domain Name ise:                                │  │  │
│  │  │ • InetAddress.getAllByName() - System DNS          │  │  │
│  │  │ • VPN başlamadan önce çözümleme                    │  │  │
│  │  │ • DnsCacheManager.saveToCache() - Cache'e kaydet   │  │  │
│  │  │                                                      │  │  │
│  │  │ Hata Durumu:                                        │  │  │
│  │  │ • DNS çözümleme başarısız → Warning log            │  │  │
│  │  │ • Xray-core kendi DNS'i ile çözmeye çalışır       │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 2.3: Instance Count Kontrolü                   │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ prefs.xrayCoreInstanceCount kontrolü                │  │  │
│  │  │                                                      │  │  │
│  │  │ • instanceCount > 1 → MultiXrayCoreManager         │  │  │
│  │  │ • instanceCount == 1 → Legacy Single Process       │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ┌──────────────────────────────────────────────────────┐ │ │
│  │ │ YOL A: Multi-Instance Mode (instanceCount > 1)      │ │ │
│  │ └──────────────────────────────────────────────────────┘ │ │
│  │                                                           │ │
│  │ ALT ADIM 2.4A: MultiXrayCoreManager Başlatma          │ │ │
│  │  ┌────────────────────────────────────────────────────┐ │ │
│  │  │ MultiXrayCoreManager.getInstance()                 │ │ │
│  │  │                                                      │ │ │
│  │  │ • LogLineCallback kurulumu:                         │ │ │
│  │  │   - logFileManager.appendLog()                      │ │ │
│  │  │   - logBroadcastChannel.trySend()                   │ │ │
│  │  │   - interceptDnsFromXrayLogs()                     │ │ │
│  │  │                                                      │ │ │
│  │  │ • instancesStatus StateFlow collector:             │ │ │
│  │  │   - Status değişikliklerini dinleme                 │ │ │
│  │  │   - ACTION_INSTANCE_STATUS_UPDATE broadcast         │ │ │
│  │  │                                                      │ │ │
│  │  │ • startInstances() çağrısı:                         │ │ │
│  │  │   - count: instanceCount                            │ │ │
│  │  │   - configPath: selectedConfigPath                  │ │ │
│  │  │   - configContent: configContent                   │ │ │
│  │  │   - excludedPorts: extractPortsFromJson()          │ │ │
│  │  │                                                      │ │ │
│  │  │ • Her instance için:                                │ │ │
│  │  │   - Port bulma (findAvailablePort)                 │ │ │
│  │  │   - Process başlatma                               │ │ │
│  │  │   - Config yazma                                   │ │ │
│  │  │   - Status tracking                                 │ │ │
│  │  │                                                      │ │ │
│  │  │ • İlk instance port'u primary API port olarak      │ │ │
│  │  └────────────────────────────────────────────────────┘ │ │
│  │                                                           │ │
│  │ ┌──────────────────────────────────────────────────────┐ │ │
│  │ │ YOL B: Single Instance Mode (Legacy)               │ │ │
│  │ └──────────────────────────────────────────────────────┘ │ │
│  │                                                           │ │
│  │ ALT ADIM 2.4B: Legacy Single Process Başlatma         │ │ │
│  │  ┌────────────────────────────────────────────────────┐ │ │
│  │  │ runXrayProcessLegacy(configFile, configContent)    │ │ │
│  │  │                                                      │ │ │
│  │  │ • getNativeLibraryDir() - Library dizini            │ │ │
│  │  │ • xrayPath = "$libraryDir/libxray.so"               │ │ │
│  │  │ • findAvailablePort() - API port bulma             │ │ │
│  │  │ • prefs.apiPort = apiPort                          │ │ │
│  │  │                                                      │ │ │
│  │  │ • getProcessBuilder(xrayPath):                      │ │ │
│  │  │   - linkerPath = "/system/bin/linker64"            │ │ │
│  │  │   - command = [linkerPath, xrayPath, "run"]        │ │ │
│  │  │   - environment["XRAY_LOCATION_ASSET"] = filesDir │ │ │
│  │  │   - redirectErrorStream(true)                      │ │ │
│  │  │                                                      │ │ │
│  │  │ • processBuilder.start():                           │ │ │
│  │  │   - Process oluşturma                              │ │ │
│  │  │   - xrayProcess = currentProcess                   │ │ │
│  │  └────────────────────────────────────────────────────┘ │ │
│  │                                                           │ │
│  │ ALT ADIM 2.5: Process Output Okuma Başlatma           │ │ │
│  │  ┌────────────────────────────────────────────────────┐ │ │
│  │  │ readProcessStreamWithTimeout(process)              │ │ │
│  │  │                                                      │ │ │
│  │  │ • Health Check Job başlatma:                       │ │ │
│  │  │   - Her 2 saniyede process.isAlive kontrolü        │ │ │
│  │  │   - Process ölürse readJob.cancel()               │ │ │
│  │  │                                                      │ │ │
│  │  │ • Read Job başlatma:                               │ │ │
│  │  │   - BufferedReader(process.inputStream)             │ │ │
│  │  │   - readLine() loop                                │ │ │
│  │  │   - Timeout protection (30 saniye)                │ │ │
│  │  │                                                      │ │ │
│  │  │ • Her log satırı için:                             │ │ │
│  │  │   - logFileManager.appendLog()                     │ │ │
│  │  │   - logBroadcastChannel.trySend()                  │ │ │
│  │  │   - interceptDnsFromXrayLogs()                     │ │ │
│  │  │   - detectUdpClosedPipeErrors()                    │ │ │
│  │  │   - detectConnectionResetErrors()                  │ │ │
│  │  │   - processSNIFromLog()                           │ │ │
│  │  │                                                      │ │ │
│  │  │ • "Xray ... started" tespiti:                      │ │ │
│  │  │   - SOCKS5 readiness check trigger                │ │ │
│  │  └────────────────────────────────────────────────────┘ │ │
│  │                                                           │ │
│  │ ALT ADIM 2.6: Process Startup Validasyonu             │ │ │
│  │  ┌────────────────────────────────────────────────────┐ │ │
│  │  │ • checkInterval = 50ms                            │ │ │
│  │  │ • minStartupChecks = 2 (100ms minimum)            │ │ │
│  │  │ • maxStartupChecks = 100 (5 saniye timeout)      │ │ │
│  │  │                                                      │ │ │
│  │  │ • Her 50ms'de:                                     │ │ │
│  │  │   - process.isAlive kontrolü                      │ │ │
│  │  │   - Process ölürse → Hata + Telegram notification│ │ │
│  │  │   - minStartupChecks sonrası → Validated          │ │ │
│  │  │                                                      │ │ │
│  │  │ • Process validated → Devam et                    │ │ │
│  │  └────────────────────────────────────────────────────┘ │ │
│  │                                                           │ │
│  │ ALT ADIM 2.7: Config Injection ve Yazma               │ │ │
│  │  ┌────────────────────────────────────────────────────┐ │ │
│  │  │ ConfigUtils.injectStatsService(prefs, configContent)│ │ │
│  │  │                                                      │ │ │
│  │  │ • Stats Service ekleme:                            │ │ │
│  │  │   - API port ekleme                                │ │ │
│  │  │   - Stats service konfigürasyonu                   │ │ │
│  │  │                                                      │ │ │
│  │  │ • UDP Support Verifikasyonu:                       │ │ │
│  │  │   - dokodemo-door inbound kontrolü                 │ │ │
│  │  │   - network array kontrolü                         │ │ │
│  │  │   - "udp" ve "tcp" kontrolü                        │ │ │
│  │  │   - Hata varsa → Log warning                       │ │ │
│  │  │                                                      │ │ │
│  │  │ • Config stdin'e yazma:                            │ │ │
│  │  │   - currentProcess.outputStream                    │ │ │
│  │  │   - os.write(injectedConfigContent.toByteArray())   │ │ │
│  │  │   - os.flush()                                     │ │ │
│  │  │                                                      │ │ │
│  │  │ Hata Durumu:                                        │ │ │
│  │  │ • Process ölürse → Error output okuma              │ │ │
│  │  │ • IOException → Hata log + Telegram notification  │ │ │
│  │  └────────────────────────────────────────────────────┘ │ │
│  └──────────────────────────────────────────────────────────┘ │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
                    Xray Process Başlatıldı ✓
```

---

## 4. ADIM 3: SOCKS5 Readiness Check

```
┌─────────────────────────────────────────────────────────────────┐
│              SOCKS5 Readiness Check Süreci                     │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                                                           │  │
│  │ ALT ADIM 3.1: Trigger Mekanizması                       │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ Multi-Instance Mode:                               │  │  │
│  │  │ • startInstances() döndükten sonra                 │  │  │
│  │  │ • instancesStatus StateFlow collector              │  │  │
│  │  │ • İlk Running instance tespiti                    │  │  │
│  │  │ • checkSocks5Readiness() trigger                  │  │  │
│  │  │                                                      │  │  │
│  │  │ Legacy Mode:                                        │  │  │
│  │  │ • Log stream'de "Xray ... started" tespiti        │  │  │
│  │  │ • 1 saniye delay (port binding için)              │  │  │
│  │  │ • checkSocks5Readiness() trigger                  │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 3.2: SOCKS5 Port Kontrolü                     │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ Socks5ReadinessChecker.waitForSocks5Ready()       │  │  │
│  │  │                                                      │  │  │
│  │  │ • socksPort = prefs.socksPort                      │  │  │
│  │  │ • socksAddress = "127.0.0.1"                      │  │  │
│  │  │ • maxWaitTimeMs = 10000 (10 saniye)                │  │  │
│  │  │ • retryIntervalMs = 500ms                          │  │  │
│  │  │                                                      │  │  │
│  │  │ • Her 500ms'de:                                    │  │  │
│  │  │   - Socket bağlantı denemesi                       │  │  │
│  │  │   - Port açık mı kontrolü                         │  │  │
│  │  │   - Başarılı olursa → Ready                       │  │  │
│  │  │   - Timeout olursa → Not Ready                    │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 3.3: Readiness State Güncelleme               │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • Socks5ReadinessChecker.setSocks5Ready(true)       │  │  │
│  │  │ • socks5ReadinessChecked = true                    │  │  │
│  │  │ • Log: "SOCKS5 is ready"                           │  │  │
│  │  │                                                      │  │  │
│  │  │ • Periodic Health Check başlatma:                 │  │  │
│  │  │   - startPeriodicSocks5HealthCheck()                │  │  │
│  │  │   - Her 30 saniyede kontrol                        │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 3.4: UDP Monitoring Başlatma                 │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ startUdpMonitoring()                               │  │  │
│  │  │                                                      │  │  │
│  │  │ • Her 10 saniyede UDP stats toplama                │  │  │
│  │  │ • UDP error detection                              │  │  │
│  │  │ • Recovery mechanism trigger                       │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
                    SOCKS5 Ready ✓
```

---

## 5. ADIM 4: AI Optimizer Başlatma

```
┌─────────────────────────────────────────────────────────────────┐
│              AI Optimizer Başlatma Süreci                      │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                                                           │  │
│  │ ALT ADIM 4.1: TProxyAiOptimizer Başlatma                │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • TProxy servisi optimizasyonu                      │  │  │
│  │  │ • Connection pool ayarları                         │  │  │
│  │  │ • Timeout optimizasyonu                            │  │  │
│  │  │ • Configuration callback kurulumu                  │  │  │
│  │  │ • startOptimization() - 30 saniye interval         │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 4.2: XrayCoreAiOptimizer Başlatma            │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • CoreStatsClient.create() - gRPC bağlantısı       │  │  │
│  │  │   - Address: "127.0.0.1"                           │  │  │
│  │  │   - Port: prefs.apiPort                            │  │  │
│  │  │   - Retry mechanism                                │  │  │
│  │  │                                                      │  │  │
│  │  │ • startOptimization() - 60 saniye interval          │  │  │
│  │  │   - Initial delay: 10 saniye                       │  │  │
│  │  │   - Metrics toplama (GetStats API)                 │  │  │
│  │  │   - ONNX model inference                           │  │  │
│  │  │   - Config optimization                            │  │  │
│  │  │   - Hot reload (UpdateConfig API)                  │  │  │
│  │  │   - Feedback collection                           │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 4.3: DeepPolicyModel Hazırlığı                │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • ONNX model yükleme (assets/models/*.onnx)       │  │  │
│  │  │ • OrtSession oluşturma                            │  │  │
│  │  │ • Context normalization hazırlığı                │  │  │
│  │  │ • Inference pipeline hazırlığı                   │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 4.4: RealityBandit Hazırlığı                  │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • Multi-armed bandit initialization                │  │  │
│  │  │ • Epsilon-greedy algorithm setup                   │  │  │
│  │  │ • Reward tracking initialization                   │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 4.5: OptimizerOrchestrator Başlatma           │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • Bandit ve Deep Model koordinasyonu               │  │  │
│  │  │ • Policy generation pipeline                       │  │  │
│  │  │ • Feedback collection mechanism                    │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
                    AI Optimizer Hazır ✓
```

---

## 6. ADIM 5: DNS Cache Entegrasyonu

```
┌─────────────────────────────────────────────────────────────────┐
│              DNS Cache Entegrasyonu Süreci                     │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                                                           │  │
│  │ ALT ADIM 5.1: DNS Query Interception                    │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ interceptDnsFromXrayLogs(logLine)                   │  │  │
│  │  │                                                      │  │  │
│  │  │ • Xray log'larından DNS query extraction            │  │  │
│  │  │ • extractDnsQuery(logLine)                          │  │  │
│  │  │ • Domain name çıkarma                               │  │  │
│  │  │                                                      │  │  │
│  │  │ • DnsCacheManager.getFromCache() kontrolü           │  │  │
│  │  │   - Cache hit → Log + Return                       │  │  │
│  │  │   - Cache miss → Resolution                        │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 5.2: DNS Resolution                            │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ forwardDnsQueryToSystemCacheServer(domain)           │  │  │
│  │  │                                                      │  │  │
│  │  │ • SystemDnsCacheServer.resolve()                    │  │  │
│  │  │   - Port 5353'te DNS server                         │  │  │
│  │  │   - System DNS query (InetAddress)                  │  │  │
│  │  │   - DoH fallback (Cloudflare, Google)               │  │  │
│  │  │   - Retry mechanism                                 │  │  │
│  │  │                                                      │  │  │
│  │  │ • Timeout: 5 saniye                                 │  │  │
│  │  │ • Max wait: 10 saniye                              │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 5.3: Cache Update                              │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • DnsCacheManager.saveToCache(domain, addresses) │  │  │
│  │  │ • Cache TTL yönetimi                               │  │  │
│  │  │ • Cache size limit kontrolü                        │  │  │
│  │  │ • Cache expiration cleanup                         │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 5.4: Sniffed Domain Caching                   │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ extractSniffedDomain(logLine)                      │  │  │
│  │  │                                                      │  │  │
│  │  │ • Xray sniffing log'larından domain çıkarma      │  │  │
│  │  │ • Cache check (öncelikli)                          │  │  │
│  │  │ • Cache miss → Resolution → Cache save            │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
                    DNS Cache Aktif ✓
```

---

## 7. ADIM 6: Log Streaming ve Monitoring

```
┌─────────────────────────────────────────────────────────────────┐
│              Log Streaming ve Monitoring Süreci                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                                                           │  │
│  │ ALT ADIM 6.1: Log Stream Okuma                          │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • BufferedReader(process.inputStream)                │  │  │
│  │  │ • readLine() loop (non-blocking)                    │  │  │
│  │  │ • Timeout protection (30 saniye)                    │  │  │
│  │  │ • Health check (her 2 saniye)                       │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 6.2: Log Processing                           │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ Her log satırı için:                                │  │  │
│  │  │                                                      │  │  │
│  │  │ • logFileManager.appendLog() - Dosyaya yazma       │  │  │
│  │  │ • logBroadcastChannel.trySend() - UI'ya gönderme   │  │  │
│  │  │ • interceptDnsFromXrayLogs() - DNS interception    │  │  │
│  │  │ • detectUdpClosedPipeErrors() - UDP error detection│  │  │
│  │  │ • detectConnectionResetErrors() - Connection errors │  │  │
│  │  │ • processSNIFromLog() - SNI extraction             │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 6.3: Log Broadcast                             │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • logBroadcastChannel (Channel<String>)             │  │  │
│  │  │ • Capacity: 1000, BufferOverflow.DROP_OLDEST       │  │  │
│  │  │ • trySend() - Non-blocking send                    │  │  │
│  │  │ • LogViewModel collector                            │  │  │
│  │  │ • UI update (LogScreen)                            │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ ALT ADIM 6.4: Error Detection                           │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • UDP Closed Pipe Errors:                          │  │  │
│  │  │   - "closed pipe" pattern detection               │  │  │
│  │  │   - Error counting ve tracking                     │  │  │
│  │  │   - Recovery mechanism trigger                    │  │  │
│  │  │                                                      │  │  │
│  │  │ • Connection Reset Errors:                        │  │  │
│  │  │   - "connection reset" pattern detection          │  │  │
│  │  │   - Error counting (threshold: 5)                │  │  │
│  │  │   - Alert mechanism                               │  │  │
│  │  │                                                      │  │  │
│  │  │ • SNI Extraction:                                  │  │  │
│  │  │   - extractSNI(logLine)                            │  │  │
│  │  │   - TLS optimization için kullanım                │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
                    Log Streaming Aktif ✓
```

---

## 8. Bağlantı Tamamlandı: Final State

```
┌─────────────────────────────────────────────────────────────────┐
│                    Bağlantı Tamamlandı                          │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                                                           │  │
│  │ ✓ VPN Servisi: ACTIVE                                    │  │
│  │   - TUN interface: UP                                      │  │
│  │   - IP: 10.0.0.2/24                                       │  │
│  │   - DNS: 127.0.0.1:5353                                   │  │
│  │   - Routes: 0.0.0.0/0                                     │  │
│  │                                                           │  │
│  │ ✓ SystemDnsCacheServer: RUNNING                          │  │
│  │   - Port: 5353                                            │  │
│  │   - DoH Fallback: ACTIVE                                   │  │
│  │   - DNS Cache: ACTIVE                                      │  │
│  │                                                           │  │
│  │ ✓ Native TProxy Service: RUNNING                         │  │
│  │   - TUN ↔ SOCKS5 Bridge: ACTIVE                          │  │
│  │   - UDP Gateway: ACTIVE                                  │  │
│  │   - TCP/UDP Forwarding: ACTIVE                           │  │
│  │                                                           │  │
│  │ ✓ Xray-core Process: RUNNING                             │  │
│  │   - Process ID: <pid>                                     │  │
│  │   - API Port: <port>                                      │  │
│  │   - Config: LOADED                                        │  │
│  │   - SOCKS5: READY                                        │  │
│  │   - Log Streaming: ACTIVE                                 │  │
│  │                                                           │  │
│  │ ✓ AI Optimizers: ACTIVE                                   │  │
│  │   - TProxyAiOptimizer: RUNNING                           │  │
│  │   - XrayCoreAiOptimizer: RUNNING                         │  │
│  │   - CoreStatsClient: CONNECTED                           │  │
│  │   - ONNX Models: LOADED                                  │  │
│  │                                                           │  │
│  │ ✓ Monitoring: ACTIVE                                      │  │
│  │   - Log Streaming: ACTIVE                                 │  │
│  │   - DNS Cache: ACTIVE                                     │  │
│  │   - UDP Monitoring: ACTIVE                                │  │
│  │   - SOCKS5 Health Check: ACTIVE                           │  │
│  │                                                           │  │
│  │ ✓ UI State: CONNECTED                                     │  │
│  │   - Connection Status: CONNECTED                          │  │
│  │   - Stats Display: ACTIVE                                 │  │
│  │   - Log Display: ACTIVE                                   │  │
│  │                                                           │  │
│  │ Trafik Akışı:                                            │  │
│  │   App → TUN → Native Tunnel → SOCKS5 → Xray → Internet  │  │
│  │                                                           │  │
│  │ DNS Akışı:                                               │  │
│  │   Query → SystemDnsCacheServer → Cache/DoH → Response    │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 9. Hata Senaryoları ve Kurtarma

```
┌─────────────────────────────────────────────────────────────────┐
│                    Hata Senaryoları                            │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                                                           │  │
│  │ HATA 1: VPN Interface Oluşturulamadı                    │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • builder.establish() → null                       │  │  │
│  │  │ • stopXray() çağrısı                               │  │  │
│  │  │ • ACTION_ERROR broadcast                           │  │  │
│  │  │ • Telegram notification                            │  │  │
│  │  │ • UI: CONNECTION_FAILED                             │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ HATA 2: Config Dosyası Bulunamadı                       │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • validateConfigPath() → null                      │  │  │
│  │  │ • ACTION_ERROR broadcast                           │  │  │
│  │  │ • stopXray() çağrısı                               │  │  │
│  │  │ • UI: CONNECTION_FAILED                             │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ HATA 3: Xray Process Başlatılamadı                       │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • Process startup validation failed                │  │  │
│  │  │ • Process exit during startup                     │  │  │
│  │  │ • Error output okuma                              │  │  │
│  │  │ • ACTION_ERROR broadcast                           │  │  │
│  │  │ • Telegram notification                            │  │  │
│  │  │ • stopXray() çağrısı                               │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ HATA 4: SOCKS5 Ready Olmadı                             │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • waitForSocks5Ready() → timeout                   │  │  │
│  │  │ • Warning log                                       │  │  │
│  │  │ • Bağlantı devam eder (Xray çalışıyor olabilir)   │  │  │
│  │  │ • Periodic health check devam eder                 │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ HATA 5: DNS Resolution Başarısız                        │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • SystemDnsCacheServer.resolve() → timeout         │  │  │
│  │  │ • DoH fallback denemesi                            │  │  │
│  │  │ • Xray-core kendi DNS'i ile çözmeye çalışır       │  │  │
│  │  │ • Warning log                                       │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  │                                                           │  │
│  │ HATA 6: Process Crash (Runtime)                          │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ • Health check → process.isAlive = false          │  │  │
│  │  │ • Process exit value okuma                        │  │  │
│  │  │ • ACTION_ERROR broadcast                           │  │  │
│  │  │ • Telegram notification                            │  │  │
│  │  │ • stopXray() çağrısı                               │  │  │
│  │  │ • UI: CONNECTION_FAILED                             │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 10. Zaman Çizelgesi (Timeline)

```
┌─────────────────────────────────────────────────────────────────┐
│                    Bağlantı Zaman Çizelgesi                    │
│                                                                 │
│  T=0ms    : Kullanıcı "Bağlan" butonuna tıklar                │
│  T=10ms   : MainViewModel.connect() çağrısı                   │
│  T=20ms   : TProxyService.onStartCommand()                     │
│  T=30ms   : startXray() çağrısı                               │
│                                                                 │
│  T=40ms   : ADIM 1 - SystemDnsCacheServer.start()             │
│  T=100ms  : ADIM 1 - VPN Builder oluşturma                    │
│  T=150ms  : ADIM 1 - builder.establish()                     │
│  T=200ms  : ADIM 1 - TUN interface oluşturuldu                │
│  T=250ms  : ADIM 1 - TProxy config dosyası yazma              │
│  T=300ms  : ADIM 1 - TProxyStartService()                     │
│  T=350ms  : ADIM 1 - Native tunnel başlatıldı                  │
│                                                                 │
│  T=400ms  : ADIM 2 - Config validasyonu                       │
│  T=450ms  : ADIM 2 - Server address pre-resolution            │
│  T=500ms  : ADIM 2 - ProcessBuilder oluşturma                 │
│  T=550ms  : ADIM 2 - process.start()                          │
│  T=600ms  : ADIM 2 - Process output okuma başlatıldı          │
│  T=650ms  : ADIM 2 - Process startup validation (50ms checks)  │
│  T=700ms  : ADIM 2 - Process validated                        │
│  T=750ms  : ADIM 2 - Config stdin'e yazma                     │
│  T=800ms  : ADIM 2 - Config yazıldı                           │
│                                                                 │
│  T=1000ms : Xray process başlatıldı (log output başlar)        │
│  T=2000ms : Xray log: "Xray ... started"                       │
│  T=3000ms : ADIM 3 - SOCKS5 readiness check trigger            │
│  T=3500ms : ADIM 3 - SOCKS5 port kontrolü                     │
│  T=4000ms : ADIM 3 - SOCKS5 ready ✓                            │
│                                                                 │
│  T=5000ms : ADIM 4 - AI Optimizer başlatma                     │
│  T=6000ms : ADIM 4 - CoreStatsClient.connect()                 │
│  T=7000ms : ADIM 4 - AI Optimizer active ✓                     │
│                                                                 │
│  T=8000ms : ADIM 5 - DNS cache entegrasyonu aktif              │
│  T=9000ms : ADIM 6 - Log streaming aktif                        │
│                                                                 │
│  T=10000ms: Bağlantı tamamen hazır ✓                           │
│                                                                 │
│  Toplam Süre: ~10 saniye                                       │
└─────────────────────────────────────────────────────────────────┘
```

---

## 11. Kritik Noktalar ve Notlar

### 11.1. Sıralama Önemi

1. **VPN Servisi ÖNCE**: TUN interface mutlaka Xray process'ten önce oluşturulmalı
2. **DNS Server ÖNCE**: SystemDnsCacheServer VPN builder'dan önce başlatılmalı
3. **Config Yazma SONRA**: Process başlatıldıktan sonra config yazılmalı
4. **SOCKS5 Check SONRA**: Xray "started" log'undan sonra kontrol edilmeli

### 11.2. Hata Toleransı

- **DNS Resolution**: Başarısız olsa bile Xray devam eder
- **SOCKS5 Ready**: Timeout olsa bile bağlantı çalışabilir
- **AI Optimizer**: Başarısız olsa bile VPN çalışır

### 11.3. Resource Management

- **File Descriptors**: Mutlaka kapatılmalı
- **Process Streams**: Graceful shutdown için kapatılmalı
- **Coroutines**: Proper cancellation gerekli
- **Native Resources**: TProxyStopService() mutlaka çağrılmalı

### 11.4. Thread Safety

- **tunFd**: synchronized(tunFdLock) ile korunmalı
- **xrayProcess**: Volatile veya synchronized kullanılmalı
- **State Variables**: Thread-safe olmalı

---

## Sonuç

Bu detaylı şema, HyperXray VPN bağlantı sürecinin tüm aşamalarını, alt adımlarını, hata senaryolarını ve zaman çizelgesini kapsamaktadır. Her adım, kod seviyesinde gerçekleşen işlemleri ve bunların sıralamasını göstermektedir.

Bağlantı süreci yaklaşık 10 saniye içinde tamamlanır ve tüm bileşenler (VPN, Xray, DNS, AI Optimizer) koordineli bir şekilde çalışır.



