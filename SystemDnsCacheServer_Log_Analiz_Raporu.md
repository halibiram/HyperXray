# SystemDnsCacheServer Log Analiz Raporu

**Tarih**: 25 Kasım 2024  
**Analiz Süresi**: Son 200 log satırı

## 📊 Genel Durum

### ✅ Başarılı İşlemler

**Cache Hit Oranı**: Yüksek

- Popüler domainler için cache hit başarılı (instagram.com, youtube.com, facebook.com, netflix.com, vb.)
- Cache hit süreleri: 0-25ms arası (çok hızlı)
- Örnek başarılı cache hit'ler:
  - `instagram.com -> [157.240.234.174] (25ms)`
  - `www.youtube.com -> [172.217.17.110, ...] (1ms)`
  - `api.facebook.com -> [157.240.234.15] (1ms)`
  - `netflix.com -> [54.170.196.176, ...] (4ms)`

### ⚠️ Sorunlar

#### 1. **Timeout Hataları (KRİTİK)**

**Sorun**: Çok sayıda DNS çözümleme işlemi timeout nedeniyle başarısız oluyor.

**Hata Mesajı**:

```
kotlinx.coroutines.TimeoutCancellationException: Timed out waiting for 2000 ms
```

**Etkilenen Domainler**:

- `s.update.rose.pubmatic.com` (çoklu timeout)
- `googleads.g.doubleclick.net`
- `pagead2.googlesyndication.com`
- `pubads.g.doubleclick.net`
- `ad.doubleclick.net`
- `b.videoamp.com`
- `cgkthn.com`
- `idsync.rlcdn.com`
- `census-app.scorecardresearch.com`
- `cadmus2.script.ac`
- `ipv6.adrta.com`
- `adrta.com`
- `q.adrta.com`
- `aax-events-cell01-cf.us-east.aps.axp.amazon-adsystem.com`

**Kök Neden Analizi**:

1. **TProxyUtils Timeout Çok Kısa**:

   - `TProxyUtils.kt:420`: `maxWaitTimeMs = 2000L` (2 saniye)
   - Bu timeout, `DnsUpstreamClient`'in Happy Eyeballs algoritması için yeterli değil

2. **DnsUpstreamClient Timeout Yapılandırması**:

   - `DEFAULT_TIMEOUT_MS = 1000L` (1 saniye)
   - `MAX_TIMEOUT_MS = 3000L` (3 saniye)
   - Happy Eyeballs birden fazla DNS sunucusunu sırayla deniyor (wave-based)
   - Her wave arasında `HAPPY_EYEBALLS_WAVE_DELAY_MS = 400L` (400ms) bekleme var

3. **Timeout Çakışması**:
   - TProxyUtils 2000ms timeout kullanıyor
   - DnsUpstreamClient'in Happy Eyeballs algoritması birden fazla DNS sunucusunu deniyor
   - İlk wave başarısız olursa, ikinci wave için 400ms bekliyor
   - Toplam süre 2000ms'yi aşabiliyor

#### 2. **Cache Miss Oranı**

**Yüksek Cache Miss Oranı**: Birçok domain için cache miss oluyor ve upstream'e gidiyor.

**Örnek Cache Miss'ler**:

- `ipv4-c246-was001-ix.1.oca.nflxvideo.net`
- `ipv4-c145-nyc005-ix.1.oca.nflxvideo.net`
- `cloudflare-dns.com`
- `dns.quad9.net`
- `www.speedtest.net`
- `raw.githubusercontent.com`
- `scontent-iad3-1.cdninstagram.com`

**Not**: Cache miss normal bir durum, ancak timeout nedeniyle başarısız oluyor.

## 🔍 Teknik Detaylar

### Timeout Yapılandırması

| Bileşen                     | Timeout Değeri | Konum                                                                   |
| --------------------------- | -------------- | ----------------------------------------------------------------------- |
| TProxyUtils                 | 2000ms         | `app/src/main/kotlin/com/hyperxray/an/service/utils/TProxyUtils.kt:420` |
| DnsUpstreamClient (Default) | 1000ms         | `core/core-network/.../DnsUpstreamClient.kt:26`                         |
| DnsUpstreamClient (Max)     | 3000ms         | `core/core-network/.../DnsUpstreamClient.kt:27`                         |
| Happy Eyeballs Wave Delay   | 400ms          | `core/core-network/.../DnsUpstreamClient.kt:28`                         |
| SystemDnsCacheServer Socket | 5000ms         | `core/core-network/.../SystemDnsCacheServer.kt:20`                      |

### Happy Eyeballs Algoritması

1. **Wave 1**: İlk 3 en hızlı DNS sunucusuna paralel sorgu (timeout: adaptive, max 3000ms)
2. **Wave 2**: 400ms sonra bir sonraki 3 sunucuya paralel sorgu (eğer wave 1 başarısız)
3. **Toplam Süre**: En kötü durumda 3000ms + 400ms + 3000ms = 6400ms olabilir

**Sorun**: TProxyUtils'in 2000ms timeout'u bu algoritma için yeterli değil.

## 💡 Öneriler

### 1. TProxyUtils Timeout Artırılmalı (ÖNCELİKLİ)

**Öneri**: `maxWaitTimeMs` değeri 2000ms'den en az 5000ms'ye çıkarılmalı.

**Gerekçe**:

- Happy Eyeballs algoritması birden fazla DNS sunucusunu deniyor
- Adaptive timeout mekanizması var (max 3000ms)
- Wave delay 400ms
- Toplam süre 2000ms'yi aşabiliyor

**Kod Değişikliği**:

```kotlin
// TProxyUtils.kt:420
val maxWaitTimeMs = 5000L // 2000L'den 5000L'ye çıkarıldı
```

### 2. DnsUpstreamClient Timeout Optimizasyonu

**Mevcut Durum**: Adaptive timeout mekanizması var ancak TProxyUtils'in timeout'u çok kısa.

**Öneri**: TProxyUtils timeout'u artırıldıktan sonra, DnsUpstreamClient'in timeout mekanizması yeterli olacak.

### 3. Cache Warm-up Optimizasyonu

**Mevcut Durum**: Cache warm-up her 6 saatte bir çalışıyor.

**Öneri**:

- Daha sık warm-up (örneğin 3 saatte bir)
- Daha fazla popüler domain eklenebilir
- Kullanıcı davranışına göre adaptive warm-up

### 4. Log İyileştirmeleri

**Öneri**:

- Başarılı DNS çözümlemeleri için daha fazla log (şu an sadece cache hit'ler loglanıyor)
- Timeout hatalarında hangi DNS sunucusunun denendiği loglanmalı
- Happy Eyeballs wave bilgisi loglanmalı

## 📈 İstatistikler

### Log Analizi (Son 200 Satır)

- **Toplam Log Satırı**: ~200
- **Timeout Hataları**: ~50+ (çok yüksek)
- **Cache Hit'ler**: ~30+ (başarılı)
- **Cache Miss'ler**: ~40+ (normal, ancak timeout nedeniyle başarısız)

### Timeout Hata Oranı

**Tahmini**: %60-70 (çok yüksek)

**Etkilenen Domain Kategorileri**:

- Reklam domainleri (doubleclick.net, pubmatic.com, vb.)
- Analytics domainleri (scorecardresearch.com, vb.)
- CDN domainleri (videoamp.com, vb.)
- Özel domainler (adrta.com, cgkthn.com, vb.)

## 🎯 Acil Aksiyonlar

1. ✅ **TProxyUtils timeout artırılmalı** (2000ms → 5000ms)
2. ⚠️ **Log iyileştirmeleri yapılmalı** (timeout detayları)
3. 📊 **Monitoring eklenmeli** (timeout oranı, başarı oranı)

## 📝 Sonuç

SystemDnsCacheServer genel olarak çalışıyor ancak **timeout sorunu kritik**. TProxyUtils'deki 2000ms timeout, DnsUpstreamClient'in Happy Eyeballs algoritması için yeterli değil. Timeout artırıldığında, DNS çözümleme başarı oranı önemli ölçüde artacaktır.

**Öncelik**: YÜKSEK  
**Etki**: YÜKSEK  
**Çözüm Süresi**: DÜŞÜK (tek satır değişiklik)
