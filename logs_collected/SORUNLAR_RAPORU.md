# HyperXray Sorun Analiz Raporu

**Oluşturulma Tarihi:** 2025-11-27 20:25:26  
**Cihaz Modeli:** 24129PN74G  
**Android Sürümü:** 16  
**SDK Sürümü:** 36  
**Uygulama Versiyonu:** 1.11.7 (versionCode: 38)

---

## Özet

Bu rapor, cihazdan toplanan logların analizi sonucunda tespit edilen sorunları içermektedir. Toplam 7 ana sorun kategorisi tespit edilmiştir.

---

## 1. TurboSchedMonitor Sorunları

### Sorun 1.1: setSchedAffinity Metodu Bulunamadı

**Hata Mesajı:** "failed to find setSchedAffinity method"

**Log Kayıtları:**

```
11-27 20:00:03.792 E/TurboSchedMonitor(32608): failed to find setSchedAffinity method com.hyperxray.an
11-27 20:00:03.793 E/TurboSchedMonitor(32608): failed to find setSchedAffinity method com.hyperxray.an
11-27 20:00:05.587 E/TurboSchedMonitor( 7435): failed to find setSchedAffinity method com.hyperxray.an:native
11-27 20:00:05.587 E/TurboSchedMonitor( 7435): failed to find setSchedAffinity method com.hyperxray.an:native
11-27 20:04:32.880 E/TurboSchedMonitor(32609): failed to find setSchedAffinity method com.hyperxray.an
11-27 20:04:32.882 E/TurboSchedMonitor(32609): failed to find setSchedAffinity method com.hyperxray.an
11-27 20:04:34.320 E/TurboSchedMonitor(10664): failed to find setSchedAffinity method com.hyperxray.an:native
11-27 20:04:34.320 E/TurboSchedMonitor(10664): failed to find setSchedAffinity method com.hyperxray.an:native
11-27 20:08:06.850 E/TurboSchedMonitor( 7608): failed to find setSchedAffinity method com.hyperxray.an
11-27 20:08:06.855 E/TurboSchedMonitor( 7608): failed to find setSchedAffinity method com.hyperxray.an
11-27 20:08:08.430 E/TurboSchedMonitor(14487): failed to find setSchedAffinity method com.hyperxray.an:native
11-27 20:08:08.430 E/TurboSchedMonitor(14487): failed to find setSchedAffinity method com.hyperxray.an:native
11-27 20:20:39.125 E/TurboSchedMonitor(11740): failed to find setSchedAffinity method com.hyperxray.an
11-27 20:20:39.126 E/TurboSchedMonitor(11740): failed to find setSchedAffinity method com.hyperxray.an
11-27 20:20:40.391 E/TurboSchedMonitor(19222): failed to find setSchedAffinity method com.hyperxray.an:native
11-27 20:20:40.392 E/TurboSchedMonitor(19222): failed to find setSchedAffinity method com.hyperxray.an:native
```

**Zaman Damgası:** 2025-11-27 20:00:03 - 20:20:40

**Etkilenen Bileşenler:**

- TurboSchedMonitor (sistem bileşeni)
- Process scheduling optimization
- Ana uygulama process (com.hyperxray.an)
- Native process (com.hyperxray.an:native)

**PID'ler:** 32608, 7435, 32609, 10664, 7608, 14487, 11740, 19222

---

## 2. ConnectivityService RemoteException Sorunları

### Sorun 2.1: NetworkRequest Callback Hatası

**Hata Tipi:** RemoteException  
**Hata Mesajı:** "RemoteException caught trying to send a callback msg for NetworkRequest"

**Log Kayıtları:**

```
11-27 20:20:36.488 E/ConnectivityService( 3688): RemoteException caught trying to send a callback msg for NetworkRequest [ TRACK_DEFAULT id=5295, [ Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED&NOT_VCN_MANAGED&NOT_BANDWIDTH_CONSTRAINED Uid: 10570 RequestorUid: 10570 RequestorPkg: com.hyperxray.an UnderlyingNetworks: Null] ]
11-27 20:20:36.489 E/ConnectivityService( 3688): RemoteException caught trying to send a callback msg for NetworkRequest [ LISTEN id=5297, [ Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED&FOREGROUND&NOT_VCN_MANAGED&NOT_BANDWIDTH_CONSTRAINED Uid: 10570 RequestorUid: 10570 RequestorPkg: com.hyperxray.an UnderlyingNetworks: Null] ]
11-27 20:20:36.573 E/ConnectivityService( 3688): RemoteException caught trying to send a callback msg for NetworkRequest [ TRACK_DEFAULT id=5295, [ Capabilities: INTERNET&NOT_RESTRICTED&TRUSTED&NOT_VCN_MANAGED&NOT_BANDWIDTH_CONSTRAINED Uid: 10570 RequestorUid: 10570 RequestorPkg: com.hyperxray.an UnderlyingNetworks: Null] ]
```

**Zaman Damgası:** 2025-11-27 20:20:36

**Etkilenen Bileşenler:**

- ConnectivityService
- NetworkRequest callbacks
- VPN bağlantı yönetimi

**Request ID'leri:** 5295, 5297

---

## 3. AppOps Sorunları

### Sorun 3.1: Operation Not Started - ESTABLISH_VPN_SERVICE

**Hata Tipi:** AppOps  
**Hata Mesajı:** "Operation not started: uid=10570 pkg=com.hyperxray.an(null) op=ESTABLISH_VPN_SERVICE"

**Log Kayıtları:**

```
11-27 20:04:26.453 E/AppOps  ( 3688): Operation not started: uid=10570 pkg=com.hyperxray.an(null) op=ESTABLISH_VPN_SERVICE
11-27 20:07:52.555 E/AppOps  ( 3688): Operation not started: uid=10570 pkg=com.hyperxray.an(null) op=ESTABLISH_VPN_SERVICE
11-27 20:20:36.402 E/AppOps  ( 3688): Operation not started: uid=10570 pkg=com.hyperxray.an(null) op=ESTABLISH_VPN_SERVICE
```

**Zaman Damgası:** 2025-11-27 20:04:26, 20:07:52, 20:20:36

**Etkilenen Bileşenler:**

- AppOps
- VPN Service establishment
- HyperVpnService

---

### Sorun 3.2: Operation Not Started - WAKE_LOCK

**Hata Tipi:** AppOps  
**Hata Mesajı:** "Operation not started: uid=10570 pkg=com.hyperxray.an(null) op=WAKE_LOCK"

**Log Kayıtları:**

```
11-27 20:04:26.482 E/AppOps  ( 3688): Operation not started: uid=10570 pkg=com.hyperxray.an(null) op=WAKE_LOCK
11-27 20:07:52.582 E/AppOps  ( 3688): Operation not started: uid=10570 pkg=com.hyperxray.an(null) op=WAKE_LOCK
11-27 20:20:36.628 E/AppOps  ( 3688): Operation not started: uid=10570 pkg=com.hyperxray.an(null) op=WAKE_LOCK
```

**Zaman Damgası:** 2025-11-27 20:04:26, 20:07:52, 20:20:36

**Etkilenen Bileşenler:**

- AppOps
- Wake lock management
- Background service operations

---

## 4. FrameInsert Dosya Erişim Hatası

### Sorun 4.1: FrameInsert Open Fail

**Hata Tipi:** FileNotFoundException  
**Hata Mesajı:** "FrameInsert open fail: No such file or directory"

**Log Kayıtları:**

```
11-27 20:20:44.562 E/om.hyperxray.an(11740): FrameInsert open fail: No such file or directory
```

**Zaman Damgası:** 2025-11-27 20:20:44

**Etkilenen Bileşenler:**

- FrameInsert (native component)
- File system operations

**PID:** 11740

---

## 5. TlsSniModel Model Dosyası Bulunamadı

### Sorun 5.1: FP32 ve FP16 Modelleri Bulunamadı

**Hata Tipi:** Warning  
**Hata Mesajı:** "FP32 model not found, trying FP16" ve "FP16 model not found, trying v9 fallback"

**Log Kayıtları:**

```
11-27 20:21:15.067 W/TlsSniModel(11740): FP32 model not found, trying FP16: models/tls_sni_optimizer_v5_fp32.onnx
11-27 20:21:15.067 W/TlsSniModel(11740): FP16 model not found, trying v9 fallback: models/tls_sni_optimizer_v5_fp16.onnx
11-27 20:21:15.072 W/TlsSniModel(11740): FP32 model not found, trying FP16: models/tls_sni_optimizer_v5_fp32.onnx
11-27 20:21:15.072 W/TlsSniModel(11740): FP16 model not found, trying v9 fallback: models/tls_sni_optimizer_v5_fp16.onnx
```

**Zaman Damgası:** 2025-11-27 20:21:15 (çok sayıda tekrar)

**Etkilenen Bileşenler:**

- TlsSniModel
- ONNX Runtime
- TLS SNI optimizer

**Durum:** v9 fallback modeli başarıyla yükleniyor, ancak v5 FP32 ve FP16 modelleri bulunamıyor.

---

## 6. HyperSentinel Bellek Kullanımı Artışı

### Sorun 6.1: RSS Memory Size Değişiklikleri

**Hata Tipi:** Memory Monitoring  
**Hata Mesajı:** "Rss Memory Size Change"

**Log Kayıtları:**

```
11-27 20:20:38.982 E/HyperSentinel( 3688): ->pid:11740,processName:"com.hyperxray.an",Rss Memory Size Change 122152kb -> 163268kb
11-27 20:20:38.982 E/HyperSentinel( 3688): ->pid:11740,processName:"com.hyperxray.an",Rss Memory Size Change 163268kb -> 218372kb
11-27 20:20:39.231 E/HyperSentinel( 3688): ->pid:11740,processName:"com.hyperxray.an",Rss Memory Size Change 218372kb -> 285812kb
11-27 20:20:39.454 E/HyperSentinel( 3688): ->pid:11740,processName:"com.hyperxray.an",Rss Memory Size Change 285812kb -> 358772kb
11-27 20:21:15.087 E/HyperSentinel( 3688): ->pid:11740,processName:"com.hyperxray.an",Rss Memory Size Change 358772kb -> 464712kb
11-27 20:21:15.600 E/HyperSentinel( 3688): ->pid:11740,processName:"com.hyperxray.an",Rss Memory Size Change 464712kb -> 558772kb
11-27 20:21:57.495 E/HyperSentinel( 3688): ->pid:11740,processName:"com.hyperxray.an",Rss Memory Size Change 558772kb -> 670576kb
```

**Zaman Damgası:** 2025-11-27 20:20:38 - 20:21:57

**Etkilenen Bileşenler:**

- HyperSentinel (sistem bileşeni)
- Memory monitoring
- Uygulama process (com.hyperxray.an)

**Bellek Artışı:** 122 MB → 670 MB (yaklaşık 5.5x artış)

**PID:** 11740

---

## 7. DexOptHelperImpl Sorunları

### Sorun 7.1: ART Odex Vdex Alınamadı

**Hata Tipi:** DexOptHelperImpl  
**Hata Mesajı:** "Fail to get art odex vdex with path"

**Log Kayıtları:**

```
11-27 20:25:15.831 E/DexOptHelperImpl( 3688): Fail to get art odex vdex with path :/data/app/~~-QaAmrTDjtxNB5u8LH3M0g==/com.hyperxray.an-uNzZlhpmjpwsbMHttvFCJw==/base.apk
```

**Zaman Damgası:** 2025-11-27 20:25:15

**Etkilenen Bileşenler:**

- DexOptHelperImpl
- ART runtime optimization
- APK optimization

**Dosya Yolu:** `/data/app/~~-QaAmrTDjtxNB5u8LH3M0g==/com.hyperxray.an-uNzZlhpmjpwsbMHttvFCJw==/base.apk`

---

## Hata İstatistikleri

| Sorun Kategorisi                    | Hata Sayısı | Kritiklik |
| ----------------------------------- | ----------- | --------- |
| TurboSchedMonitor                   | 16          | Düşük     |
| ConnectivityService RemoteException | 3           | Yüksek    |
| AppOps (ESTABLISH_VPN_SERVICE)      | 3           | Yüksek    |
| AppOps (WAKE_LOCK)                  | 3           | Orta      |
| FrameInsert                         | 1           | Orta      |
| TlsSniModel Model Bulunamadı        | 100+        | Düşük     |
| HyperSentinel Bellek Artışı         | 7           | Orta      |
| DexOptHelperImpl                    | 1           | Düşük     |

**Toplam Hata Sayısı:** 134+

---

## Log Dosyaları

Toplanan log dosyaları:

- `logs_collected/app_log_latest.txt` - 1001 satır
- `logs_collected/error_logs.txt` - 103 satır
- `logs_collected/full_app_logs.txt` - 2000+ satır

---

## Notlar

1. TurboSchedMonitor hataları sistem seviyesinde olup, uygulama işlevselliğini doğrudan etkilememektedir. Bu hatalar, sistem optimizasyon bileşeninin uygulama için özel scheduling metodunu bulamadığını göstermektedir.

2. ConnectivityService RemoteException hataları, VPN servisinin bağlantı durumunu takip ederken oluşmaktadır. Bu, callback mekanizmasında bir sorun olduğunu gösterebilir.

3. AppOps hataları, VPN servisinin başlatılması ve wake lock kullanımı sırasında oluşmaktadır. Bu, izin yönetimi ile ilgili bir sorun olabilir.

4. FrameInsert hatası, native bileşenin bir dosyaya erişmeye çalışırken oluşmaktadır. Dosya yolu veya izinlerle ilgili bir sorun olabilir.

5. TlsSniModel uyarıları, v5 FP32 ve FP16 modellerinin bulunamadığını ancak v9 fallback modelinin başarıyla yüklendiğini göstermektedir. Bu, kritik bir sorun değildir ancak performans etkisi olabilir.

6. HyperSentinel bellek izleme logları, uygulama başlatma sırasında bellek kullanımının 122 MB'dan 670 MB'a çıktığını göstermektedir. Bu, normal bir başlatma davranışı olabilir ancak izlenmesi gerekir.

7. DexOptHelperImpl hatası, ART runtime'ın APK optimizasyon dosyalarına erişemediğini göstermektedir. Bu, geçici bir sorun olabilir.

---

**Rapor Sonu**
