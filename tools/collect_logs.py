#!/usr/bin/env python3
"""
HyperXray Log Collector ve Rapor Oluşturucu

Bu script:
1. ADB ile cihazdan logları toplar
2. Log dosyalarını analiz eder
3. Detaylı bir rapor oluşturur
"""

import subprocess
import json
import os
import sys
from datetime import datetime
from pathlib import Path
from collections import defaultdict, Counter
from typing import Dict, List, Any, Optional
import re

# Package name
PACKAGE_NAME = "com.hyperxray.an"

# Log dosya yolları (cihaz içi)
DEVICE_LOG_PATHS = {
    "app_log": f"/data/data/{PACKAGE_NAME}/files/app_log.txt",
    "learner_log": f"/data/data/{PACKAGE_NAME}/files/learner_log.jsonl",
    "runtime_log": f"/data/data/{PACKAGE_NAME}/files/logs/tls_v5_runtime_log.jsonl",
    "dns_cache": f"/data/data/{PACKAGE_NAME}/cache/dns_cache.json",
    "stat_file": f"/data/data/{PACKAGE_NAME}/files/stat.txt",
    "logcat": None,  # Will be collected separately
}

# Çıktı dizini
OUTPUT_DIR = Path("logs_collected")
OUTPUT_DIR.mkdir(exist_ok=True)

# Rapor dosyası
REPORT_FILE = OUTPUT_DIR / \
    f"log_report_{datetime.now().strftime('%Y%m%d_%H%M%S')}.md"


def run_adb_command(command: List[str]) -> Optional[str]:
    """ADB komutu çalıştır ve sonucu döndür."""
    try:
        result = subprocess.run(
            ["adb"] + command,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="ignore",
            check=False
        )
        if result.returncode == 0:
            return result.stdout.strip() if result.stdout else None
        else:
            error_msg = result.stderr.strip() if result.stderr else "Bilinmeyen hata"
            print(f"⚠️  ADB komutu başarısız: {' '.join(command)}")
            if error_msg:
                print(f"   Hata: {error_msg}")
            return None
    except FileNotFoundError:
        print("❌ ADB bulunamadı! Lütfen Android SDK'yı yükleyin.")
        sys.exit(1)
    except Exception as e:
        print(f"❌ ADB komutu hatası: {e}")
        return None


def check_adb_connection() -> bool:
    """ADB bağlantısını kontrol et."""
    result = run_adb_command(["devices"])
    if result:
        lines = result.split("\n")[1:]  # İlk satırı atla
        devices = [line for line in lines if line.strip() and "device" in line]
        if devices:
            print(f"✅ ADB bağlantısı başarılı: {len(devices)} cihaz bulundu")
            return True
        else:
            print("❌ Bağlı cihaz bulunamadı!")
            return False
    return False


def pull_log_file(device_path: str, local_name: str, check_exists: bool = True) -> Optional[Path]:
    """Cihazdan log dosyasını çek."""
    local_path = OUTPUT_DIR / local_name

    # Önce dosyanın var olup olmadığını kontrol et (opsiyonel)
    if check_exists:
        result = run_adb_command(
            ["shell", "run-as", PACKAGE_NAME, "test", "-f", device_path])
        if result is None:
            print(f"⚠️  Log dosyası bulunamadı: {device_path}")
            return None

    # Dosyayı çek (relative path kullan)
    # device_path'ten sadece dosya adını al
    relative_path = device_path.replace(f"/data/data/{PACKAGE_NAME}/", "")
    result = run_adb_command(
        ["shell", "run-as", PACKAGE_NAME, "cat", relative_path])
    if result is not None:
        local_path.write_text(result, encoding="utf-8", errors="ignore")
        size = local_path.stat().st_size
        print(f"✅ {local_name} çekildi ({size:,} bytes)")
        return local_path
    else:
        print(f"⚠️  {local_name} çekilemedi")
        return None


def collect_logcat(tag_filters: List[str] = None, lines: int = 500000) -> Optional[Path]:
    """Logcat'ten logları detaylı topla (artırılmış satır limiti)."""
    local_path = OUTPUT_DIR / "logcat.txt"

    # Önce tüm logları çek (filtreleme sonra yapılabilir)
    # -d: dump and exit, -v time: timestamp ekle
    command = ["logcat", "-d", "-v", "time"]

    # Son N satırı al (artırıldı: 500000 - daha detaylı rapor için)
    command.extend(["-t", str(lines)])

    result = run_adb_command(command)
    if result:
        # Package ile ilgili logları filtrele (DNS cache loglarını da dahil et)
        filtered_lines = []
        dns_tags = ["SystemDnsCacheServer", "DnsCacheManager", "DNS", "dns", "DnsUpstreamClient", "DnsSocketPool"]
        relevant_tags = [PACKAGE_NAME, "HyperXray", "TProxyService", "XrayRuntime", "TProxy", "Xray"]
        
        for line in result.split("\n"):
            if (any(tag in line for tag in relevant_tags) or 
                any(tag in line for tag in dns_tags)):
                filtered_lines.append(line)

        if filtered_lines:
            content = "\n".join(filtered_lines)
            local_path.write_text(content, encoding="utf-8", errors="ignore")
            size = local_path.stat().st_size
            print(
                f"✅ Logcat çekildi ({size:,} bytes, {len(filtered_lines)} satır filtrelendi)")
            return local_path
        else:
            print("⚠️  Logcat'te package ile ilgili log bulunamadı")
            return None
    else:
        print("⚠️  Logcat çekilemedi")
        return None


def analyze_app_log(log_file: Path) -> Dict[str, Any]:
    """Ana uygulama logunu analiz et."""
    if not log_file or not log_file.exists():
        return {"status": "not_found"}

    content = log_file.read_text(encoding="utf-8", errors="ignore")
    lines = content.split("\n")

    # Log seviyelerini say
    level_counts = Counter()
    tag_counts = Counter()
    error_patterns = []

    # Timestamp pattern
    timestamp_pattern = re.compile(r"(\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2})")

    for line in lines:
        if not line.strip():
            continue

        # Log seviyesi tespit et
        if "ERROR" in line.upper() or "[E]" in line:
            level_counts["ERROR"] += 1
        elif "WARN" in line.upper() or "[W]" in line:
            level_counts["WARN"] += 1
        elif "INFO" in line.upper() or "[I]" in line:
            level_counts["INFO"] += 1
        elif "DEBUG" in line.upper() or "[D]" in line:
            level_counts["DEBUG"] += 1

        # Tag tespit et
        tag_match = re.search(r"\[([^\]]+)\]", line)
        if tag_match:
            tag = tag_match.group(1)
            tag_counts[tag] += 1

        # Hata mesajlarını topla
        if "ERROR" in line.upper() or "Exception" in line or "Crash" in line:
            error_patterns.append(line[:200])  # İlk 200 karakter

    # Timestamp aralığını bul
    timestamps = timestamp_pattern.findall(content)
    time_range = None
    if timestamps:
        time_range = {
            "first": timestamps[0],
            "last": timestamps[-1],
            "count": len(timestamps)
        }

    return {
        "status": "analyzed",
        "total_lines": len(lines),
        "level_counts": dict(level_counts),
        "top_tags": dict(tag_counts.most_common(10)),
        "error_count": level_counts["ERROR"],
        "sample_errors": error_patterns[:10],
        "time_range": time_range
    }


def analyze_jsonl_log(log_file: Path) -> Dict[str, Any]:
    """JSONL formatındaki logları analiz et."""
    if not log_file or not log_file.exists():
        return {"status": "not_found"}

    entries = []
    errors = []

    for line_num, line in enumerate(log_file.read_text(encoding="utf-8", errors="ignore").split("\n"), 1):
        if not line.strip():
            continue

        try:
            entry = json.loads(line)
            entries.append(entry)
        except json.JSONDecodeError as e:
            errors.append(f"Satır {line_num}: {str(e)}")

    if not entries:
        return {"status": "empty"}

    # İstatistikler
    stats = {
        "total_entries": len(entries),
        "parse_errors": len(errors),
        "sample_errors": errors[:5]
    }

    # Learner log analizi
    if "svcClass" in entries[0]:
        # Learner log
        route_decisions = Counter(e.get("routeDecision", -1) for e in entries)
        success_rate = sum(1 for e in entries if e.get(
            "success", False)) / len(entries) * 100
        avg_latency = sum(e.get("latencyMs", 0)
                          for e in entries) / len(entries)
        avg_throughput = sum(e.get("throughputKbps", 0)
                             for e in entries) / len(entries)

        stats.update({
            "type": "learner",
            "route_decisions": dict(route_decisions),
            "success_rate": round(success_rate, 2),
            "avg_latency_ms": round(avg_latency, 2),
            "avg_throughput_kbps": round(avg_throughput, 2)
        })

    # Runtime log analizi
    elif "routingDecision" in entries[0]:
        # Runtime log
        routing_decisions = Counter(
            e.get("routingDecision", -1) for e in entries)
        success_rate = sum(1 for e in entries if e.get(
            "success", False)) / len(entries) * 100
        avg_latency = sum(e.get("latencyMs", 0)
                          for e in entries) / len(entries)
        avg_throughput = sum(e.get("throughputKbps", 0)
                             for e in entries) / len(entries)

        stats.update({
            "type": "runtime",
            "routing_decisions": dict(routing_decisions),
            "success_rate": round(success_rate, 2),
            "avg_latency_ms": round(avg_latency, 2),
            "avg_throughput_kbps": round(avg_throughput, 2)
        })

    return {
        "status": "analyzed",
        **stats
    }


def analyze_dns_cache(cache_file: Path) -> Dict[str, Any]:
    """DNS cache dosyasını detaylı analiz et."""
    if not cache_file or not cache_file.exists():
        return {"status": "not_found"}

    try:
        content = cache_file.read_text(encoding="utf-8", errors="ignore")
        cache_data = json.loads(content)

        # Cache istatistikleri
        entries = cache_data.get("entries", {})
        total_entries = len(entries)
        current_time = int(datetime.now().timestamp())

        # TTL analizi
        ttls = []
        expired_entries = 0
        valid_entries = 0
        entry_ages = []
        ip_counts = []
        domain_categories = {
            "popular": 0,
            "cdn": 0,
            "api": 0,
            "other": 0
        }

        for domain, entry_data in entries.items():
            if isinstance(entry_data, dict):
                ttl = entry_data.get("ttl", 0)
                timestamp = entry_data.get("timestamp", 0)
                ips = entry_data.get("ips", [])
                
                if ttl > 0:
                    ttls.append(ttl)
                
                # Check if expired
                age = current_time - timestamp
                entry_ages.append(age)
                if age > ttl:
                    expired_entries += 1
                else:
                    valid_entries += 1
                
                # IP count
                if isinstance(ips, list):
                    ip_counts.append(len(ips))
                
                # Domain categorization
                domain_lower = domain.lower()
                if any(p in domain_lower for p in ["google", "facebook", "youtube", "twitter", "instagram", "amazon", "microsoft", "apple"]):
                    domain_categories["popular"] += 1
                elif any(cdn in domain_lower for cdn in [".cdn.", ".edge.", "cloudfront", "akamaiedge", "fastly"]):
                    domain_categories["cdn"] += 1
                elif any(api in domain_lower for api in ["api.", ".api", "api-"]):
                    domain_categories["api"] += 1
                else:
                    domain_categories["other"] += 1

        avg_ttl = sum(ttls) / len(ttls) if ttls else 0
        avg_age = sum(entry_ages) / len(entry_ages) if entry_ages else 0
        avg_ips = sum(ip_counts) / len(ip_counts) if ip_counts else 0
        min_ttl = min(ttls) if ttls else 0
        max_ttl = max(ttls) if ttls else 0

        # Domain analizi
        domains = list(entries.keys())
        popular_domains = [d for d in domains if any(
            p in d.lower() for p in ["google", "facebook", "youtube", "twitter", "instagram"])]

        # TTL distribution
        ttl_distribution = Counter()
        for ttl in ttls:
            if ttl < 3600:  # < 1 hour
                ttl_distribution["<1h"] += 1
            elif ttl < 86400:  # < 24 hours
                ttl_distribution["1h-24h"] += 1
            elif ttl < 172800:  # < 48 hours
                ttl_distribution["24h-48h"] += 1
            else:
                ttl_distribution[">48h"] += 1

        return {
            "status": "analyzed",
            "total_entries": total_entries,
            "valid_entries": valid_entries,
            "expired_entries": expired_entries,
            "expiry_rate": round((expired_entries / total_entries * 100) if total_entries > 0 else 0, 2),
            "avg_ttl_seconds": round(avg_ttl, 2),
            "min_ttl_seconds": min_ttl,
            "max_ttl_seconds": max_ttl,
            "avg_age_seconds": round(avg_age, 2),
            "avg_ips_per_entry": round(avg_ips, 2),
            "ttl_distribution": dict(ttl_distribution),
            "domain_categories": domain_categories,
            "popular_domains_count": len(popular_domains),
            "sample_domains": domains[:30],
            "cache_size_bytes": len(content.encode('utf-8'))
        }
    except json.JSONDecodeError as e:
        return {"status": "invalid_json", "error": str(e)}
    except Exception as e:
        return {"status": "error", "error": str(e)}


def analyze_dns_logs(app_log_file: Path, logcat_file: Path) -> Dict[str, Any]:
    """DNS loglarını detaylı analiz et (app_log ve logcat'ten filtrele)."""
    dns_logs = []
    dns_cache_hits = 0
    dns_cache_misses = 0
    dns_resolved = 0
    dns_errors = 0
    dns_unhealthy_servers = 0
    dns_invalid_data = 0
    domains_queried = Counter()
    latency_times = []
    ttl_values = []
    dns_operations = Counter()  # initialize, save, get, cleanup, etc.
    dns_metrics_updates = 0
    dns_server_starts = 0
    dns_server_stops = 0

    # DNS ile ilgili tag'ler (genişletilmiş)
    dns_tags = ["SystemDnsCacheServer", "DnsCacheManager", "DnsUpstreamClient", 
                "DnsSocketPool", "DNS", "dns", "DnsCache"]

    # App log'dan DNS loglarını filtrele
    if app_log_file and app_log_file.exists():
        content = app_log_file.read_text(encoding="utf-8", errors="ignore")
        for line in content.split("\n"):
            if any(tag in line for tag in dns_tags):
                dns_logs.append(line)

                # Cache hit/miss sayısı
                line_upper = line.upper()
                if "CACHE HIT" in line_upper or "cache hit" in line.lower():
                    dns_cache_hits += 1
                elif "CACHE MISS" in line_upper or "cache miss" in line.lower():
                    dns_cache_misses += 1
                elif "DNS resolved" in line_upper or "✅ DNS" in line:
                    dns_resolved += 1

                # Domain çıkar
                domain_match = re.search(
                    r'([a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}', line)
                if domain_match:
                    domain = domain_match.group(0)
                    domains_queried[domain] += 1

                # Latency extraction
                latency_match = re.search(r'latency[:\s]+([\d.]+)\s*ms', line, re.IGNORECASE)
                if latency_match:
                    try:
                        latency_times.append(float(latency_match.group(1)))
                    except ValueError:
                        pass
                
                # TTL extraction
                ttl_match = re.search(r'TTL[:\s]+(\d+)', line, re.IGNORECASE)
                if ttl_match:
                    try:
                        ttl_values.append(int(ttl_match.group(1)))
                    except ValueError:
                        pass
                
                # DNS operations tracking
                if "initialized" in line.lower() or "initialize" in line.lower():
                    dns_operations["initialize"] += 1
                elif "save" in line.lower() and "cache" in line.lower():
                    dns_operations["save"] += 1
                elif "get" in line.lower() and "cache" in line.lower():
                    dns_operations["get"] += 1
                elif "cleanup" in line.lower() or "cleaned up" in line.lower():
                    dns_operations["cleanup"] += 1
                elif "metrics" in line.lower() and "update" in line.lower():
                    dns_metrics_updates += 1
                elif "started" in line.lower() and "server" in line.lower():
                    dns_server_starts += 1
                elif "stopped" in line.lower() and "server" in line.lower():
                    dns_server_stops += 1
                
                # Hata kontrolü
                if "ERROR" in line_upper or "FAILED" in line_upper or "EXCEPTION" in line_upper:
                    dns_errors += 1
                elif "unhealthy" in line.lower():
                    dns_unhealthy_servers += 1
                elif "Invalid dataLength" in line or "invalid" in line.lower() and "dns" in line.lower():
                    dns_invalid_data += 1

    # Logcat'ten DNS loglarını filtrele
    if logcat_file and logcat_file.exists():
        content = logcat_file.read_text(encoding="utf-8", errors="ignore")
        for line in content.split("\n"):
            if any(tag in line for tag in dns_tags):
                dns_logs.append(line)

                line_upper = line.upper()
                if "CACHE HIT" in line_upper or "✅ DNS CACHE HIT" in line:
                    dns_cache_hits += 1
                elif "CACHE MISS" in line_upper or "⚠️ DNS CACHE MISS" in line:
                    dns_cache_misses += 1
                elif "DNS resolved" in line_upper or "✅ DNS resolved" in line:
                    dns_resolved += 1

                domain_match = re.search(
                    r'([a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}', line)
                if domain_match:
                    domain = domain_match.group(0)
                    domains_queried[domain] += 1

                # Latency extraction (logcat)
                latency_match = re.search(r'latency[:\s]+([\d.]+)\s*ms', line, re.IGNORECASE)
                if latency_match:
                    try:
                        latency_times.append(float(latency_match.group(1)))
                    except ValueError:
                        pass
                
                # TTL extraction (logcat)
                ttl_match = re.search(r'TTL[:\s]+(\d+)', line, re.IGNORECASE)
                if ttl_match:
                    try:
                        ttl_values.append(int(ttl_match.group(1)))
                    except ValueError:
                        pass
                
                # DNS operations tracking (logcat)
                if "initialized" in line.lower() or "initialize" in line.lower():
                    dns_operations["initialize"] += 1
                elif "save" in line.lower() and "cache" in line.lower():
                    dns_operations["save"] += 1
                elif "get" in line.lower() and "cache" in line.lower():
                    dns_operations["get"] += 1
                elif "cleanup" in line.lower() or "cleaned up" in line.lower():
                    dns_operations["cleanup"] += 1
                elif "metrics" in line.lower() and "update" in line.lower():
                    dns_metrics_updates += 1
                elif "started" in line.lower() and "server" in line.lower():
                    dns_server_starts += 1
                elif "stopped" in line.lower() and "server" in line.lower():
                    dns_server_stops += 1
                
                if "ERROR" in line_upper or "FATAL" in line_upper:
                    dns_errors += 1
                elif "unhealthy" in line.lower():
                    dns_unhealthy_servers += 1
                elif "Invalid dataLength" in line:
                    dns_invalid_data += 1

    if not dns_logs:
        return {"status": "not_found"}

    total_queries = dns_cache_hits + dns_cache_misses + dns_resolved
    cache_hit_rate = (dns_cache_hits / total_queries *
                      100) if total_queries > 0 else 0
    
    # Calculate latency statistics
    avg_latency = sum(latency_times) / len(latency_times) if latency_times else 0
    min_latency = min(latency_times) if latency_times else 0
    max_latency = max(latency_times) if latency_times else 0
    
    # Calculate TTL statistics
    avg_ttl = sum(ttl_values) / len(ttl_values) if ttl_values else 0
    min_ttl = min(ttl_values) if ttl_values else 0
    max_ttl = max(ttl_values) if ttl_values else 0

    return {
        "status": "analyzed",
        "total_dns_logs": len(dns_logs),
        "cache_hits": dns_cache_hits,
        "cache_misses": dns_cache_misses,
        "dns_resolved": dns_resolved,
        "cache_hit_rate": round(cache_hit_rate, 2),
        "errors": dns_errors,
        "unhealthy_servers": dns_unhealthy_servers,
        "invalid_data_errors": dns_invalid_data,
        "unique_domains": len(domains_queried),
        "top_domains": dict(domains_queried.most_common(30)),
        "latency_stats": {
            "avg_ms": round(avg_latency, 2),
            "min_ms": round(min_latency, 2),
            "max_ms": round(max_latency, 2),
            "samples": len(latency_times)
        },
        "ttl_stats": {
            "avg_seconds": round(avg_ttl, 2),
            "min_seconds": min_ttl,
            "max_seconds": max_ttl,
            "samples": len(ttl_values)
        },
        "operations": dict(dns_operations),
        "metrics_updates": dns_metrics_updates,
        "server_starts": dns_server_starts,
        "server_stops": dns_server_stops,
        "sample_logs": dns_logs[:20]
    }


def analyze_telegram_logs(app_log_file: Path, logcat_file: Path) -> Dict[str, Any]:
    """Telegram loglarını analiz et (app_log ve logcat'ten filtrele)."""
    telegram_logs = []
    telegram_errors = 0
    telegram_success = 0
    commands_processed = 0
    notifications_sent = 0
    api_calls = 0
    api_errors = 0

    # Telegram ile ilgili tag'ler
    telegram_tags = ["TelegramNotificationManager",
                     "TelegramApiDataSource", "TelegramConfig", "Telegram"]

    # App log'dan Telegram loglarını filtrele
    if app_log_file and app_log_file.exists():
        content = app_log_file.read_text(encoding="utf-8", errors="ignore")
        for line in content.split("\n"):
            if any(tag in line for tag in telegram_tags):
                telegram_logs.append(line)

                # Başarılı işlemler
                if "sent successfully" in line.lower() or "successfully" in line.lower():
                    telegram_success += 1
                if "notification sent" in line.lower():
                    notifications_sent += 1
                if "command processed" in line.lower():
                    commands_processed += 1
                if "Sending message" in line or "getUpdates" in line:
                    api_calls += 1

                # Hatalar
                if "ERROR" in line.upper() or "FAILED" in line.upper() or "Exception" in line:
                    telegram_errors += 1
                    api_errors += 1

    # Logcat'ten Telegram loglarını filtrele
    if logcat_file and logcat_file.exists():
        content = logcat_file.read_text(encoding="utf-8", errors="ignore")
        for line in content.split("\n"):
            if any(tag in line for tag in telegram_tags):
                telegram_logs.append(line)

                if "sent successfully" in line.lower():
                    telegram_success += 1
                if "notification sent" in line.lower():
                    notifications_sent += 1
                if "command processed" in line.lower():
                    commands_processed += 1
                if "Sending message" in line or "getUpdates" in line:
                    api_calls += 1

                if "ERROR" in line.upper() or "FATAL" in line.upper():
                    telegram_errors += 1
                    api_errors += 1

    if not telegram_logs:
        return {"status": "not_found"}

    success_rate = (telegram_success / (telegram_success + telegram_errors)
                    * 100) if (telegram_success + telegram_errors) > 0 else 0

    return {
        "status": "analyzed",
        "total_telegram_logs": len(telegram_logs),
        "success_count": telegram_success,
        "error_count": telegram_errors,
        "success_rate": round(success_rate, 2),
        "notifications_sent": notifications_sent,
        "commands_processed": commands_processed,
        "api_calls": api_calls,
        "api_errors": api_errors,
        "sample_logs": telegram_logs[:15]
    }


def analyze_logcat(log_file: Path) -> Dict[str, Any]:
    """Logcat loglarını analiz et."""
    if not log_file or not log_file.exists():
        return {"status": "not_found"}

    content = log_file.read_text(encoding="utf-8", errors="ignore")
    lines = content.split("\n")

    # Log seviyeleri
    level_counts = Counter()
    tag_counts = Counter()
    fatal_errors = []

    for line in lines:
        if not line.strip():
            continue

        # Android log formatı: MM-DD HH:MM:SS.mmm PID TID LEVEL TAG: MESSAGE
        parts = line.split()
        if len(parts) >= 5:
            level = parts[4] if len(parts) > 4 else "UNKNOWN"
            level_counts[level] += 1

            if len(parts) > 5:
                tag = parts[5].rstrip(":")
                tag_counts[tag] += 1

        # Fatal hataları topla
        if "FATAL" in line or "AndroidRuntime" in line and "FATAL" in line:
            fatal_errors.append(line[:300])

    return {
        "status": "analyzed",
        "total_lines": len(lines),
        "level_counts": dict(level_counts),
        "top_tags": dict(tag_counts.most_common(15)),
        "fatal_errors": fatal_errors[:10]
    }


def generate_report(analyses: Dict[str, Any], system_info: Dict[str, Any] = None) -> str:
    """Markdown formatında rapor oluştur."""
    report = []

    # Başlık
    report.append("# HyperXray Log Analiz Raporu\n")
    report.append(
        f"**Oluşturulma Tarihi:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
    report.append(f"**Package:** {PACKAGE_NAME}\n")
    report.append("---\n")

    # Özet
    report.append("## 📊 Özet\n")
    report.append("| Log Tipi | Durum | Detaylar |\n")
    report.append("|----------|-------|----------|\n")

    for log_type, analysis in analyses.items():
        status = analysis.get("status", "unknown")
        status_emoji = "✅" if status == "analyzed" else "⚠️" if status == "not_found" else "❌"

        if log_type == "app_log":
            if status == "analyzed":
                total = analysis.get("total_lines", 0)
                errors = analysis.get("error_count", 0)
                report.append(
                    f"| Ana Log | {status_emoji} {status} | {total} satır, {errors} hata |\n")
            else:
                report.append(f"| Ana Log | {status_emoji} {status} | - |\n")

        elif log_type == "learner_log":
            if status == "analyzed":
                entries = analysis.get("total_entries", 0)
                success = analysis.get("success_rate", 0)
                report.append(
                    f"| Öğrenme Logu | {status_emoji} {status} | {entries} kayıt, %{success} başarı |\n")
            else:
                report.append(
                    f"| Öğrenme Logu | {status_emoji} {status} | - |\n")

        elif log_type == "runtime_log":
            if status == "analyzed":
                entries = analysis.get("total_entries", 0)
                success = analysis.get("success_rate", 0)
                report.append(
                    f"| Runtime Logu | {status_emoji} {status} | {entries} kayıt, %{success} başarı |\n")
            else:
                report.append(
                    f"| Runtime Logu | {status_emoji} {status} | - |\n")

        elif log_type == "dns_cache":
            if status == "analyzed":
                entries = analysis.get("total_entries", 0)
                report.append(
                    f"| DNS Cache | {status_emoji} {status} | {entries} kayıt |\n")
            else:
                report.append(f"| DNS Cache | {status_emoji} {status} | - |\n")

        elif log_type == "dns_logs":
            if status == "analyzed":
                logs = analysis.get("total_dns_logs", 0)
                hit_rate = analysis.get("cache_hit_rate", 0)
                report.append(
                    f"| DNS Logları | {status_emoji} {status} | {logs} log, %{hit_rate} cache hit |\n")
            else:
                report.append(
                    f"| DNS Logları | {status_emoji} {status} | - |\n")

        elif log_type == "telegram_logs":
            if status == "analyzed":
                logs = analysis.get("total_telegram_logs", 0)
                success_rate = analysis.get("success_rate", 0)
                report.append(
                    f"| Telegram Logları | {status_emoji} {status} | {logs} log, %{success_rate} başarı |\n")
            else:
                report.append(
                    f"| Telegram Logları | {status_emoji} {status} | - |\n")

        elif log_type == "stat_file":
            if status == "analyzed":
                total = analysis.get("total_lines", 0)
                report.append(
                    f"| Stat Dosyası | {status_emoji} {status} | {total} satır |\n")
            else:
                report.append(f"| Stat Dosyası | {status_emoji} {status} | - |\n")

        elif log_type == "logcat":
            if status == "analyzed":
                lines = analysis.get("total_lines", 0)
                fatals = len(analysis.get("fatal_errors", []))
                report.append(
                    f"| Logcat | {status_emoji} {status} | {lines} satır, {fatals} fatal hata |\n")
            else:
                report.append(f"| Logcat | {status_emoji} {status} | - |\n")

    report.append("\n")

    # Detaylı analizler
    for log_type, analysis in analyses.items():
        if analysis.get("status") != "analyzed":
            continue

        report.append(f"## 📋 {log_type.replace('_', ' ').title()} Detayları\n")

        if log_type == "app_log":
            report.append(
                f"- **Toplam Satır:** {analysis.get('total_lines', 0):,}\n")

            if analysis.get("time_range"):
                tr = analysis["time_range"]
                report.append(
                    f"- **Zaman Aralığı:** {tr['first']} - {tr['last']}\n")

            report.append("\n### Log Seviyeleri\n")
            report.append("| Seviye | Sayı |\n")
            report.append("|--------|------|\n")
            for level, count in analysis.get("level_counts", {}).items():
                report.append(f"| {level} | {count:,} |\n")

            report.append("\n### En Çok Kullanılan Tag'ler\n")
            report.append("| Tag | Kullanım |\n")
            report.append("|-----|----------|\n")
            for tag, count in list(analysis.get("top_tags", {}).items())[:10]:
                report.append(f"| {tag} | {count:,} |\n")

            if analysis.get("error_count", 0) > 0:
                report.append("\n### Örnek Hatalar\n")
                report.append("```\n")
                for error in analysis.get("sample_errors", [])[:5]:
                    report.append(f"{error}\n")
                report.append("```\n")

        elif log_type in ["learner_log", "runtime_log"]:
            report.append(
                f"- **Toplam Kayıt:** {analysis.get('total_entries', 0):,}\n")
            report.append(
                f"- **Başarı Oranı:** %{analysis.get('success_rate', 0):.2f}\n")
            report.append(
                f"- **Ortalama Gecikme:** {analysis.get('avg_latency_ms', 0):.2f} ms\n")
            report.append(
                f"- **Ortalama Bant Genişliği:** {analysis.get('avg_throughput_kbps', 0):.2f} kbps\n")

            if "route_decisions" in analysis:
                report.append("\n### Rota Kararları\n")
                report.append("| Karar | Sayı |\n")
                report.append("|-------|------|\n")
                for decision, count in analysis["route_decisions"].items():
                    decision_name = {0: "Proxy", 1: "Direct", 2: "Optimized"}.get(
                        decision, f"Unknown({decision})")
                    report.append(f"| {decision_name} | {count:,} |\n")

            if "routing_decisions" in analysis:
                report.append("\n### Routing Kararları\n")
                report.append("| Karar | Sayı |\n")
                report.append("|-------|------|\n")
                for decision, count in analysis["routing_decisions"].items():
                    report.append(f"| {decision} | {count:,} |\n")

        elif log_type == "dns_cache":
            report.append(
                f"- **Toplam Kayıt:** {analysis.get('total_entries', 0):,}\n")
            report.append(
                f"- **Geçerli Kayıt:** {analysis.get('valid_entries', 0):,}\n")
            report.append(
                f"- **Süresi Dolmuş Kayıt:** {analysis.get('expired_entries', 0):,}\n")
            report.append(
                f"- **Süresi Dolma Oranı:** %{analysis.get('expiry_rate', 0):.2f}\n")
            report.append(
                f"- **Ortalama TTL:** {analysis.get('avg_ttl_seconds', 0):.2f} saniye ({analysis.get('avg_ttl_seconds', 0) / 3600:.2f} saat)\n")
            report.append(
                f"- **Min TTL:** {analysis.get('min_ttl_seconds', 0):,} saniye\n")
            report.append(
                f"- **Max TTL:** {analysis.get('max_ttl_seconds', 0):,} saniye\n")
            report.append(
                f"- **Ortalama Yaş:** {analysis.get('avg_age_seconds', 0):.2f} saniye ({analysis.get('avg_age_seconds', 0) / 3600:.2f} saat)\n")
            report.append(
                f"- **Entry Başına Ortalama IP:** {analysis.get('avg_ips_per_entry', 0):.2f}\n")
            report.append(
                f"- **Cache Boyutu:** {analysis.get('cache_size_bytes', 0):,} bytes ({analysis.get('cache_size_bytes', 0) / 1024:.2f} KB)\n")
            
            if analysis.get("ttl_distribution"):
                report.append("\n### TTL Dağılımı\n")
                report.append("| Aralık | Kayıt Sayısı |\n")
                report.append("|--------|--------------|\n")
                for range_name, count in analysis.get("ttl_distribution", {}).items():
                    report.append(f"| {range_name} | {count:,} |\n")
            
            if analysis.get("domain_categories"):
                report.append("\n### Domain Kategorileri\n")
                report.append("| Kategori | Sayı |\n")
                report.append("|----------|------|\n")
                for category, count in analysis.get("domain_categories", {}).items():
                    report.append(f"| {category} | {count:,} |\n")
            
            report.append(
                f"- **Popüler Domain Sayısı:** {analysis.get('popular_domains_count', 0)}\n")

            if analysis.get("sample_domains"):
                report.append("\n### Örnek Domainler (İlk 30)\n")
                report.append("| Domain |\n")
                report.append("|--------|\n")
                for domain in analysis.get("sample_domains", [])[:30]:
                    report.append(f"| {domain} |\n")

        elif log_type == "dns_logs":
            report.append(
                f"- **Toplam DNS Log:** {analysis.get('total_dns_logs', 0):,}\n")
            report.append(
                f"- **Cache Hit:** {analysis.get('cache_hits', 0):,}\n")
            report.append(
                f"- **Cache Miss:** {analysis.get('cache_misses', 0):,}\n")
            report.append(
                f"- **DNS Resolved:** {analysis.get('dns_resolved', 0):,}\n")
            report.append(
                f"- **Cache Hit Rate:** %{analysis.get('cache_hit_rate', 0):.2f}\n")
            report.append(f"- **Hatalar:** {analysis.get('errors', 0):,}\n")
            if analysis.get('unhealthy_servers', 0) > 0:
                report.append(
                    f"- **⚠️ Unhealthy DNS Servers:** {analysis.get('unhealthy_servers', 0):,}\n")
            if analysis.get('invalid_data_errors', 0) > 0:
                report.append(
                    f"- **⚠️ Invalid Data Errors:** {analysis.get('invalid_data_errors', 0):,}\n")
            report.append(
                f"- **Benzersiz Domain:** {analysis.get('unique_domains', 0):,}\n")
            
            if analysis.get("latency_stats"):
                lat_stats = analysis["latency_stats"]
                report.append("\n### Latency İstatistikleri\n")
                report.append(f"- **Ortalama Latency:** {lat_stats.get('avg_ms', 0):.2f} ms\n")
                report.append(f"- **Min Latency:** {lat_stats.get('min_ms', 0):.2f} ms\n")
                report.append(f"- **Max Latency:** {lat_stats.get('max_ms', 0):.2f} ms\n")
                report.append(f"- **Örnek Sayısı:** {lat_stats.get('samples', 0):,}\n")
            
            if analysis.get("ttl_stats"):
                ttl_stats = analysis["ttl_stats"]
                report.append("\n### TTL İstatistikleri (Loglardan)\n")
                report.append(f"- **Ortalama TTL:** {ttl_stats.get('avg_seconds', 0):.2f} saniye ({ttl_stats.get('avg_seconds', 0) / 3600:.2f} saat)\n")
                report.append(f"- **Min TTL:** {ttl_stats.get('min_seconds', 0):,} saniye\n")
                report.append(f"- **Max TTL:** {ttl_stats.get('max_seconds', 0):,} saniye\n")
                report.append(f"- **Örnek Sayısı:** {ttl_stats.get('samples', 0):,}\n")
            
            if analysis.get("operations"):
                report.append("\n### DNS İşlemleri\n")
                report.append("| İşlem | Sayı |\n")
                report.append("|-------|------|\n")
                for op, count in analysis.get("operations", {}).items():
                    report.append(f"| {op} | {count:,} |\n")
            
            if analysis.get("metrics_updates", 0) > 0:
                report.append(f"- **Metrics Güncellemeleri:** {analysis.get('metrics_updates', 0):,}\n")
            if analysis.get("server_starts", 0) > 0:
                report.append(f"- **DNS Server Başlatmaları:** {analysis.get('server_starts', 0):,}\n")
            if analysis.get("server_stops", 0) > 0:
                report.append(f"- **DNS Server Durdurmaları:** {analysis.get('server_stops', 0):,}\n")

            if analysis.get("top_domains"):
                report.append("\n### En Çok Sorgulanan Domainler (İlk 30)\n")
                report.append("| Domain | Sorgu Sayısı |\n")
                report.append("|--------|--------------|\n")
                for domain, count in list(analysis.get("top_domains", {}).items())[:30]:
                    report.append(f"| {domain} | {count:,} |\n")

            if analysis.get("sample_logs"):
                report.append("\n### Örnek DNS Logları\n")
                report.append("```\n")
                for log in analysis.get("sample_logs", [])[:5]:
                    report.append(f"{log[:200]}\n")
                report.append("```\n")

        elif log_type == "telegram_logs":
            report.append(
                f"- **Toplam Telegram Log:** {analysis.get('total_telegram_logs', 0):,}\n")
            report.append(
                f"- **Başarılı İşlemler:** {analysis.get('success_count', 0):,}\n")
            report.append(
                f"- **Hatalar:** {analysis.get('error_count', 0):,}\n")
            report.append(
                f"- **Başarı Oranı:** %{analysis.get('success_rate', 0):.2f}\n")
            report.append(
                f"- **Gönderilen Bildirimler:** {analysis.get('notifications_sent', 0):,}\n")
            report.append(
                f"- **İşlenen Komutlar:** {analysis.get('commands_processed', 0):,}\n")
            report.append(
                f"- **API Çağrıları:** {analysis.get('api_calls', 0):,}\n")
            report.append(
                f"- **API Hataları:** {analysis.get('api_errors', 0):,}\n")

            if analysis.get("sample_logs"):
                report.append("\n### Örnek Telegram Logları\n")
                report.append("```\n")
                for log in analysis.get("sample_logs", [])[:10]:
                    report.append(f"{log[:200]}\n")
                report.append("```\n")

        elif log_type == "logcat":
            report.append(
                f"- **Toplam Satır:** {analysis.get('total_lines', 0):,}\n")

            report.append("\n### Log Seviyeleri\n")
            report.append("| Seviye | Sayı |\n")
            report.append("|--------|------|\n")
            for level, count in analysis.get("level_counts", {}).items():
                report.append(f"| {level} | {count:,} |\n")

            report.append("\n### En Çok Kullanılan Tag'ler\n")
            report.append("| Tag | Kullanım |\n")
            report.append("|-----|----------|\n")
            for tag, count in list(analysis.get("top_tags", {}).items())[:15]:
                report.append(f"| {tag} | {count:,} |\n")

            if analysis.get("fatal_errors"):
                report.append("\n### Fatal Hatalar\n")
                report.append("```\n")
                for error in analysis.get("fatal_errors", [])[:5]:
                    report.append(f"{error}\n")
                report.append("```\n")

        report.append("\n")

    # Sistem Bilgileri
    report.append("## 🖥️ Sistem Bilgileri\n")
    
    if system_info:
        if system_info.get("device_model"):
            report.append(f"- **Cihaz Modeli:** {system_info['device_model']}\n")
        if system_info.get("android_version"):
            report.append(f"- **Android Sürümü:** {system_info['android_version']}\n")
        if system_info.get("sdk_version"):
            report.append(f"- **SDK Sürümü:** {system_info['sdk_version']}\n")
        if system_info.get("app_version"):
            report.append(f"- **Uygulama Versiyonu:** {system_info['app_version']}\n")
    else:
        report.append("- Sistem bilgileri alınamadı\n")
    
    report.append("\n")

    # Performans Metrikleri Özeti
    report.append("## 📈 Performans Metrikleri Özeti\n")
    
    # Learner ve Runtime metrikleri
    learner_analysis = analyses.get("learner_log", {})
    runtime_analysis = analyses.get("runtime_log", {})
    
    if learner_analysis.get("status") == "analyzed":
        report.append("### Öğrenme Sistemi Metrikleri\n")
        report.append(f"- **Toplam Kayıt:** {learner_analysis.get('total_entries', 0):,}\n")
        report.append(f"- **Başarı Oranı:** %{learner_analysis.get('success_rate', 0):.2f}\n")
        report.append(f"- **Ortalama Gecikme:** {learner_analysis.get('avg_latency_ms', 0):.2f} ms\n")
        report.append(f"- **Ortalama Bant Genişliği:** {learner_analysis.get('avg_throughput_kbps', 0):.2f} kbps\n")
        report.append("\n")
    
    if runtime_analysis.get("status") == "analyzed":
        report.append("### Runtime Metrikleri\n")
        report.append(f"- **Toplam Kayıt:** {runtime_analysis.get('total_entries', 0):,}\n")
        report.append(f"- **Başarı Oranı:** %{runtime_analysis.get('success_rate', 0):.2f}\n")
        report.append(f"- **Ortalama Gecikme:** {runtime_analysis.get('avg_latency_ms', 0):.2f} ms\n")
        report.append(f"- **Ortalama Bant Genişliği:** {runtime_analysis.get('avg_throughput_kbps', 0):.2f} kbps\n")
        report.append("\n")
    
    # DNS Metrikleri
    dns_analysis = analyses.get("dns_logs", {})
    if dns_analysis.get("status") == "analyzed":
        report.append("### DNS Performans Metrikleri\n")
        report.append(f"- **Cache Hit Rate:** %{dns_analysis.get('cache_hit_rate', 0):.2f}\n")
        if dns_analysis.get("latency_stats"):
            lat_stats = dns_analysis["latency_stats"]
            report.append(f"- **Ortalama DNS Latency:** {lat_stats.get('avg_ms', 0):.2f} ms\n")
        report.append(f"- **Benzersiz Domain Sayısı:** {dns_analysis.get('unique_domains', 0):,}\n")
        report.append("\n")

    # Hata Analizi
    report.append("## ⚠️ Hata Analizi\n")
    
    total_errors = sum(
        a.get("error_count", 0) for a in analyses.values()
        if isinstance(a, dict) and "error_count" in a
    )
    
    dns_errors = analyses.get("dns_logs", {}).get("errors", 0)
    telegram_errors = analyses.get("telegram_logs", {}).get("error_count", 0)
    app_log_errors = analyses.get("app_log", {}).get("error_count", 0)
    logcat_fatals = len(analyses.get("logcat", {}).get("fatal_errors", []))
    
    report.append(f"- **Toplam Hata Sayısı:** {total_errors + dns_errors + telegram_errors + logcat_fatals:,}\n")
    report.append(f"  - App Log Hataları: {app_log_errors:,}\n")
    report.append(f"  - DNS Hataları: {dns_errors:,}\n")
    report.append(f"  - Telegram Hataları: {telegram_errors:,}\n")
    report.append(f"  - Logcat Fatal Hatalar: {logcat_fatals:,}\n")
    
    if total_errors + dns_errors + telegram_errors + logcat_fatals > 0:
        report.append("\n⚠️  **Hatalar tespit edildi.** Detaylı inceleme önerilir.\n")
    else:
        report.append("\n✅ **Kritik hata tespit edilmedi.**\n")
    
    report.append("\n")

    # Sonuç ve Öneriler
    report.append("## 💡 Sonuç ve Öneriler\n")

    report.append("### Öneriler:\n")
    report.append("1. Log dosyalarını düzenli olarak temizleyin\n")
    report.append("2. Hata loglarını düzenli olarak inceleyin\n")
    report.append("3. Performance metriklerini takip edin\n")
    report.append("4. Log rotation ayarlarını kontrol edin\n")
    report.append("5. DNS cache hit rate'i optimize edin\n")
    report.append("6. Network latency metriklerini izleyin\n")
    
    # Rapor İstatistikleri
    report.append("\n### Rapor İstatistikleri\n")
    report.append(f"- **Rapor Oluşturulma Tarihi:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
    report.append(f"- **Analiz Edilen Log Tipi:** {len([a for a in analyses.values() if a.get('status') == 'analyzed'])}\n")
    report.append(f"- **Toplam Log Dosyası:** {len([f for f in analyses.values() if f.get('status') in ['analyzed', 'not_found']])}\n")

    return "".join(report)


def delete_previous_reports():
    """Önceki rapor dosyalarını sil."""
    deleted_count = 0
    deleted_size = 0
    
    if not OUTPUT_DIR.exists():
        return 0, 0
    
    # Tüm .md rapor dosyalarını bul ve sil
    for report_file in OUTPUT_DIR.glob("log_report_*.md"):
        try:
            size = report_file.stat().st_size
            report_file.unlink()
            deleted_count += 1
            deleted_size += size
            print(f"  🗑️  Silindi: {report_file.name} ({size:,} bytes)")
        except Exception as e:
            print(f"  ⚠️  Silinemedi: {report_file.name} - {e}")
    
    return deleted_count, deleted_size


def main():
    """Ana fonksiyon."""
    print("=" * 60)
    print("HyperXray Log Collector ve Rapor Oluşturucu")
    print("=" * 60)
    print()

    # Önceki raporları sil
    print("🗑️  Önceki raporlar temizleniyor...")
    deleted_count, deleted_size = delete_previous_reports()
    if deleted_count > 0:
        print(f"✅ {deleted_count} rapor silindi (toplam {deleted_size:,} bytes)")
    else:
        print("ℹ️  Silinecek rapor bulunamadı")
    print()

    # ADB bağlantısını kontrol et
    if not check_adb_connection():
        print(
            "\n❌ ADB bağlantısı kurulamadı. Lütfen cihazınızın bağlı olduğundan emin olun.")
        sys.exit(1)

    print("\n📥 Log dosyaları çekiliyor...\n")

    # Log dosyalarını çek
    log_files = {}
    log_files["app_log"] = pull_log_file(
        DEVICE_LOG_PATHS["app_log"],
        "app_log.txt",
        check_exists=False  # Boş olsa bile çek
    )
    log_files["learner_log"] = pull_log_file(
        DEVICE_LOG_PATHS["learner_log"],
        "learner_log.jsonl",
        check_exists=False
    )
    log_files["runtime_log"] = pull_log_file(
        DEVICE_LOG_PATHS["runtime_log"],
        "tls_v5_runtime_log.jsonl",
        check_exists=False
    )
    # DNS cache dosyasını çek (cache dizini için özel işlem)
    dns_cache_result = run_adb_command(
        ["shell", "run-as", PACKAGE_NAME, "cat", "cache/dns_cache.json"])
    if dns_cache_result:
        dns_cache_local = OUTPUT_DIR / "dns_cache.json"
        dns_cache_local.write_text(
            dns_cache_result, encoding="utf-8", errors="ignore")
        size = dns_cache_local.stat().st_size
        print(f"✅ dns_cache.json çekildi ({size:,} bytes)")
        log_files["dns_cache"] = dns_cache_local
    else:
        print(f"⚠️  DNS cache dosyası bulunamadı: cache/dns_cache.json")
        log_files["dns_cache"] = None

    # Stat dosyasını çek
    log_files["stat_file"] = pull_log_file(
        DEVICE_LOG_PATHS["stat_file"],
        "stat.txt",
        check_exists=False
    )

    # Crash dosyalarını çek
    print("\n📂 Crash dosyaları kontrol ediliyor...")
    crashes_list = run_adb_command(
        ["shell", "run-as", PACKAGE_NAME, "ls", "files/crashes/"])
    if crashes_list:
        crash_files = [f.strip() for f in crashes_list.split("\n") if f.strip()]
        if crash_files:
            print(f"  {len(crash_files)} crash dosyası bulundu")
            for crash_file in crash_files[:5]:  # İlk 5 crash dosyasını çek
                crash_result = run_adb_command(
                    ["shell", "run-as", PACKAGE_NAME, "cat", f"files/crashes/{crash_file}"])
                if crash_result:
                    crash_local = OUTPUT_DIR / f"crash_{crash_file}"
                    crash_local.write_text(crash_result, encoding="utf-8", errors="ignore")
                    size = crash_local.stat().st_size
                    print(f"  ✅ crash_{crash_file} çekildi ({size:,} bytes)")

    # Tüm dosyaları listele (debug için)
    print("\n📂 Uygulama dosyaları listeleniyor...")
    files_list = run_adb_command(
        ["shell", "run-as", PACKAGE_NAME, "ls", "-la", "files/"])
    if files_list:
        print("Files dizini içeriği:")
        for line in files_list.split("\n")[:20]:  # İlk 20 satır
            if line.strip():
                print(f"  {line}")
    
    # Logs dizinini kontrol et ve tüm dosyaları çek
    print("\n📂 Logs dizini kontrol ediliyor...")
    logs_list = run_adb_command(
        ["shell", "run-as", PACKAGE_NAME, "ls", "-la", "files/logs/"])
    if logs_list:
        print("Logs dizini içeriği:")
        log_files_in_logs = []
        for line in logs_list.split("\n"):
            if line.strip() and not line.startswith("total") and not line.startswith("d"):
                # Dosya adını çıkar
                parts = line.split()
                if len(parts) >= 9:
                    filename = parts[-1]
                    if filename and filename != "." and filename != "..":
                        log_files_in_logs.append(filename)
                        print(f"  {line}")
        
        # Logs dizinindeki tüm dosyaları çek
        for log_file_name in log_files_in_logs:
            log_result = run_adb_command(
                ["shell", "run-as", PACKAGE_NAME, "cat", f"files/logs/{log_file_name}"])
            if log_result:
                log_local = OUTPUT_DIR / f"logs_{log_file_name}"
                log_local.write_text(log_result, encoding="utf-8", errors="ignore")
                size = log_local.stat().st_size
                print(f"  ✅ logs_{log_file_name} çekildi ({size:,} bytes)")

    log_files["logcat"] = collect_logcat()

    print("\n📊 Loglar analiz ediliyor...\n")

    # Logları analiz et
    analyses = {}
    analyses["app_log"] = analyze_app_log(log_files.get("app_log"))
    analyses["learner_log"] = analyze_jsonl_log(log_files.get("learner_log"))
    analyses["runtime_log"] = analyze_jsonl_log(log_files.get("runtime_log"))
    analyses["dns_cache"] = analyze_dns_cache(log_files.get("dns_cache"))
    analyses["stat_file"] = analyze_app_log(log_files.get("stat_file"))  # Stat dosyasını da analiz et
    analyses["dns_logs"] = analyze_dns_logs(
        log_files.get("app_log"), log_files.get("logcat"))
    analyses["telegram_logs"] = analyze_telegram_logs(
        log_files.get("app_log"), log_files.get("logcat"))
    analyses["logcat"] = analyze_logcat(log_files.get("logcat"))

    print("\n📝 Rapor oluşturuluyor...\n")

    # Sistem bilgilerini topla
    system_info = {}
    device_info = run_adb_command(["shell", "getprop", "ro.product.model"])
    android_version = run_adb_command(["shell", "getprop", "ro.build.version.release"])
    sdk_version = run_adb_command(["shell", "getprop", "ro.build.version.sdk"])
    
    if device_info:
        system_info["device_model"] = device_info.strip()
    if android_version:
        system_info["android_version"] = android_version.strip()
    if sdk_version:
        system_info["sdk_version"] = sdk_version.strip()
    
    # Uygulama versiyonu
    app_version_result = run_adb_command(["shell", "dumpsys", "package", PACKAGE_NAME])
    if app_version_result:
        # versionName'i parse et
        for line in app_version_result.split("\n"):
            if "versionName" in line:
                parts = line.split("=")
                if len(parts) > 1:
                    system_info["app_version"] = parts[1].strip()
                    break

    # Rapor oluştur
    report = generate_report(analyses, system_info)
    REPORT_FILE.write_text(report, encoding="utf-8")

    print(f"✅ Rapor oluşturuldu: {REPORT_FILE}")
    print(f"\n📁 Tüm log dosyaları: {OUTPUT_DIR.absolute()}")
    print("\n" + "=" * 60)
    print("Tamamlandı! ✅")
    print("=" * 60)


if __name__ == "__main__":
    main()
