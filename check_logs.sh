#!/bin/bash
# HyperXray log kontrol scripti

echo "=== HyperXray Log Kontrol Scripti ==="
echo ""
echo "1. Uygulama process ID'sini buluyorum..."
PID=$(adb shell "ps -A | grep hyperxray | awk '{print \$2}' | head -1")
echo "Process ID: $PID"
echo ""

echo "2. Son 100 log satırını kontrol ediyorum..."
adb logcat -d | grep -E "HttpClientFactory|DnsCacheManager|RetryInterceptor|CacheLoggingInterceptor|com.hyperxray.an" | tail -100 > hyperxray_logs.txt

echo "3. Loglar hyperxray_logs.txt dosyasına kaydedildi"
echo ""
echo "=== Log İçeriği ==="
cat hyperxray_logs.txt

echo ""
echo "=== Canlı Log İzleme ==="
echo "Logları canlı izlemek için şu komutu çalıştırın:"
echo "adb logcat -s HttpClientFactory:D DnsCacheManager:D RetryInterceptor:D CacheLoggingInterceptor:D"





