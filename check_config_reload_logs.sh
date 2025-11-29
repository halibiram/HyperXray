#!/bin/bash

echo "=========================================="
echo "Config Reload Test - Log Kontrolü"
echo "=========================================="
echo ""
echo "Lütfen uygulamayı başlatıp VPN'i connect edin..."
echo "5 saniye bekliyorum..."
sleep 5

echo ""
echo "=== XRAY PROCESS START Logları ==="
adb logcat -d | grep -i "XRAY PROCESS START" | tail -20

echo ""
echo "=== Force Reloading Config Logları ==="
adb logcat -d | grep -i "Force reloading config" | tail -10

echo ""
echo "=== Config Hash Logları ==="
adb logcat -d | grep -i "Config loaded from disk\|config hash" | tail -10

echo ""
echo "=== DNS PRE-RESOLVE Logları ==="
adb logcat -d | grep -i "DNS PRE-RESOLVE\|DNS BOOTSTRAP" | tail -10

echo ""
echo "=== XrayCoreManager/XrayProcessManager Logları ==="
adb logcat -d | grep -E "XrayCoreManager|XrayProcessManager" | grep -i "config\|hash\|reload" | tail -20

echo ""
echo "=========================================="
echo "Kontrol tamamlandı!"
echo "=========================================="

