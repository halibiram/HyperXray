#!/bin/bash
# Xray crash sorununu tespit eden script

echo "=== Xray Crash Tespit Scripti ==="
echo ""

PACKAGE_NAME="com.hyperxray.an"

echo "1. Xray Process Durumu:"
adb shell "ps -A | grep -iE 'xray|libxray|com.hyperxray.an:native'"

echo ""
echo "2. VPN Interface Durumu:"
adb shell "ip addr show tun0 2>/dev/null" || echo "  TUN0 yok"

echo ""
echo "3. SOCKS5 Port Durumu:"
adb shell "netstat -tuln 2>/dev/null || ss -tuln 2>/dev/null" 2>/dev/null | grep "10808.*LISTEN" || echo "  Port dinlenmiyor"

echo ""
echo "4. Son Xray Logları (TProxyService):"
adb logcat -d -t 500 2>/dev/null | grep -iE "TProxyService.*xray|Xray.*exit|Xray.*crash|runXrayProcess" | tail -30

echo ""
echo "5. Xray Process Çıktısı:"
adb logcat -d -t 500 2>/dev/null | grep -i "xray" | grep -v "TProxyService\|VpnService" | tail -20

echo ""
echo "6. Config Dosyası Kontrolü:"
CONFIG_FILE=$(adb shell "ls /data/data/$PACKAGE_NAME/files/*.json 2>/dev/null" | head -1 | tr -d '\r')
if [ -n "$CONFIG_FILE" ]; then
    echo "  Config dosyası: $CONFIG_FILE"
    echo "  İlk 10 satır:"
    adb shell "head -10 $CONFIG_FILE 2>/dev/null" | head -10
else
    echo "  Config dosyası bulunamadı"
fi

echo ""
echo "7. GeoIP/GeoSite Dosyaları:"
adb shell "ls -la /data/data/$PACKAGE_NAME/files/*.dat 2>/dev/null" | head -5 || echo "  GeoIP/GeoSite dosyaları bulunamadı"

echo ""
echo "=== Sorun Özeti ==="
echo "VPN başlatılıyor ama Xray process hemen kapanıyor."
echo "Muhtemel nedenler:"
echo "  1. Config dosyası hatası (JSON formatı, eksik alanlar)"
echo "  2. GeoIP/GeoSite dosyaları eksik"
echo "  3. Xray native library hatası"
echo "  4. Port çakışması"
echo ""
echo "Detaylı log için: adb logcat | grep -iE 'TProxyService|xray'"












