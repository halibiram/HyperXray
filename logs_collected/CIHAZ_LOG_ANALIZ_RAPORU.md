# Cihaz Log Analiz Raporu

**Tarih**: 27 Kasım 2024  
**Cihaz**: c49108  
**Durum**: 🔴 Kritik Sorunlar Tespit Edildi  
**Analiz Zamanı**: 23:12 - 23:24 (12 dakika)

---

## 📋 Özet

Cihazdan toplanan loglar analiz edildi. VPN tunnel başarıyla başlatılmış ancak **Xray-core ile iletişim kurulamıyor** ve **WireGuard handshake tamamlanamıyor**. Bu durum tunnel'ın çalışmasını engelliyor.

### 🔴 Kritik Sorunlar

1. **Xray-core gRPC Bağlantı Sorunu**: Xray-core ile iletişim kurulamıyor
2. **Pipe Kapatılma Sorunu**: XrayUDP pipe'ı kapanıyor (`io: read/write on closed pipe`)
3. **WireGuard Handshake Sorunu**: Handshake paketleri gönderiliyor ama yanıt alınamıyor
4. **Veri Alınamıyor**: `rxBytes: 0` - Hiç veri alınamıyor

---

## 🔍 Detaylı Analiz

### 1. Xray-core gRPC Bağlantı Sorunu

**Belirtiler:**

```
11-27 23:22:32.249 W/CoreStatsClient(21363): GetSysStats RPC unavailable - Xray-core may not be ready
11-27 23:22:32.250 W/XrayStatsManager(21363): Stats query failed (timeout/exception/disabled)
```

**Sıklık**: Sürekli tekrarlanıyor (her 2 saniyede bir)

**Analiz:**

- XrayStatsManager sürekli yeni CoreStatsClient oluşturmaya çalışıyor
- Her denemede "GetSysStats RPC unavailable" hatası alınıyor
- Xray-core'un gRPC API'sine erişilemiyor
- Port: 65276 (apiPort)

**Olası Nedenler:**

1. Xray-core process çalışmıyor olabilir
2. Xray-core gRPC servisi başlatılmamış olabilir
3. Port yanlış yapılandırılmış olabilir
4. Firewall/network kısıtlaması olabilir

---

### 2. Pipe Kapatılma Sorunu

**Belirtiler:**

```
11-27 23:23:12.110 E/HyperXray-Bridge( 7518): [XrayUDP] Read error: io: read/write on closed pipe
11-27 23:23:16.381 E/HyperXray-Bridge( 7518): [XrayUDP] Write error: io: read/write on closed pipe
11-27 23:23:16.381 E/HyperXray-Bridge( 7518): [XrayBind] Send error: io: read/write on closed pipe
11-27 23:23:16.381 E/HyperXray-Bridge( 7518): [WireGuard] peer(bmXO…fgyo) - Failed to send handshake initiation: io: read/write on closed pipe
```

**Zaman Çizelgesi:**

- `23:22:25` - İlk başarılı paket gönderimi
- `23:23:12` - İlk pipe kapatılma hatası (Read error)
- `23:23:16` - İlk pipe kapatılma hatası (Write error)
- `23:23:16` - Sonraki tüm gönderimler başarısız

**Analiz:**

- XrayUDP pipe'ı yaklaşık 47 saniye sonra kapanıyor
- Pipe kapandıktan sonra tüm gönderimler başarısız oluyor
- WireGuard handshake paketleri gönderilemiyor
- Pipe kapatılma nedeni belirsiz

**Olası Nedenler:**

1. Xray-core process çökmüş olabilir
2. Xray-core UDP handler'ı kapanmış olabilir
3. Process lifecycle yönetimi sorunu olabilir
4. Memory/resource yetersizliği olabilir

---

### 3. WireGuard Handshake Sorunu

**Belirtiler:**

```
11-27 23:22:30.561 D/HyperXray-Bridge( 7518): [WireGuard] peer(bmXO…fgyo) - Handshake did not complete after 5 seconds, retrying (try 2)
11-27 23:22:30.561 D/HyperXray-Bridge( 7518): [WireGuard] peer(bmXO…fgyo) - Sending handshake initiation
```

**Sıklık**: Her 5 saniyede bir retry

**Tunnel Stats:**

```json
{
  "connected": true,
  "txBytes": 2516,
  "rxBytes": 0, // ← Hiç veri alınamıyor!
  "txPackets": 17,
  "rxPackets": 0, // ← Hiç paket alınamıyor!
  "lastHandshake": 0, // ← Handshake tamamlanmamış!
  "endpoint": "162.159.192.1:2408",
  "uptime": 130001
}
```

**Analiz:**

- Handshake paketleri gönderiliyor (17 paket)
- Ancak hiç yanıt alınamıyor
- `rxBytes: 0` - Hiç veri alınamıyor
- `lastHandshake: 0` - Handshake hiç tamamlanmamış

**Olası Nedenler:**

1. Xray-core UDP trafiği işlemiyor
2. Xray-core'dan gelen paketler pipe'a ulaşmıyor
3. Network routing sorunu
4. Xray-core yapılandırması yanlış

---

### 4. Tunnel Durumu

**Başarılı Adımlar:**

- ✅ Tunnel başarıyla başlatılmış (`connected: true`)
- ✅ TUN interface oluşturulmuş
- ✅ WireGuard device oluşturulmuş
- ✅ WireGuard Up() başarılı
- ✅ Handshake paketleri gönderiliyor

**Başarısız Adımlar:**

- ❌ Xray-core ile iletişim kurulamıyor
- ❌ Pipe kapatılıyor
- ❌ Handshake yanıtı alınamıyor
- ❌ Hiç veri alınamıyor

---

## 📊 İstatistikler

### Tunnel Stats (130 saniye sonra)

- **Uptime**: 130 saniye
- **TX Bytes**: 2516 bytes
- **RX Bytes**: 0 bytes ❌
- **TX Packets**: 17 paket
- **RX Packets**: 0 paket ❌
- **Last Handshake**: 0 (tamamlanmamış) ❌

### Hata İstatistikleri

- **Xray-core gRPC hataları**: ~60+ (her 2 saniyede bir)
- **Pipe kapatılma hataları**: ~30+ (23:23:12'den sonra)
- **Handshake retry**: ~26+ (her 5 saniyede bir)

---

## 🔬 Kök Neden Analizi

### Senaryo 1: Xray-core Process Çökmüş

**Belirtiler:**

- gRPC bağlantısı kurulamıyor
- Pipe kapatılıyor
- Hiç veri alınamıyor

**Kontrol:**

```bash
adb shell ps | grep xray
adb shell logcat | grep -i "xray.*crash\|xray.*died\|xray.*fatal"
```

### Senaryo 2: Xray-core gRPC Servisi Başlatılmamış

**Belirtiler:**

- gRPC bağlantısı kurulamıyor
- "GetSysStats RPC unavailable" hatası

**Kontrol:**

- Xray-core config dosyasında gRPC servisi yapılandırılmış mı?
- Port 65276 doğru mu?
- gRPC servisi başlatılıyor mu?

### Senaryo 3: Xray-core UDP Handler Sorunu

**Belirtiler:**

- Pipe kapatılıyor
- UDP paketleri işlenmiyor
- Handshake yanıtı alınamıyor

**Kontrol:**

- Xray-core config dosyasında UDP handler yapılandırılmış mı?
- UDP routing doğru mu?

---

## 💡 Çözüm Önerileri

### 1. Xray-core Process Kontrolü

**Acil:**

```bash
# Xray-core process'ini kontrol et
adb shell ps | grep xray

# Xray-core loglarını kontrol et
adb logcat | grep -i xray
```

**Kod:**

- Xray-core process lifecycle'ını kontrol et
- Process crash durumunda restart mekanizması ekle
- Process health check ekle

### 2. gRPC Bağlantı Kontrolü

**Acil:**

- Xray-core config dosyasında gRPC servisi yapılandırıldığından emin ol
- Port 65276'nın doğru olduğunu kontrol et
- gRPC servisinin başlatıldığını doğrula

**Kod:**

- gRPC bağlantı health check ekle
- Bağlantı kesildiğinde otomatik reconnect mekanizması ekle
- Bağlantı durumunu logla

### 3. Pipe Kapatılma Sorunu

**Acil:**

- XrayUDP pipe'ının neden kapandığını araştır
- Pipe kapatılma durumunda otomatik reconnect mekanizması ekle
- Pipe durumunu sürekli monitor et

**Kod:**

- Pipe kapatılma durumunu detect et
- Pipe'ı otomatik olarak yeniden aç
- Pipe durumunu logla

### 4. WireGuard Handshake Sorunu

**Acil:**

- Xray-core'un UDP trafiğini işlediğinden emin ol
- Network routing'i kontrol et
- Xray-core config dosyasını kontrol et

**Kod:**

- Handshake timeout değerlerini ayarla
- Handshake retry mekanizmasını iyileştir
- Handshake durumunu logla

---

## 📝 Sonraki Adımlar

### Öncelik 1: Xray-core Process Kontrolü

1. ✅ Xray-core process'ini kontrol et
2. ✅ Xray-core loglarını incele
3. ✅ Process crash durumunu kontrol et

### Öncelik 2: gRPC Bağlantı Kontrolü

1. ✅ Xray-core config dosyasını kontrol et
2. ✅ gRPC servisinin başlatıldığını doğrula
3. ✅ Port yapılandırmasını kontrol et

### Öncelik 3: Pipe Kapatılma Sorunu

1. ✅ Pipe kapatılma nedenini araştır
2. ✅ Otomatik reconnect mekanizması ekle
3. ✅ Pipe durumunu monitor et

### Öncelik 4: WireGuard Handshake Sorunu

1. ✅ Xray-core UDP handler'ını kontrol et
2. ✅ Network routing'i kontrol et
3. ✅ Handshake timeout değerlerini ayarla

---

## 🔗 İlgili Dosyalar

- `native/bridge/bind.go` - XrayBind implementasyonu
- `native/bridge/xray.go` - Xray-core entegrasyonu
- `app/src/main/kotlin/com/hyperxray/an/core/monitor/XrayStatsManager.kt` - gRPC istatistik yönetimi
- `app/src/main/kotlin/com/hyperxray/an/service/managers/XrayCoreManager.kt` - Xray-core yönetimi

---

## 📌 Notlar

- Tunnel başarıyla başlatılıyor ancak Xray-core ile iletişim kurulamıyor
- Pipe kapatılma sorunu tunnel'ın çalışmasını engelliyor
- Handshake tamamlanamıyor çünkü yanıt alınamıyor
- Xray-core process durumu kontrol edilmeli

---

**Rapor Oluşturulma Tarihi**: 27 Kasım 2024  
**Son Güncelleme**: 27 Kasım 2024  
**Durum**: 🔴 Kritik Sorunlar Tespit Edildi - Acil Müdahale Gerekli



