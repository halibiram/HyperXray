#!/bin/bash
# HyperXray VPN Bağlantı Sorun Tespit Scripti
# Bu script VPN bağlantı sorunlarını tespit eder ve çözüm önerileri sunar

set -uo pipefail
# Note: -e flag removed to allow script to continue even if some checks fail

# Renk kodları (terminal desteği varsa)
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Paket bilgileri
PACKAGE_NAME="com.hyperxray.an"
SERVICE_NAME="com.hyperxray.an.service.TProxyService"
DEFAULT_SOCKS5_PORT=10808
LOG_TAG="TProxyService"

# Hata kategorileri
declare -a ISSUES=()
declare -a SOLUTIONS=()

# Rapor dosyası
REPORT_FILE="diagnose_report_$(date +%Y%m%d_%H%M%S).txt"

# Yardımcı fonksiyonlar
print_header() {
    echo ""
    echo -e "${BLUE}=== $1 ===${NC}"
    echo ""
}

print_success() {
    echo -e "${GREEN}✓${NC} $1"
}

print_error() {
    echo -e "${RED}✗${NC} $1"
    ISSUES+=("$1")
}

print_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
    ISSUES+=("$1")
}

print_info() {
    echo -e "${BLUE}ℹ${NC} $1"
}

add_solution() {
    SOLUTIONS+=("$1")
}

# ADB kontrolü
check_adb_connection() {
    print_header "[1] ADB Cihaz Bağlantısı Kontrolü"
    
    if ! command -v adb &> /dev/null; then
        print_error "ADB bulunamadı. Android SDK Platform Tools yüklü mü?"
        add_solution "Android SDK Platform Tools'u yükleyin: https://developer.android.com/studio/releases/platform-tools"
        return 1
    fi
    
    print_info "ADB versiyonu:"
    adb version
    
    print_info "Bağlı cihazlar:"
    local devices_output=$(adb devices)
    echo "$devices_output"
    
    if echo "$devices_output" | grep -q "device$"; then
        print_success "Cihaz bağlı ve yetkili"
        return 0
    elif echo "$devices_output" | grep -q "unauthorized"; then
        print_error "Cihaz yetkisiz. USB debugging izni verin."
        add_solution "Cihazda 'USB debugging' izni verin ve 'Always allow from this computer' seçeneğini işaretleyin"
        return 1
    elif echo "$devices_output" | grep -q "offline"; then
        print_error "Cihaz offline durumda"
        add_solution "USB kabloyu çıkarıp takın veya 'adb reconnect' komutunu çalıştırın"
        return 1
    else
        print_error "Hiç cihaz bağlı değil"
        add_solution "USB kabloyu kontrol edin ve USB debugging'i etkinleştirin"
        return 1
    fi
}

# Uygulama durumu kontrolü
check_application() {
    print_header "[2] HyperXray Uygulama Durumu"
    
    local package_installed=$(adb shell "pm list packages $PACKAGE_NAME" 2>/dev/null | grep -c "$PACKAGE_NAME" || echo "0")
    
    if [ "$package_installed" -eq 0 ]; then
        print_error "Uygulama yüklü değil ($PACKAGE_NAME)"
        add_solution "HyperXray uygulamasını yükleyin"
        return 1
    fi
    
    print_success "Uygulama yüklü ($PACKAGE_NAME)"
    
    # Uygulama çalışıyor mu?
    local app_processes=$(adb shell "ps -A | grep $PACKAGE_NAME" 2>/dev/null || true)
    
    if [ -z "$app_processes" ]; then
        print_warning "Uygulama çalışmıyor"
        add_solution "Uygulamayı başlatın"
    else
        print_success "Uygulama çalışıyor"
        echo "$app_processes"
    fi
    
    return 0
}

# VPN servis durumu kontrolü
check_vpn_service() {
    print_header "[3] TProxyService (VPN Servis) Durumu"
    
    local service_output=$(adb shell "dumpsys activity services $PACKAGE_NAME" 2>/dev/null || true)
    
    if echo "$service_output" | grep -q "$SERVICE_NAME"; then
        print_success "TProxyService bulundu"
        
        # Servis çalışıyor mu?
        if echo "$service_output" | grep -A 5 "$SERVICE_NAME" | grep -q "ServiceRecord\|active"; then
            print_success "TProxyService aktif"
            echo "$service_output" | grep -A 10 "$SERVICE_NAME" | head -15
        else
            print_warning "TProxyService bulundu ama aktif değil görünüyor"
            add_solution "Uygulamadan VPN'i başlatmayı deneyin"
        fi
    else
        print_warning "TProxyService bulunamadı (servis henüz başlatılmamış olabilir)"
        add_solution "VPN'i uygulamadan başlatın. TProxyService otomatik başlayacaktır."
        # Return 0 because this is not a critical error (service might not be started yet)
    fi
    
    return 0
}

# VPN interface kontrolü
check_vpn_interface() {
    print_header "[4] VPN Interface (TUN) Durumu"
    
    # dumpsys vpn kontrolü
    local vpn_output=$(adb shell "dumpsys vpn" 2>/dev/null || true)
    
    if [ -n "$vpn_output" ]; then
        print_info "VPN dumpsys çıktısı:"
        echo "$vpn_output" | head -30
        
        if echo "$vpn_output" | grep -qi "$PACKAGE_NAME"; then
            print_success "VPN interface aktif (dumpsys'de görünüyor)"
        else
            print_warning "VPN interface dumpsys'de görünmüyor"
        fi
    else
        print_warning "dumpsys vpn komutu çalıştırılamadı"
    fi
    
    # ip addr show ile TUN interface kontrolü
    local tun_interfaces=$(adb shell "ip addr show 2>/dev/null | grep -i '^[0-9]*:.*tun'" || true)
    
    if [ -n "$tun_interfaces" ]; then
        print_success "TUN interface(ler) bulundu:"
        echo "$tun_interfaces"
    else
        print_error "TUN interface bulunamadı"
        add_solution "VPN'i uygulamadan başlatın. VPN izni verilmiş olmalı."
        return 1
    fi
    
    # TUN0 özellikle kontrol et
    local tun0_status=$(adb shell "ip addr show tun0 2>/dev/null" || true)
    
    if [ -n "$tun0_status" ]; then
        print_success "tun0 interface mevcut:"
        echo "$tun0_status" | head -10
    else
        print_warning "tun0 interface bulunamadı (diğer TUN interface'ler mevcut olabilir)"
    fi
    
    return 0
}

# Xray process kontrolü
check_xray_process() {
    print_header "[5] Xray Process Durumu"
    
    local xray_processes=$(adb shell "ps -A | grep -iE 'xray|libxray'" 2>/dev/null || true)
    
    if [ -z "$xray_processes" ]; then
        print_error "Xray process bulunamadı"
        add_solution "Xray process başlatılamadı. Logları kontrol edin: adb logcat | grep -iE 'TProxyService.*ERROR|xray'"
        return 1
    fi
    
    print_success "Xray process(ler) çalışıyor:"
    echo "$xray_processes"
    
    # Process detayları
    local process_pids=$(echo "$xray_processes" | awk '{print $2}' | grep -E '^[0-9]+$' || true)
    
    for pid in $process_pids; do
        if [ -n "$pid" ]; then
            print_info "Process $pid detayları:"
            local proc_info=$(adb shell "cat /proc/$pid/stat 2>/dev/null" || true)
            if [ -n "$proc_info" ]; then
                # Process durumu (3. alan: R=running, S=sleeping, Z=zombie, etc.)
                local proc_state=$(echo "$proc_info" | awk '{print $3}')
                case "$proc_state" in
                    R) print_success "  Durum: Running" ;;
                    S) print_info "  Durum: Sleeping" ;;
                    Z) print_error "  Durum: Zombie (çökmüş)" ;;
                    *) print_warning "  Durum: $proc_state" ;;
                esac
            fi
        fi
    done
    
    return 0
}

# SOCKS5 port kontrolü
check_socks5_port() {
    print_header "[6] SOCKS5 Port Durumu"
    
    # Varsayılan port kontrolü (Preferences'ten okunabilir, şimdilik varsayılan kullanıyoruz)
    local socks5_port=$DEFAULT_SOCKS5_PORT
    
    print_info "Kontrol edilen SOCKS5 portu: $socks5_port"
    
    # Port dinleme durumu kontrolü
    local netstat_output=$(adb shell "netstat -tuln 2>/dev/null || ss -tuln 2>/dev/null" || true)
    
    if [ -z "$netstat_output" ]; then
        print_warning "netstat/ss komutu çalıştırılamadı"
        # Alternatif: lsof kullan
        local lsof_output=$(adb shell "lsof -i :$socks5_port 2>/dev/null" || true)
        if [ -n "$lsof_output" ]; then
            print_success "Port $socks5_port dinleniyor (lsof):"
            echo "$lsof_output"
        else
            print_error "Port $socks5_port dinlenmiyor"
            add_solution "SOCKS5 portu dinlenmiyor. Xray process çalışıyor mu kontrol edin."
            return 1
        fi
    else
        local port_listening=$(echo "$netstat_output" | grep -E ":$socks5_port.*LISTEN" || true)
        
        if [ -n "$port_listening" ]; then
            print_success "Port $socks5_port dinleniyor:"
            echo "$port_listening"
        else
            print_error "Port $socks5_port dinlenmiyor"
            echo "Mevcut dinlenen portlar:"
            echo "$netstat_output" | grep LISTEN | head -10
            add_solution "SOCKS5 portu dinlenmiyor. Xray başlatma loglarını kontrol edin."
            return 1
        fi
    fi
    
    # Localhost SOCKS5 bağlantı testi (opsiyonel)
    print_info "SOCKS5 bağlantı testi (curl ile):"
    local test_result=$(adb shell "curl --connect-timeout 2 --max-time 3 --socks5 127.0.0.1:$socks5_port http://www.google.com 2>&1" || echo "CONNECTION_FAILED")
    
    if echo "$test_result" | grep -q "CONNECTION_FAILED\|Connection refused\|timeout"; then
        print_warning "SOCKS5 bağlantı testi başarısız (bu normal olabilir, Xray henüz hazır olmayabilir)"
    else
        print_success "SOCKS5 bağlantı testi başarılı"
    fi
    
    return 0
}

# Log analizi
analyze_logs() {
    print_header "[7] Log Analizi"
    
    print_info "Son 100 satır log analizi (TProxyService, ERROR, VPN ilgili):"
    
    # Kritik hataları analiz et
    local error_logs=$(adb logcat -d -t 100 2>/dev/null | grep -iE "$LOG_TAG.*ERROR|$LOG_TAG.*failed|VPN.*failed|establish.*failed|TUN.*failed" || true)
    
    if [ -z "$error_logs" ]; then
        print_info "Son 100 satırda kritik hata bulunamadı"
    else
        print_warning "Kritik hatalar bulundu:"
        echo "$error_logs" | head -20
        
        # Hata kategorizasyonu
        if echo "$error_logs" | grep -qi "VPN permission\|VpnService.*permission"; then
            print_error "VPN izni hatası tespit edildi"
            add_solution "VPN izni verin: Uygulama ayarları > İzinler > VPN"
            ISSUES+=("VPN Permission Error")
        fi
        
        if echo "$error_logs" | grep -qi "another VPN\|VPN.*active"; then
            print_error "Başka bir VPN aktif"
            add_solution "Diğer VPN uygulamalarını kapatın"
            ISSUES+=("VPN Conflict")
        fi
        
        if echo "$error_logs" | grep -qi "xray.*crash\|xray.*exit\|libxray.*error"; then
            print_error "Xray process çökmüş"
            add_solution "Xray config dosyasını kontrol edin. Logları inceleyin: adb logcat | grep -i xray"
            ISSUES+=("Xray Crash")
        fi
        
        if echo "$error_logs" | grep -qi "port.*in use\|port.*conflict\|bind.*error"; then
            print_error "Port çakışması"
            add_solution "SOCKS5 portunu değiştirin veya portu kullanan diğer uygulamayı kapatın"
            ISSUES+=("Port Conflict")
        fi
        
        if echo "$error_logs" | grep -qi "native library\|libxray.*not found\|dlopen"; then
            print_error "Native library hatası"
            add_solution "Uygulamayı yeniden yükleyin veya native library dosyalarını kontrol edin"
            ISSUES+=("Native Library Error")
        fi
        
        if echo "$error_logs" | grep -qi "config.*error\|config.*invalid\|json.*error"; then
            print_error "Config hatası"
            add_solution "Xray config dosyasını doğrulayın. JSON formatı geçerli olmalı"
            ISSUES+=("Config Error")
        fi
        
        if echo "$error_logs" | grep -qi "TUN.*establish.*null\|TUN.*failed"; then
            print_error "TUN interface kurulum hatası"
            add_solution "VPN izni verin ve diğer VPN uygulamalarını kapatın"
            ISSUES+=("TUN Interface Error")
        fi
    fi
    
    # Son başlatma logları
    print_info "Son Xray başlatma logları:"
    local start_logs=$(adb logcat -d -t 200 2>/dev/null | grep -iE "$LOG_TAG.*start|$LOG_TAG.*xray.*start" | tail -10 || true)
    
    if [ -n "$start_logs" ]; then
        echo "$start_logs"
    else
        print_info "Xray başlatma logu bulunamadı"
    fi
    
    return 0
}

# Sorun raporu oluştur
generate_report() {
    print_header "[8] Sorun Raporu"
    
    {
        echo "HyperXray VPN Bağlantı Diagnostik Raporu"
        echo "Tarih: $(date)"
        echo "Paket: $PACKAGE_NAME"
        echo ""
        echo "=== Tespit Edilen Sorunlar ==="
        
        if [ ${#ISSUES[@]} -eq 0 ]; then
            echo "Hiç sorun tespit edilmedi. VPN bağlantısı normal görünüyor."
        else
            for i in "${!ISSUES[@]}"; do
                echo "$((i+1)). ${ISSUES[$i]}"
            done
        fi
        
        echo ""
        echo "=== Çözüm Önerileri ==="
        
        if [ ${#SOLUTIONS[@]} -eq 0 ]; then
            echo "Çözüm önerisi yok."
        else
            for i in "${!SOLUTIONS[@]}"; do
                echo "$((i+1)). ${SOLUTIONS[$i]}"
            done
        fi
        
        echo ""
        echo "=== Detaylı Loglar ==="
        echo "Tam log analizi için: adb logcat | grep -iE 'TProxyService|ERROR|VPN'"
        echo "Xray logları için: adb logcat | grep -i xray"
    } | tee "$REPORT_FILE"
    
    print_success "Rapor dosyası oluşturuldu: $REPORT_FILE"
    
    return 0
}

# Ana fonksiyon
main() {
    echo "=== HyperXray VPN Bağlantı Sorun Tespit Scripti ==="
    echo "Tarih: $(date)"
    echo ""
    
    # ADB kontrolü
    if ! check_adb_connection; then
        echo ""
        print_error "ADB bağlantı sorunu var. Lütfen önce bunu çözün."
        exit 1
    fi
    
    # Uygulama kontrolü
    check_application
    
    # VPN servis kontrolü
    check_vpn_service
    
    # VPN interface kontrolü
    check_vpn_interface
    
    # Xray process kontrolü
    check_xray_process
    
    # SOCKS5 port kontrolü
    check_socks5_port
    
    # Log analizi
    analyze_logs
    
    # Rapor oluştur
    generate_report
    
    echo ""
    echo "=== Özet ==="
    
    if [ ${#ISSUES[@]} -eq 0 ]; then
        print_success "Hiç sorun tespit edilmedi!"
    else
        print_error "${#ISSUES[@]} sorun tespit edildi"
        echo ""
        echo "Detaylı rapor için: cat $REPORT_FILE"
    fi
    
    echo ""
    print_info "Canlı log izleme için:"
    echo "  adb logcat | grep -iE 'TProxyService|ERROR|VPN'"
    echo ""
}

# Script'i çalıştır
main "$@"

