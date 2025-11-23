#!/bin/bash
# Basit VPN monitoring script - başarılı olana kadar izler

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

PACKAGE_NAME="com.hyperxray.an"
DEFAULT_SOCKS5_PORT=10808

check_vpn_status() {
    local tun0_count=$(adb shell "ip addr show tun0 2>/dev/null | grep -c 'inet '" 2>/dev/null)
    local socks5_count=$(adb shell "netstat -tuln 2>/dev/null || ss -tuln 2>/dev/null" 2>/dev/null | grep -c ":${DEFAULT_SOCKS5_PORT}.*LISTEN" || echo "0")
    
    if [ -z "$tun0_count" ]; then
        tun0_count=0
    fi
    
    if [ -z "$socks5_count" ]; then
        socks5_count=0
    fi
    
    echo "$tun0_count|$socks5_count"
}

echo "=== HyperXray VPN Monitoring ==="
echo "VPN başarılı olana kadar izleniyor..."
echo "Başlangıç: $(date +%H:%M:%S)"
echo "VPN'i uygulamadan başlatın"
echo ""
echo "Çıkmak için Ctrl+C"
echo ""

COUNTER=0

while true; do
    COUNTER=$((COUNTER + 1))
    TIMESTAMP=$(date +%H:%M:%S)
    
    STATUS=$(check_vpn_status)
    TUN0_COUNT=$(echo "$STATUS" | cut -d'|' -f1)
    SOCKS5_COUNT=$(echo "$STATUS" | cut -d'|' -f2)
    
    # Durum göster
    printf "[%s] #%d - TUN0: %s | SOCKS5: %s\r" "$TIMESTAMP" "$COUNTER" "$TUN0_COUNT" "$SOCKS5_COUNT"
    
    # Başarı kontrolü
    if [ "$TUN0_COUNT" -gt 0 ] && [ "$SOCKS5_COUNT" -gt 0 ]; then
        echo ""
        echo ""
        echo -e "${GREEN}=== VPN BAŞARILI! ===${NC}"
        echo -e "${GREEN}Zaman: $TIMESTAMP${NC}"
        echo ""
        
        echo "TUN0 Interface:"
        adb shell "ip addr show tun0 2>/dev/null" | head -5
        
        echo ""
        echo "SOCKS5 Port (${DEFAULT_SOCKS5_PORT}):"
        adb shell "netstat -tuln 2>/dev/null || ss -tuln 2>/dev/null" 2>/dev/null | grep ":${DEFAULT_SOCKS5_PORT}.*LISTEN" || true
        
        echo ""
        echo "TProxyService:"
        adb shell "dumpsys activity services ${PACKAGE_NAME} 2>/dev/null" | grep -A 5 "TProxyService" | head -10 || true
        
        echo ""
        echo -e "${GREEN}✓ VPN bağlantısı başarıyla kuruldu!${NC}"
        break
    fi
    
    # Her 20 kontrolde bir durum raporu
    if [ $((COUNTER % 20)) -eq 0 ]; then
        echo ""
        echo -e "${YELLOW}[$TIMESTAMP] Hala bekleniyor... (Kontrol #$COUNTER)${NC}"
        if [ "$SOCKS5_COUNT" -gt 0 ] && [ "$TUN0_COUNT" -eq 0 ]; then
            echo "  ⚠ SOCKS5 port dinleniyor ama TUN0 interface yok"
            echo "  ⚠ VPN interface kurulması gerekiyor"
        fi
        echo "  → VPN'i uygulamadan başlatın"
    fi
    
    sleep 3
done




