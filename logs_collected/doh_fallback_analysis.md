# DoH (DNS over HTTPS) Fallback Çalışma Yapısı Analizi

**Tarih:** 2025-11-21  
**Kod Konumu:** `SystemDnsCacheServer.kt` - `tryDoHFallback()` fonksiyonu

---

## 📋 Mevcut Durum

### ⚠️ Önemli Tespit: Gerçek DoH Kullanılmıyor!

**Mevcut `tryDoHFallback()` fonksiyonu:**

- ❌ Gerçek DNS over HTTPS (DoH) kullanmıyor
- ✅ Android'in sistem DNS resolver'ını (`InetAddress.getAllByName`) kullanıyor
- ⚠️ Fonksiyon adı "DoH fallback" olsa da, gerçekte sistem DNS fallback'i

---

## 🔍 Mevcut Çalışma Yapısı

### 1. Çağrılma Senaryosu

```kotlin
// SystemDnsCacheServer.kt - forwardToUpstreamDnsWithRetry() içinde
for ((attempt, timeoutMs) in timeouts.withIndex()) {
    // UDP DNS sorguları deneniyor
    val result = forwardToUpstreamDnsWithTimeout(queryData, hostname, timeoutMs)
    if (result != null) {
        return result // Başarılı
    }
}

// Tüm UDP DNS sorguları başarısız olduysa
val dohResult = tryDoHFallback(hostname) // DoH fallback çağrılıyor
if (dohResult != null) {
    return dohResult
}
```

### 2. Mevcut `tryDoHFallback()` İmplementasyonu

```kotlin
private suspend fun tryDoHFallback(hostname: String): ByteArray? {
    return withContext(Dispatchers.IO) {
        // Retry mekanizması: 3 deneme, artan timeout'lar
        val fallbackTimeouts = listOf(2000L, 3000L, 5000L)

        for ((attempt, timeoutMs) in fallbackTimeouts.withIndex()) {
            try {
                // ⚠️ GERÇEK DoH DEĞİL - Sistem DNS resolver kullanılıyor
                val addresses = withTimeoutOrNull(timeoutMs) {
                    InetAddress.getAllByName(hostname) // Android sistem DNS
                }

                if (addresses != null && addresses.isNotEmpty()) {
                    // DNS response packet oluştur
                    val queryData = buildDnsQuery(hostname)
                    val responseData = buildDnsResponse(queryData, addresses.toList())

                    // Cache'e kaydet
                    DnsCacheManager.saveToCache(hostname, addresses.toList(), ttl = 3600L)

                    return@withContext responseData
                }
            } catch (e: Exception) {
                // Retry...
            }
        }
        null // Tüm denemeler başarısız
    }
}
```

### 3. Çalışma Akışı

```
┌─────────────────────────────────────┐
│ UDP DNS Sorguları (3 deneme)       │
│ - 200ms, 350ms, 600ms timeout      │
└──────────────┬──────────────────────┘
               │
               ▼
        [Başarısız mı?]
               │
               ├─── Başarılı ──► [DNS Response Döndür]
               │
               ▼ Başarısız
┌─────────────────────────────────────┐
│ tryDoHFallback() çağrılıyor         │
│ (Sistem DNS Fallback)               │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│ Sistem DNS Resolver                 │
│ InetAddress.getAllByName()          │
│ - 3 deneme: 2000ms, 3000ms, 5000ms  │
└──────────────┬──────────────────────┘
               │
               ▼
        [Başarılı mı?]
               │
               ├─── Başarılı ──► [DNS Response + Cache]
               │
               ▼ Başarısız
        [null döndür - DNS çözümleme başarısız]
```

---

## ⚠️ Sorunlar ve Eksiklikler

### 1. Gerçek DoH Kullanılmıyor

**Sorun:**

- Fonksiyon adı "DoH fallback" ama gerçek DoH kullanmıyor
- Sadece Android sistem DNS resolver'ı kullanılıyor
- DoH provider'ları (`HttpClientFactory.kt`'de tanımlı) kullanılmıyor

**Etki:**

- UDP DNS engellendiğinde DoH alternatifi yok
- Sistem DNS de engellenirse tamamen başarısız oluyor
- Gerçek DoH avantajlarından (şifreleme, gizlilik) yararlanılamıyor

### 2. DoH Provider'ları Mevcut Ama Kullanılmıyor

**HttpClientFactory.kt'de tanımlı DoH provider'ları:**

```kotlin
val cloudflareDoh = DnsOverHttps.Builder()
    .client(dnsClient)
    .url("https://cloudflare-dns.com/dns-query")
    .includeIPv6(true)
    .build()

val googleDoh = DnsOverHttps.Builder()
    .client(dnsClient)
    .url("https://dns.google/dns-query")
    .includeIPv6(true)
    .build()

val quad9Doh = DnsOverHttps.Builder()
    .client(dnsClient)
    .url("https://dns.quad9.net/dns-query")
    .includeIPv6(true)
    .build()

val openDnsDoh = DnsOverHttps.Builder()
    .client(dnsClient)
    .url("https://doh.opendns.com/dns-query")
    .includeIPv6(true)
    .build()
```

**Ancak bu provider'lar sadece `HttpClientFactory.fastDns` resolver'ında kullanılıyor, `SystemDnsCacheServer`'da kullanılmıyor.**

---

## 💡 İyileştirme Önerileri

### 1. Gerçek DoH Fallback İmplementasyonu (Yüksek Öncelik)

**Öneri:** `tryDoHFallback()` fonksiyonunu gerçek DoH kullanacak şekilde güncelle

**Yeni İmplementasyon:**

```kotlin
/**
 * DNS over HTTPS (DoH) fallback when UDP DNS fails
 * Uses real DoH providers (Cloudflare, Google, Quad9, OpenDNS) with parallel queries
 */
private suspend fun tryDoHFallback(hostname: String): ByteArray? {
    return withContext(Dispatchers.IO) {
        // DoH provider'ları (HttpClientFactory'den al veya burada oluştur)
        val dohProviders = listOf(
            createDoHProvider("https://cloudflare-dns.com/dns-query"),
            createDoHProvider("https://dns.google/dns-query"),
            createDoHProvider("https://dns.quad9.net/dns-query"),
            createDoHProvider("https://doh.opendns.com/dns-query")
        )

        // Paralel DoH sorguları - ilk başarılı yanıtı al
        val dohTimeouts = listOf(2000L, 3000L, 5000L)

        for ((attempt, timeoutMs) in dohTimeouts.withIndex()) {
            try {
                if (attempt > 0) {
                    Log.d(TAG, "🔄 Retrying DoH fallback (attempt ${attempt + 1}/${dohTimeouts.size}) for $hostname with timeout ${timeoutMs}ms...")
                    delay(50)
                } else {
                    Log.d(TAG, "🔄 Trying DoH fallback for $hostname (real DNS over HTTPS)...")
                }

                // Paralel DoH sorguları - tüm provider'ları aynı anda dene
                val deferredResults = dohProviders.map { dohProvider ->
                    async(Dispatchers.IO) {
                        withTimeoutOrNull(timeoutMs) {
                            try {
                                dohProvider.lookup(hostname)
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                }

                // İlk başarılı yanıtı al
                val selectedResult = select<List<InetAddress>?> {
                    deferredResults.forEachIndexed { index, deferred ->
                        deferred.onAwait { result ->
                            if (result != null && result.isNotEmpty()) {
                                val providerName = when (index) {
                                    0 -> "Cloudflare"
                                    1 -> "Google"
                                    2 -> "Quad9"
                                    3 -> "OpenDNS"
                                    else -> "Unknown"
                                }
                                Log.i(TAG, "✅ DNS resolved via DoH ($providerName): $hostname -> ${result.map { it.hostAddress }}")
                                result
                            } else null
                        }
                    }
                }

                if (selectedResult != null && selectedResult.isNotEmpty()) {
                    // DNS response packet oluştur
                    val queryData = buildDnsQuery(hostname) ?: return@withContext null
                    val responseData = buildDnsResponse(queryData, selectedResult)

                    // Cache'e kaydet
                    DnsCacheManager.saveToCache(hostname, selectedResult, ttl = 3600L)

                    if (attempt > 0) {
                        Log.i(TAG, "✅ DoH fallback successful (retry ${attempt + 1}) for $hostname (${System.currentTimeMillis() - startTime}ms)")
                    } else {
                        Log.i(TAG, "✅ DoH fallback successful for $hostname (${System.currentTimeMillis() - startTime}ms)")
                    }
                    return@withContext responseData
                }
            } catch (e: Exception) {
                if (attempt < dohTimeouts.size - 1) {
                    Log.d(TAG, "⚠️ DoH fallback attempt ${attempt + 1} failed for $hostname: ${e.message}, retrying...")
                } else {
                    Log.w(TAG, "❌ DoH fallback failed after ${dohTimeouts.size} attempts for $hostname: ${e.message}")
                }
            }
        }

        // Tüm DoH provider'ları başarısız - sistem DNS'yi son çare olarak dene
        Log.d(TAG, "🔄 All DoH providers failed, trying system DNS as last resort...")
        return trySystemDnsFallback(hostname)
    }
}

/**
 * Sistem DNS fallback (son çare)
 */
private suspend fun trySystemDnsFallback(hostname: String): ByteArray? {
    return withContext(Dispatchers.IO) {
        val fallbackTimeouts = listOf(2000L, 3000L, 5000L)

        for ((attempt, timeoutMs) in fallbackTimeouts.withIndex()) {
            try {
                val addresses = withTimeoutOrNull(timeoutMs) {
                    InetAddress.getAllByName(hostname)
                }

                if (addresses != null && addresses.isNotEmpty()) {
                    val queryData = buildDnsQuery(hostname) ?: return@withContext null
                    val responseData = buildDnsResponse(queryData, addresses.toList())
                    DnsCacheManager.saveToCache(hostname, addresses.toList(), ttl = 3600L)
                    Log.i(TAG, "✅ System DNS fallback successful for $hostname")
                    return@withContext responseData
                }
            } catch (e: Exception) {
                // Retry...
            }
        }
        null
    }
}

/**
 * DoH provider oluştur
 */
private fun createDoHProvider(url: String): DnsOverHttps {
    val dnsClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .connectionPool(
            okhttp3.ConnectionPool(
                maxIdleConnections = 10,
                keepAliveDuration = 5,
                timeUnit = TimeUnit.MINUTES
            )
        )
        .build()

    return DnsOverHttps.Builder()
        .client(dnsClient)
        .url(url.toHttpUrl())
        .includeIPv6(true)
        .build()
}
```

### 2. DoH Provider Paylaşımı (Orta Öncelik)

**Öneri:** `HttpClientFactory`'deki DoH provider'larını `SystemDnsCacheServer` ile paylaş

**Yaklaşım:**

- DoH provider'larını singleton olarak yönet
- Hem `HttpClientFactory` hem de `SystemDnsCacheServer` aynı provider'ları kullansın
- Kaynak kullanımını optimize et

### 3. DoH Provider Health Check (Düşük Öncelik)

**Öneri:** DoH provider'larının sağlığını izle

**Uygulama:**

- Her provider için success/failure sayısını takip et
- Unhealthy provider'ları geçici olarak devre dışı bırak
- Recovery mekanizması ekle

---

## 📊 Mevcut vs Önerilen Karşılaştırma

| Özellik                 | Mevcut                       | Önerilen                                  |
| ----------------------- | ---------------------------- | ----------------------------------------- |
| **DoH Kullanımı**       | ❌ Hayır (sadece sistem DNS) | ✅ Evet (4 DoH provider)                  |
| **Paralel Sorgular**    | ❌ Hayır (sıralı)            | ✅ Evet (tüm provider'lar paralel)        |
| **Şifreleme**           | ❌ Hayır                     | ✅ Evet (HTTPS üzerinden)                 |
| **Gizlilik**            | ⚠️ Kısmi (sistem DNS)        | ✅ Tam (DoH)                              |
| **Engelleme Direnci**   | ⚠️ Düşük                     | ✅ Yüksek (4 farklı provider)             |
| **Fallback Stratejisi** | Sistem DNS → Başarısız       | DoH (4 provider) → Sistem DNS → Başarısız |

---

## 🔄 Yeni Çalışma Akışı (Önerilen)

```
┌─────────────────────────────────────┐
│ UDP DNS Sorguları (3 deneme)       │
│ - 200ms, 350ms, 600ms timeout      │
└──────────────┬──────────────────────┘
               │
               ▼
        [Başarısız mı?]
               │
               ├─── Başarılı ──► [DNS Response Döndür]
               │
               ▼ Başarısız
┌─────────────────────────────────────┐
│ tryDoHFallback() - Gerçek DoH       │
│ 4 DoH Provider Paralel:              │
│ - Cloudflare DoH                     │
│ - Google DoH                         │
│ - Quad9 DoH                          │
│ - OpenDNS DoH                        │
└──────────────┬──────────────────────┘
               │
               ▼
        [Başarılı mı?]
               │
               ├─── Başarılı ──► [DNS Response + Cache]
               │
               ▼ Başarısız
┌─────────────────────────────────────┐
│ trySystemDnsFallback()              │
│ Sistem DNS (son çare)                │
│ - 3 deneme: 2000ms, 3000ms, 5000ms  │
└──────────────┬──────────────────────┘
               │
               ▼
        [Başarılı mı?]
               │
               ├─── Başarılı ──► [DNS Response + Cache]
               │
               ▼ Başarısız
        [null döndür - DNS çözümleme başarısız]
```

---

## 📝 Sonuç

### Mevcut Durum

- ❌ DoH fallback gerçek DoH kullanmıyor
- ✅ Sistem DNS fallback çalışıyor
- ⚠️ UDP DNS engellendiğinde alternatif sınırlı

### Önerilen İyileştirme

- ✅ Gerçek DoH implementasyonu
- ✅ 4 DoH provider paralel sorgu
- ✅ Sistem DNS son çare olarak
- ✅ Daha yüksek engelleme direnci
- ✅ Daha iyi gizlilik ve şifreleme

### Öncelik

**Yüksek Öncelik:** Gerçek DoH fallback implementasyonu - UDP DNS engellendiğinde DoH alternatifi kritik öneme sahip.

---

**Not:** Bu analiz mevcut kod yapısına dayanmaktadır. İyileştirmeler uygulandığında DNS çözümleme başarı oranı ve gizlilik artacaktır.


