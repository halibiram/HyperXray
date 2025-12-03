#!/bin/bash
# HyperXray canlı log izleme scripti

echo "=== HyperXray Canlı Log İzleme ==="
echo ""
echo "Logları izlemek için Ctrl+C ile durdurun"
echo ""
echo "İzlenen Tag'ler:"
echo "  - HttpClientFactory (HTTP client optimizasyonları)"
echo "  - GoogleDNS (DNS çözümleme - Google DNS)"
echo "  - RetryInterceptor (Retry mekanizması)"
echo "  - CacheLoggingInterceptor (HTTP cache logları)"
echo ""
echo "=================================="
echo ""

# Logları temizle ve canlı izle
adb logcat -c
adb logcat -s HttpClientFactory:D GoogleDNS:D RetryInterceptor:D CacheLoggingInterceptor:D







