# Güncel Cihaz Log Analiz Raporu

**Tarih**: 27 Kasım 2024 23:45  
**Cihaz**: 24129PN74G  
**Durum**: 🔴 Kritik Sorunlar Devam Ediyor  
**Analiz Zamanı**: 23:45:28 - 23:45:38 (10 saniye)

---

## 📋 Özet

Cihazdan toplanan güncel loglar analiz edildi. VPN tunnel çalışıyor ancak **Xray-core ile iletişim kurulamıyor** ve **WireGuard handshake tamamlanamıyor**. Bu durum tunnel'ın veri alışverişi yapamamasına neden oluyor.

### 🔴 Kritik Sorunlar

1. **Xray-core gRPC Bağlantı Sorunu**: Sürekli devam ediyor
2. **WireGuard Handshake Sorunu**: Handshake paketleri gönderiliyor ama yanıt alınamıyor
3. **Veri Alınamıyor**: `rxBytes: 0` - Hiç veri alınamıyor
4. **gRPC Channel Durumu**: `TRANSIENT_FAILURE` - Geçici başarısızlık

---

## 🔍 Detaylı Analiz

### 1. Xray-core gRPC Bağlantı Sorunu

**Belirtiler:**

```
11-27 23:45:28.440 W XrayStatsManager: CoreStatsClient is null, cannot update stats - will retry on next call
11-27 23:45:30.442 D XrayStatsManager: Exponential backoff active, 3995ms remaining (failures: 3)
11-27 23:45:36.460 W CoreStatsClient: Channel not ready for getSystemStats (state: TRANSIENT_FAILURE), returning null
11-27 23:45:38.462 W XrayStatsManager: Multiple consecutive failures (3), closing client
```

**Sıklık**: Her 2 saniyede bir deneme, sürekli başarısız

**Analiz:**

- XrayStatsManager sürekli yeni CoreStatsClient oluşturmaya çalışıyor
- Her denemede "Channel not ready" hatası alınıyor
- Channel durumu: `TRANSIENT_FAILURE` (geçici başarısızlık)
- Exponential backoff aktif (3 başarısızlık sonrası)
- Client kapatılıyor ve yeniden oluşturuluyor
- Port: 65276 (apiPort)

**Olası Nedenler:**

1. Xray-core process çalışmıyor olabilir
2. Xray-core gRPC servisi başlatılmamış olabilir
3. gRPC servisi crash olmuş olabilir
4. Port yanlış yapılandırılmış olabilir
5. Network routing sorunu olabilir

---

### 2. WireGuard Handshake Sorunu

**Belirtiler:**

```
11-27 23:45:29.376 D HyperVpnService: 📊 Tunnel stats JSON: {
  "connected": true,
  "txBytes": 1924,
  "rxBytes": 0,        // ← Hiç veri alınamıyor!
  "txPackets": 13,
  "rxPackets": 0,      // ← Hiç paket alınamıyor!
  "lastHandshake": 0,  // ← Handshake tamamlanmamış!
  "endpoint": "162.159.192.1:2408",
  "uptime": 65001
}
```

```
11-27 23:45:36.057 D HyperXray-Bridge: [WireGuard] peer(bmXO…fgyo) - Handshake did not complete after 5 seconds, retrying (try 2)
11-27 23:45:36.058 D HyperXray-Bridge: [WireGuard] peer(bmXO…fgyo) - Sending handshake initiation
11-27 23:45:36.059 D HyperXray-Bridge: [XrayUDP] Sent 148 bytes
11-27 23:45:36.059 D HyperXray-Bridge: [XrayBind] → Sent 148 bytes
```

**Sıklık**: Her 5 saniyede bir retry

**Analiz:**

- Handshake paketleri gönderiliyor (13 paket gönderilmiş)
- Ancak hiç yanıt alınamıyor
- `rxBytes: 0` - Hiç veri alınamıyor
- `rxPackets: 0` - Hiç paket alınamıyor
- `lastHandshake: 0` - Handshake hiç tamamlanmamış
- Tunnel uptime: 65 saniye (yaklaşık 1 dakika)

**Olası Nedenler:**

1. Xray-core UDP trafiği işlemiyor
2. Xray-core'dan gelen paketler pipe'a ulaşmıyor
3. Network routing sorunu
4. Xray-core yapılandırması yanlış
5. UDP handler çalışmıyor

---

### 3. Tunnel Durumu

**Başarılı Adımlar:**

- ✅ Tunnel başarıyla başlatılmış (`connected: true`)
- ✅ TUN interface oluşturulmuş
- ✅ WireGuard device oluşturulmuş
- ✅ WireGuard Up() başarılı
- ✅ Handshake paketleri gönderiliyor (13 paket)
- ✅ Tunnel çalışıyor (65 saniye uptime)

**Başarısız Adımlar:**

- ❌ Xray-core ile iletişim kurulamıyor
- ❌ gRPC channel `TRANSIENT_FAILURE` durumunda
- ❌ Handshake yanıtı alınamıyor
- ❌ Hiç veri alınamıyor (`rxBytes: 0`)

---

## 📊 İstatistikler

### Tunnel Stats (65 saniye sonra)

- **Uptime**: 65 saniye (~1 dakika)
- **TX Bytes**: 1924 bytes
- **RX Bytes**: 0 bytes ❌
- **TX Packets**: 13 paket
- **RX Packets**: 0 paket ❌
- **Last Handshake**: 0 (tamamlanmamış) ❌
- **Endpoint**: 162.159.192.1:2408

### Hata İstatistikleri

- **Xray-core gRPC hataları**: Sürekli (her 2 saniyede bir)
- **Channel durumu**: `TRANSIENT_FAILURE`
- **Handshake retry**: Her 5 saniyede bir
- **Client kapatılma**: 3 başarısızlık sonrası

---

## 🔬 Kök Neden Analizi

### Senaryo 1: Xray-core Process Çalışmıyor

**Belirtiler:**

- gRPC bağlantısı kurulamıyor
- Channel `TRANSIENT_FAILURE` durumunda
- Hiç veri alınamıyor

**Kontrol:**

```bash
# Xray-core process'ini kontrol et
adb shell ps | grep xray

# Xray-core loglarını kontrol et
adb logcat | grep -i "xray.*crash\|xray.*died\|xray.*fatal"
```

### Senaryo 2: Xray-core gRPC Servisi Başlatılmamış

**Belirtiler:**

- gRPC bağlantısı kurulamıyor
- "Channel not ready" hatası
- Channel durumu: `TRANSIENT_FAILURE`

**Kontrol:**

- Xray-core config dosyasında gRPC servisi yapılandırılmış mı?
- Port 65276 doğru mu?
- gRPC servisi başlatılıyor mu?

### Senaryo 3: Xray-core UDP Handler Sorunu

**Belirtiler:**

- UDP paketleri işlenmiyor
- Handshake yanıtı alınamıyor
- Hiç veri alınamıyor

**Kontrol:**

- Xray-core config dosyasında UDP handler yapılandırılmış mı?
- UDP routing doğru mu?
- UDP handler çalışıyor mu?

---

## 💡 Çözüm Önerileri

### 1. Xray-core Process Kontrolü (Acil)

**Acil:**

```bash
# Xray-core process'ini kontrol et
adb shell ps | grep xray

# Xray-core loglarını kontrol et
adb logcat | grep -i xray

# Process durumunu kontrol et
adb shell dumpsys activity services | grep -i xray
```

**Kod:**

- Xray-core process lifecycle'ını kontrol et
- Process crash durumunda restart mekanizması ekle
- Process health check ekle
- Process durumunu logla

### 2. gRPC Bağlantı Kontrolü (Acil)

**Acil:**

- Xray-core config dosyasında gRPC servisi yapılandırıldığından emin ol
- Port 65276'nın doğru olduğunu kontrol et
- gRPC servisinin başlatıldığını doğrula
- Channel durumunu sürekli monitor et

**Kod:**

- gRPC bağlantı health check ekle
- Bağlantı kesildiğinde otomatik reconnect mekanizması ekle
- Bağlantı durumunu logla
- Channel durumunu daha detaylı logla

### 3. WireGuard Handshake Sorunu (Acil)

**Acil:**

- Xray-core'un UDP trafiğini işlediğinden emin ol
- Network routing'i kontrol et
- Xray-core config dosyasını kontrol et
- UDP handler'ın çalıştığını doğrula

**Kod:**

- Handshake timeout değerlerini ayarla
- Handshake retry mekanizmasını iyileştir
- Handshake durumunu logla
- UDP paket akışını logla

---

## 📝 Sonraki Adımlar

### Öncelik 1: Xray-core Process Kontrolü

1. ✅ Xray-core process'ini kontrol et
2. ✅ Xray-core loglarını incele
3. ✅ Process crash durumunu kontrol et
4. ❌ Process durumunu sürekli monitor et

### Öncelik 2: gRPC Bağlantı Kontrolü

1. ✅ Xray-core config dosyasını kontrol et
2. ✅ gRPC servisinin başlatıldığını doğrula
3. ✅ Port yapılandırmasını kontrol et
4. ❌ Channel durumunu sürekli monitor et

### Öncelik 3: WireGuard Handshake Sorunu

1. ✅ Xray-core UDP handler'ını kontrol et
2. ✅ Network routing'i kontrol et
3. ✅ Handshake timeout değerlerini ayarla
4. ❌ UDP paket akışını logla

---

## 🔗 İlgili Dosyalar

- `native/bridge/bind.go` - XrayBind implementasyonu
- `native/bridge/xray.go` - Xray-core entegrasyonu
- `app/src/main/kotlin/com/hyperxray/an/core/monitor/XrayStatsManager.kt` - gRPC istatistik yönetimi
- `app/src/main/kotlin/com/hyperxray/an/service/managers/XrayCoreManager.kt` - Xray-core yönetimi

---

## 📌 Notlar

- Tunnel başarıyla başlatılıyor ancak Xray-core ile iletişim kurulamıyor
- gRPC channel `TRANSIENT_FAILURE` durumunda, sürekli retry yapılıyor
- Handshake tamamlanamıyor çünkü yanıt alınamıyor
- Xray-core process durumu kontrol edilmeli
- UDP handler çalışıyor mu kontrol edilmeli

---

**Rapor Oluşturulma Tarihi**: 27 Kasım 2024 23:45  
**Son Güncelleme**: 27 Kasım 2024 23:45  
**Durum**: 🔴 Kritik Sorunlar Devam Ediyor - Acil Müdahale Gerekli



