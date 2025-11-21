# Log Toplama ve Raporlama Aracı

Bu araç, HyperXray uygulamasının loglarını toplar, analiz eder ve detaylı bir rapor oluşturur.

## Gereksinimler

- Python 3.6+
- Android SDK (ADB)
- Bağlı Android cihaz veya emülatör

## Kullanım

```bash
python tools/collect_logs.py
```

## Toplanan Loglar

Script aşağıdaki log dosyalarını toplar:

1. **Ana Uygulama Logu** (`app_log.txt`)

   - Konum: `/data/data/com.hyperxray.an/files/app_log.txt`
   - İçerik: Genel uygulama logları, AI logları

2. **Öğrenme Logu** (`learner_log.jsonl`)

   - Konum: `/data/data/com.hyperxray.an/files/learner_log.jsonl`
   - İçerik: ML öğrenme olayları, rota kararları, performans metrikleri

3. **Runtime Logu** (`tls_v5_runtime_log.jsonl`)

   - Konum: `/data/data/com.hyperxray.an/files/logs/tls_v5_runtime_log.jsonl`
   - İçerik: TLS/SNI runtime metrikleri, network performans verileri

4. **Logcat** (`logcat.txt`)
   - Konum: Android sistem logları
   - İçerik: Package ile ilgili sistem logları

## Çıktı

Script çalıştırıldığında:

1. **Log Dosyaları**: `logs_collected/` dizinine kaydedilir
2. **Rapor**: `logs_collected/log_report_YYYYMMDD_HHMMSS.md` formatında oluşturulur

## Rapor İçeriği

Rapor şunları içerir:

- **Özet Tablosu**: Tüm log tiplerinin durumu
- **Detaylı Analizler**:
  - Log seviyeleri (ERROR, WARN, INFO, DEBUG)
  - En çok kullanılan tag'ler
  - Hata örnekleri
  - Performans metrikleri (gecikme, bant genişliği)
  - Başarı oranları
  - Rota kararları
- **Sonuç ve Öneriler**: Analiz sonuçlarına göre öneriler

## Örnek Rapor

```markdown
# HyperXray Log Analiz Raporu

**Oluşturulma Tarihi:** 2025-11-21 13:28:27
**Package:** com.hyperxray.an

## 📊 Özet

| Log Tipi     | Durum       | Detaylar               |
| ------------ | ----------- | ---------------------- |
| Ana Log      | ✅ analyzed | 50 satır, 0 hata       |
| Öğrenme Logu | ✅ analyzed | 9 kayıt, %100.0 başarı |

## 📋 App Log Detayları

- **Toplam Satır:** 50
- **Zaman Aralığı:** 2025/11/21 10:28:14 - 2025/11/21 10:28:19

### Log Seviyeleri

| Seviye | Sayı |
| ------ | ---- |
| INFO   | 34   |
| DEBUG  | 7    |
```

## Sorun Giderme

### ADB Bağlantı Hatası

```bash
# Cihazların listesini kontrol et
adb devices

# ADB sunucusunu yeniden başlat
adb kill-server
adb start-server
```

### Log Dosyası Bulunamadı

Bazı log dosyaları mevcut olmayabilir (örneğin runtime log henüz oluşturulmamışsa). Bu normaldir ve rapor bunu belirtir.

### Encoding Hatası

Script UTF-8 encoding kullanır. Windows'ta sorun yaşarsanız, Python'u UTF-8 modunda çalıştırın:

```bash
set PYTHONIOENCODING=utf-8
python tools/collect_logs.py
```

## Otomatikleştirme

Cron job veya scheduled task ile düzenli olarak çalıştırabilirsiniz:

```bash
# Her gün saat 02:00'de çalıştır (Linux/Mac)
0 2 * * * cd /path/to/project && python tools/collect_logs.py

# Windows Task Scheduler ile de yapılabilir
```

## Notlar

- Log dosyaları cihazın internal storage'ında saklanır
- Büyük log dosyaları için yeterli disk alanı olduğundan emin olun
- Log rotation mekanizması log dosyalarını otomatik olarak temizler (10MB limit)
