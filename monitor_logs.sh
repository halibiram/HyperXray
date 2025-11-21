#!/bin/bash
# HyperXray log monitoring script

echo "=== HyperXray Log Monitoring ==="
echo ""
echo "Logları izliyorum... (Ctrl+C ile durdurun)"
echo ""

# Logları temizle
adb logcat -c

# Canlı logları izle
adb logcat -s HttpClientFactory:I DnsCacheManager:I RetryInterceptor:I CacheLoggingInterceptor:I NetworkModule:I MainViewModel:I





