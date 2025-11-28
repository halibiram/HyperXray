#!/bin/bash
# VPN başarılı olana kadar bekleyen script
# Monitoring script'ini sürekli çalıştırır

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

PACKAGE_NAME="com.hyperxray.an"
DEFAULT_SOCKS5_PORT=10808

check_vpn() {
    local tun0_exists=$(adb shell "ip addr show tun0 2>/dev/null | grep -c 'inet '" 2>/dev/null || echo "0")
    local socks5_listening=$(adb shell "netstat -tuln 2>/dev/null || ss -tuln 2>/dev/null" 2>/dev/null | grep -c ":$DEFAULT_SOCKS5_PORT.*LISTEN" || echo "0")
    
    if [ "$tun0_exists" -gt 0 ] && [ "$socks5_listening" -gt 0 ]; then
        return 0  # VPN başarılı
    else
        return 1  # VPN henüz başarılı değil
    fi
}

echo "=== HyperXray VPN Bağlantı Bekleme ==="
echo "VPN başarılı olana kadar izleniyor..."
echo "VPN'i uygulamadan başlatın (com.hyperxray.an)"
echo ""
echo "Çıkmak için Ctrl+C"
echo ""

COUNTER=0
LAST_STATUS=""

while true; do
    COUNTER=$((COUNTER + 1))
    TIMESTAMP=$(date +%H:%M:%S)
    
    if check_vpn; then
        echo ""
        echo -e "${GREEN}=== VPN BAŞARILI! ===${NC}"
        echo -e "${GREEN}Zaman: $TIMESTAMP${NC}"
        echo ""
        
        echo "TUN0 Interface:"
        adb shell "ip addr show tun0 2>/dev/null" | head -5
        
        echo ""
        echo "SOCKS5 Port ($DEFAULT_SOCKS5_PORT):"
        adb shell "netstat -tuln 2>/dev/null || ss -tuln 2>/dev/null" 2>/dev/null | grep ":$DEFAULT_SOCKS5_PORT.*LISTEN" || true
        
        echo ""
        echo "TProxyService:"
        adb shell "dumpsys activity services $PACKAGE_NAME 2>/dev/null" | grep -A 5 "TProxyService" | head -10 || true
        
        echo ""
        echo -e "${GREEN}✓ VPN bağlantısı başarıyla kuruldu!${NC}"
        break
    else
        # Durum değişikliğini göster
        CURRENT_STATUS=$(adb shell "ip addr show tun0 2>/dev/null | grep -c 'inet '" 2>/dev/null || echo "0")
        
        if [ "$CURRENT_STATUS" != "$LAST_STATUS" ]; then
            if [ "$CURRENT_STATUS" -gt 0 ]; then
                echo -e "${YELLOW}[$TIMESTAMP] TUN0 interface bulundu! SOCKS5 bekleniyor...${NC}"
            fi
            LAST_STATUS="$CURRENT_STATUS"
        fi
        
        # Her 20 kontrolde bir durum göster
        if [ $((COUNTER % 20)) -eq 0 ]; then
            echo -e "${YELLOW}[$TIMESTAMP] Hala bekleniyor... (Kontrol #$COUNTER)${NC}"
            echo "  - VPN'i uygulamadan başlatın"
            echo "  - VPN izni verildiğinden emin olun"
        fi
    fi
    
    sleep 3
done






















