#!/bin/bash
# DNS Cache Test Scripti
# Bu script DNS cache'in çalışıp çalışmadığını test eder

echo "=== DNS Cache Test Başlatılıyor ==="
echo ""

# Logları temizle
adb logcat -c

echo "1. DNS Cache durumu kontrol ediliyor..."
adb logcat -d -s DnsCacheManager:I HttpClientFactory:I | grep -E "DNS|cache|Cache" | tail -20

echo ""
echo "2. DNS Cache dosyası kontrol ediliyor..."
adb shell "run-as com.hyperxray.an ls -lh cache/dns_cache.json 2>/dev/null || echo 'DNS cache dosyasi henuz olusturulmamis'"

echo ""
echo "3. Canlı loglar izleniyor (10 saniye)..."
echo "   Uygulamada bir HTTP isteği yapın (web sitesi açın veya API çağrısı yapın)"
echo ""
timeout 10 adb logcat -s DnsCacheManager:I HttpClientFactory:I 2>&1 | grep -E "DNS|cache|Cache" || echo "Henüz DNS sorgusu yapılmadı"

echo ""
echo "4. Test sonrası durum:"
adb logcat -d -s DnsCacheManager:I HttpClientFactory:I | grep -E "DNS|cache|Cache" | tail -30

echo ""
echo "=== DNS Cache Test Tamamlandı ==="





