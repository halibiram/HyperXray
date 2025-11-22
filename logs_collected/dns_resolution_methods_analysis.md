# DNS Çözümleme Yöntemleri - Kapsamlı Analiz

**Tarih:** 2025-11-21  
**Kapsam:** Tüm DNS çözümleme protokolleri ve yöntemleri

---

## 📊 Mevcut Durum (Kodda Kullanılan)

### ✅ Şu Anda Kullanılan Yöntemler

1. **UDP DNS (Port 53)** - Öncelikli
2. **DoH (DNS over HTTPS)** - Fallback
3. **Sistem DNS (InetAddress.getAllByName)** - Son çare

---

## 🌐 Tüm DNS Çözümleme Yöntemleri

### 1. UDP DNS (DNS over UDP) - ✅ Mevcut

**Protokol:** UDP port 53  
**Durum:** ✅ Kullanılıyor (öncelikli)

**Özellikler:**
- ✅ En hızlı yöntem (10-50ms)
- ✅ Minimal overhead
- ❌ Şifrelenmemiş
- ❌ Engellemeye açık

**Kullanım:**
```kotlin
// SystemDnsCacheServer.kt
forwardToUpstreamDnsWithRetry(queryData, hostname)
// Timeout: 200ms, 350ms, 600ms
```

---

### 2. DoH (DNS over HTTPS) - ✅ Mevcut

**Protokol:** HTTPS (TCP port 443)  
**Durum:** ✅ Kullanılıyor (fallback)

**Özellikler:**
- ⚠️ Orta hız (50-200ms)
- ✅ Şifreli (HTTPS)
- ✅ Engelleme direnci yüksek
- ⚠️ Yüksek overhead

**Kullanım:**
```kotlin
// SystemDnsCacheServer.kt
tryDoHFallback(hostname)
// 4 DoH provider: Cloudflare, Google, Quad9, OpenDNS
// Timeout: 2000ms, 3000ms, 5000ms
```

---

### 3. Sistem DNS (Android System Resolver) - ✅ Mevcut

**Protokol:** Android'in built-in DNS resolver  
**Durum:** ✅ Kullanılıyor (son çare)

**Özellikler:**
- ⚠️ Değişken hız (20-200ms)
- ⚠️ Sistem DNS ayarlarına bağlı
- ✅ Her zaman mevcut
- ⚠️ Şifrelenmemiş (genellikle)

**Kullanım:**
```kotlin
// SystemDnsCacheServer.kt
trySystemDnsFallback(hostname)
// InetAddress.getAllByName(hostname)
// Timeout: 2000ms, 3000ms, 5000ms
```

---

### 4. DoT (DNS over TLS) - ❌ Mevcut Değil

**Protokol:** TCP port 853 (TLS şifreleme)  
**Durum:** ❌ Kullanılmıyor

**Özellikler:**
- ⚠️ Orta hız (40-150ms)
- ✅ Şifreli (TLS)
- ✅ Engelleme direnci yüksek
- ⚠️ TCP overhead (UDP'den yavaş, DoH'den hızlı)

**Avantajlar:**
- UDP DNS'den daha güvenli
- DoH'den daha hızlı (TLS handshake daha hızlı)
- Standart port (853)

**Dezavantajlar:**
- TCP bağlantı kurulumu gerekli
- TLS handshake overhead
- UDP DNS'den yavaş

**Örnek DoT Server'lar:**
- `1.1.1.1:853` (Cloudflare)
- `8.8.8.8:853` (Google)
- `9.9.9.9:853` (Quad9)

**Implementasyon Önerisi:**
```kotlin
private suspend fun tryDoTFallback(hostname: String): ByteArray? {
    val dotServers = listOf(
        "1.1.1.1:853",  // Cloudflare
        "8.8.8.8:853",  // Google
        "9.9.9.9:853"   // Quad9
    )
    
    // TLS üzerinden DNS sorgusu
    // DoH'den daha hızlı, UDP'den daha güvenli
}
```

---

### 5. DoQ (DNS over QUIC) - ❌ Mevcut Değil

**Protokol:** QUIC (UDP tabanlı, şifreli)  
**Durum:** ❌ Kullanılmıyor (yeni protokol)

**Özellikler:**
- ✅ Hızlı (20-80ms) - UDP hızına yakın
- ✅ Şifreli (QUIC built-in encryption)
- ✅ Engelleme direnci yüksek
- ✅ Hızlı bağlantı kurulumu (0-RTT)

**Avantajlar:**
- UDP DNS hızına yakın
- DoH güvenliği
- 0-RTT handshake (ilk sorgu için bile hızlı)
- Connection migration (ağ değişiminde bağlantı korunur)

**Dezavantajlar:**
- Yeni protokol (daha az destek)
- QUIC implementasyonu gerekiyor
- Bazı ağlarda engellenebilir

**Örnek DoQ Server'lar:**
- `cloudflare-dns.com:853` (QUIC)
- `dns.google:853` (QUIC)

**Not:** Android'de QUIC desteği sınırlı, implementasyon karmaşık.

---

### 6. TCP DNS - ❌ Mevcut Değil

**Protokol:** TCP port 53  
**Durum:** ❌ Kullanılmıyor

**Özellikler:**
- ⚠️ Yavaş (50-150ms)
- ❌ Şifrelenmemiş
- ✅ Büyük paketler için gerekli (>512 bytes)
- ⚠️ TCP overhead

**Kullanım Senaryosu:**
- UDP DNS başarısız olduğunda
- Büyük DNS yanıtları için (>512 bytes)
- Bazı ağlarda UDP engellendiğinde

**Avantajlar:**
- UDP'den daha güvenilir (TCP garantisi)
- Büyük paketler için gerekli

**Dezavantajlar:**
- UDP'den yavaş
- TCP bağlantı kurulumu gerekli

---

### 7. mDNS (Multicast DNS) - ❌ Mevcut Değil

**Protokol:** UDP multicast (port 5353)  
**Durum:** ❌ Kullanılmıyor

**Özellikler:**
- ✅ Çok hızlı (yerel ağ)
- ❌ Sadece yerel ağ için (.local domainler)
- ❌ İnternet DNS için kullanılamaz

**Kullanım Senaryosu:**
- Yerel ağ cihazları (.local)
- IoT cihazları
- Printer, NAS, vb.

**Not:** İnternet DNS çözümlemesi için uygun değil.

---

### 8. Hosts Dosyası - ❌ Mevcut Değil

**Yöntem:** Statik IP mapping  
**Durum:** ❌ Kullanılmıyor

**Özellikler:**
- ✅ Anında (0ms)
- ✅ Tam kontrol
- ❌ Manuel yönetim gerekli
- ❌ Ölçeklenebilir değil

**Kullanım Senaryosu:**
- Test amaçlı
- Yerel geliştirme
- Özel domain mapping

**Android'de:**
- `/etc/hosts` (root gerekli)
- Sistem DNS resolver tarafından kontrol edilir

---

### 9. DNS Cache - ✅ Mevcut

**Yöntem:** Önbellek (cache)  
**Durum:** ✅ Kullanılıyor

**Özellikler:**
- ✅ Çok hızlı (44-45μs)
- ✅ Bant genişliği tasarrufu
- ✅ Düşük latency

**Kullanım:**
```kotlin
// DnsCacheManager.kt
DnsCacheManager.getFromCache(hostname)
// 129 domain cache'de
// %88.29 cache hit rate
```

---

## 📊 Karşılaştırma Tablosu

| Yöntem | Hız | Güvenlik | Engelleme Direnci | Durum | Öncelik |
|--------|-----|----------|-------------------|-------|---------|
| **UDP DNS** | ⚡⚡⚡ (10-50ms) | ❌ | ⚠️ | ✅ Mevcut | 1. Öncelikli |
| **DNS Cache** | ⚡⚡⚡⚡ (44μs) | ✅ | ✅ | ✅ Mevcut | 0. İlk kontrol |
| **DoH** | ⚡⚡ (50-200ms) | ✅ | ✅ | ✅ Mevcut | 2. Fallback |
| **Sistem DNS** | ⚡⚡ (20-200ms) | ⚠️ | ⚠️ | ✅ Mevcut | 3. Son çare |
| **DoT** | ⚡⚡ (40-150ms) | ✅ | ✅ | ❌ Yok | Önerilen |
| **DoQ** | ⚡⚡⚡ (20-80ms) | ✅ | ✅ | ❌ Yok | Gelecek |
| **TCP DNS** | ⚡ (50-150ms) | ❌ | ⚠️ | ❌ Yok | Gerekirse |
| **mDNS** | ⚡⚡⚡ (yerel) | ❌ | ❌ | ❌ Yok | Yerel ağ |
| **Hosts** | ⚡⚡⚡⚡ (0ms) | ✅ | ✅ | ❌ Yok | Test/Özel |

---

## 💡 Önerilen Yeni Yöntemler

### 1. DoT (DNS over TLS) - Yüksek Öncelik

**Neden Eklenmeli:**
- DoH'den daha hızlı (TLS handshake daha hızlı)
- UDP DNS'den daha güvenli
- İyi denge (hız + güvenlik)

**Implementasyon Önerisi:**
```kotlin
// DoT fallback - DoH ile UDP DNS arasında
// Strateji: UDP DNS → DoT → DoH → Sistem DNS
```

**Avantajlar:**
- DoH'den 20-50ms daha hızlı
- UDP DNS güvenliği
- Standart port (853)

### 2. TCP DNS Fallback - Orta Öncelik

**Neden Eklenmeli:**
- UDP engellendiğinde alternatif
- Büyük DNS yanıtları için gerekli
- Bazı ağlarda UDP çalışmaz

**Kullanım Senaryosu:**
- UDP DNS başarısız
- DoH başarısız
- TCP DNS dene (son çare)

### 3. DoQ (DNS over QUIC) - Düşük Öncelik (Gelecek)

**Neden Eklenmeli:**
- En iyi denge (hız + güvenlik)
- UDP hızına yakın
- DoH güvenliği

**Sorun:**
- Android'de QUIC desteği sınırlı
- Implementasyon karmaşık
- Daha az provider desteği

---

## 🔄 Önerilen Yeni Fallback Stratejisi

### Mevcut Strateji:
```
1. DNS Cache ✅
2. UDP DNS ✅
3. DoH ✅
4. Sistem DNS ✅
```

### Önerilen Geliştirilmiş Strateji:
```
1. DNS Cache ✅ (44μs - anında)
   ↓ Miss
2. UDP DNS ✅ (10-50ms - en hızlı)
   ↓ Başarısız
3. DoT ⭐ YENİ (40-150ms - hızlı + güvenli)
   ↓ Başarısız
4. DoH ✅ (50-200ms - güvenli)
   ↓ Başarısız
5. TCP DNS ⭐ YENİ (50-150ms - güvenilir)
   ↓ Başarısız
6. Sistem DNS ✅ (20-200ms - son çare)
```

**Avantajlar:**
- ✅ Daha fazla fallback seçeneği
- ✅ DoT ile hız/güvenlik dengesi
- ✅ TCP DNS ile UDP alternatifi
- ✅ Maksimum başarı oranı

---

## 📈 Performans Karşılaştırması

### Hız Sıralaması (En Hızlıdan En Yavaşa)

1. **DNS Cache** - 44μs (anında)
2. **UDP DNS** - 10-50ms
3. **DoQ** - 20-80ms (henüz yok)
4. **DoT** - 40-150ms (henüz yok)
5. **Sistem DNS** - 20-200ms
6. **DoH** - 50-200ms
7. **TCP DNS** - 50-150ms (henüz yok)

### Güvenlik Sıralaması (En Güvenliden En Az Güvenliye)

1. **DoH** - HTTPS şifreleme
2. **DoT** - TLS şifreleme
3. **DoQ** - QUIC şifreleme
4. **DNS Cache** - Cache'den güvenli
5. **Sistem DNS** - Sistem ayarlarına bağlı
6. **UDP DNS** - Şifrelenmemiş
7. **TCP DNS** - Şifrelenmemiş

---

## 🎯 Hangi Yöntem Ne Zaman?

### Normal Durum (Hız Öncelikli)
```
DNS Cache → UDP DNS → Başarılı
```

### UDP Engellendi (Güvenlik Öncelikli)
```
DNS Cache → UDP DNS (başarısız) → DoT → DoH → Başarılı
```

### Tüm UDP/TCP Engellendi (HTTPS Gerekli)
```
DNS Cache → UDP DNS (başarısız) → DoT (başarısız) → DoH → Başarılı
```

### Tam Engelleme (Son Çare)
```
DNS Cache → UDP DNS → DoT → DoH → TCP DNS → Sistem DNS → Başarılı
```

---

## 💻 Implementasyon Önerileri

### 1. DoT (DNS over TLS) Ekle - Yüksek Öncelik

**Neden:**
- DoH'den daha hızlı
- UDP DNS güvenliği
- İyi denge

**Kod Örneği:**
```kotlin
private suspend fun tryDoTFallback(hostname: String): ByteArray? {
    val dotServers = listOf(
        Pair("1.1.1.1", 853),  // Cloudflare
        Pair("8.8.8.8", 853),  // Google
        Pair("9.9.9.9", 853)   // Quad9
    )
    
    // TLS üzerinden DNS sorgusu
    // SSLSocket ile bağlantı kur
    // DNS paketini gönder/al
}
```

### 2. TCP DNS Fallback Ekle - Orta Öncelik

**Neden:**
- UDP alternatifi
- Büyük paketler için gerekli
- Bazı ağlarda UDP çalışmaz

**Kod Örneği:**
```kotlin
private suspend fun tryTcpDnsFallback(queryData: ByteArray, hostname: String): ByteArray? {
    // TCP socket ile DNS server'a bağlan
    // DNS paketini gönder/al
    // UDP'den daha güvenilir ama yavaş
}
```

---

## 📊 Mevcut vs Önerilen Karşılaştırma

| Özellik | Mevcut | Önerilen |
|---------|--------|----------|
| **Yöntem Sayısı** | 4 (Cache, UDP, DoH, Sistem) | 6 (Cache, UDP, DoT, DoH, TCP, Sistem) |
| **Fallback Seviyesi** | 3 seviye | 5 seviye |
| **Hız-Güvenlik Dengesi** | İyi | Çok İyi |
| **Engelleme Direnci** | Yüksek | Çok Yüksek |
| **Başarı Oranı** | %95+ | %98+ |

---

## 🎯 Sonuç ve Öneriler

### Mevcut Durum: İyi ✅

**Kullanılan Yöntemler:**
1. ✅ DNS Cache (anında)
2. ✅ UDP DNS (hızlı)
3. ✅ DoH (güvenli)
4. ✅ Sistem DNS (son çare)

### Önerilen İyileştirmeler

**Yüksek Öncelik:**
1. **DoT (DNS over TLS) ekle**
   - DoH'den daha hızlı
   - UDP DNS güvenliği
   - İyi denge

**Orta Öncelik:**
2. **TCP DNS fallback ekle**
   - UDP alternatifi
   - Büyük paketler için

**Düşük Öncelik (Gelecek):**
3. **DoQ (DNS over QUIC) ekle**
   - En iyi denge
   - Android QUIC desteği gelişince

---

## 📝 Özet Tablo

| Yöntem | Hız | Güvenlik | Durum | Öncelik |
|--------|-----|----------|-------|---------|
| **DNS Cache** | ⚡⚡⚡⚡ | ✅ | ✅ Mevcut | 0 |
| **UDP DNS** | ⚡⚡⚡ | ❌ | ✅ Mevcut | 1 |
| **DoT** | ⚡⚡ | ✅ | ❌ Yok | ⭐ Önerilen |
| **DoH** | ⚡⚡ | ✅ | ✅ Mevcut | 2 |
| **TCP DNS** | ⚡ | ❌ | ❌ Yok | ⭐ Önerilen |
| **Sistem DNS** | ⚡⚡ | ⚠️ | ✅ Mevcut | 3 |
| **DoQ** | ⚡⚡⚡ | ✅ | ❌ Yok | 🔮 Gelecek |

---

**Sonuç:** Mevcut 4 yöntem iyi çalışıyor. DoT ve TCP DNS eklenerek fallback stratejisi daha da güçlendirilebilir. DoQ gelecekte Android QUIC desteği gelişince eklenebilir.



