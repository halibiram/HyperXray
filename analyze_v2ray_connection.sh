#!/bin/bash
# HTTP Custom / v2ray Bağlantı Analiz Scripti
# Bu script v2ray bağlantı kurulumunu izler ve analiz eder

echo "=== HTTP Custom v2ray Bağlantı Analizi ==="
echo ""

# 1. Cihaz bağlantısını kontrol et
echo "[1] ADB cihaz durumu:"
adb devices
echo ""

# 2. Uygulama paket adını bul
echo "[2] HTTP Custom uygulamasını arıyorum..."
PACKAGE_NAME=$(adb shell "pm list packages" | grep -iE "http.*custom|hyperxray" | head -1 | cut -d: -f2)
if [ -z "$PACKAGE_NAME" ]; then
    echo "Uyarı: HTTP Custom uygulaması bulunamadı"
    echo "Tüm uygulamaları listeliyorum..."
    adb shell "pm list packages" | grep -iE "proxy|vpn|v2ray|xray"
else
    echo "Bulunan paket: $PACKAGE_NAME"
fi
echo ""

# 3. Xray process'ini kontrol et
echo "[3] Çalışan xray/v2ray process'lerini kontrol ediyorum..."
adb shell "ps -A | grep -iE 'xray|v2ray|libxray'"
echo ""

# 4. SOCKS5 portunu kontrol et
echo "[4] Dinleyen portları kontrol ediyorum (SOCKS5 genellikle 10808):"
adb shell "netstat -tuln 2>/dev/null || ss -tuln 2>/dev/null" | grep -E "10808|1080|socks"
echo ""

# 5. VPN servis durumunu kontrol et
echo "[5] VPN servis durumu:"
adb shell "dumpsys vpn" 2>/dev/null | head -20
echo ""

# 6. Logları izle (v2ray bağlantı kurulumu için)
echo "[6] v2ray bağlantı loglarını izliyorum (Ctrl+C ile durdurun):"
echo "    - Xray process başlatma"
echo "    - SOCKS5 bağlantı kurulumu"
echo "    - HTTP istekleri"
echo ""
echo "Logları izlemeye başlıyorum..."
adb logcat -c
adb logcat | grep -iE "xray|v2ray|socks5|TProxyService|XrayRuntimeService|connection|connect" --color=never


