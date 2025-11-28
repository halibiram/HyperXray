#!/bin/bash
# HyperXray VPN Bağlantı İzleme Scripti
# VPN başarılı olana kadar izlemeye devam eder

set +e  # Hata durumunda durmayı devre dışı bırak

# Renk kodları
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

PACKAGE_NAME="com.hyperxray.an"
SERVICE_NAME="com.hyperxray.an.service.TProxyService"
DEFAULT_SOCKS5_PORT=10808

check_vpn_status() {
    local tun0_exists=$(adb shell "ip addr show tun0 2>/dev/null | grep -c 'inet '" 2>/dev/null || echo "0")
    local socks5_listening=$(adb shell "netstat -tuln 2>/dev/null || ss -tuln 2>/dev/null" 2>/dev/null | grep -c ":$DEFAULT_SOCKS5_PORT.*LISTEN" || echo "0")
    local service_running=$(adb shell "dumpsys activity services $PACKAGE_NAME 2>/dev/null | grep -c '$SERVICE_NAME'" 2>/dev/null || echo "0")
    
    # TUN0 interface var mı?
    if [ "$tun0_exists" -gt 0 ]; then
        echo "TUN0_OK"
        return 0
    fi
    
    # SOCKS5 port dinleniyor mu?
    if [ "$socks5_listening" -gt 0 ]; then
        echo "SOCKS5_OK"
        return 1
    fi
    
    # Servis çalışıyor mu?
    if [ "$service_running" -gt 0 ]; then
        echo "SERVICE_OK"
        return 1
    fi
    
    echo "NOT_CONNECTED"
    return 1
}

print_status() {
    local status=$1
    local timestamp=$(date +%H:%M:%S)
    
    case "$status" in
        "TUN0_OK")
            echo -e "${GREEN}[$timestamp] ✓ VPN BAŞARILI! TUN0 interface aktif${NC}"
            ;;
        "SOCKS5_OK")
            echo -e "${YELLOW}[$timestamp] ⚠ SOCKS5 port dinleniyor ama TUN0 yok${NC}"
            ;;
        "SERVICE_OK")
            echo -e "${YELLOW}[$timestamp] ⚠ TProxyService çalışıyor ama VPN interface yok${NC}"
            ;;
        *)
            echo -e "${RED}[$timestamp] ✗ VPN bağlantısı yok${NC}"
            ;;
    esac
}

echo "=== HyperXray VPN Bağlantı İzleme ==="
echo "VPN başarılı olana kadar izleniyor..."
echo "Çıkmak için Ctrl+C"
echo ""

COUNTER=0

while true; do
    COUNTER=$((COUNTER + 1))
    
    # VPN durumunu kontrol et
    STATUS=$(check_vpn_status)
    
    # Durumu göster
    print_status "$STATUS"
    
    # Başarılı mı?
    if [ "$STATUS" = "TUN0_OK" ]; then
        echo ""
        echo -e "${GREEN}=== VPN BAŞARILI! ===${NC}"
        echo ""
        
        # Detaylı bilgi
        echo "TUN0 Interface:"
        adb shell "ip addr show tun0 2>/dev/null" | head -5
        
        echo ""
        echo "SOCKS5 Port:"
        adb shell "netstat -tuln 2>/dev/null || ss -tuln 2>/dev/null" 2>/dev/null | grep ":$DEFAULT_SOCKS5_PORT" || true
        
        echo ""
        echo "TProxyService:"
        adb shell "dumpsys activity services $PACKAGE_NAME 2>/dev/null" | grep -A 5 "$SERVICE_NAME" | head -10 || true
        
        echo ""
        echo -e "${GREEN}✓ VPN bağlantısı başarıyla kuruldu!${NC}"
        break
    fi
    
    # Her 10 kontrolde bir özet bilgi göster
    if [ $((COUNTER % 10)) -eq 0 ]; then
        echo ""
        echo -e "${BLUE}--- Kontrol #$COUNTER - Detaylı Durum ---${NC}"
        
        # TUN interfaces
        echo "TUN Interfaces:"
        adb shell "ip addr show 2>/dev/null | grep -i '^[0-9]*:.*tun'" 2>/dev/null | head -3 || echo "  TUN interface bulunamadı"
        
        # SOCKS5 port
        echo "SOCKS5 Port ($DEFAULT_SOCKS5_PORT):"
        local port_check=$(adb shell "netstat -tuln 2>/dev/null || ss -tuln 2>/dev/null" 2>/dev/null | grep ":$DEFAULT_SOCKS5_PORT.*LISTEN" || echo "  Dinlenmiyor")
        echo "  $port_check"
        
        # Son loglar
        echo "Son VPN Logları:"
        adb logcat -d -t 5 2>/dev/null | grep -iE "TProxyService|VPN|tun0" | tail -3 || echo "  Log bulunamadı"
        
        echo ""
    fi
    
    # 5 saniye bekle
    sleep 5
done






















