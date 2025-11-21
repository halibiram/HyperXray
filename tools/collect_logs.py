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


def pull_log_file(device_path: str, local_name: str) -> Optional[Path]:
    """Cihazdan log dosyasını çek."""
    local_path = OUTPUT_DIR / local_name

    # Önce dosyanın var olup olmadığını kontrol et
    result = run_adb_command(
        ["shell", "run-as", PACKAGE_NAME, "test", "-f", device_path])
    if result is None:
        print(f"⚠️  Log dosyası bulunamadı: {device_path}")
        return None

    # Dosyayı çek
    result = run_adb_command(
        ["shell", "run-as", PACKAGE_NAME, "cat", device_path])
    if result:
        local_path.write_text(result, encoding="utf-8", errors="ignore")
        size = local_path.stat().st_size
        print(f"✅ {local_name} çekildi ({size:,} bytes)")
        return local_path
    else:
        print(f"⚠️  {local_name} çekilemedi")
        return None


def collect_logcat(tag_filters: List[str] = None, lines: int = 10000) -> Optional[Path]:
    """Logcat'ten logları topla."""
    local_path = OUTPUT_DIR / "logcat.txt"

    # Önce tüm logları çek (filtreleme sonra yapılabilir)
    # -d: dump and exit, -v time: timestamp ekle
    command = ["logcat", "-d", "-v", "time"]

    # Son N satırı al
    command.extend(["-t", str(lines)])

    result = run_adb_command(command)
    if result:
        # Package ile ilgili logları filtrele (DNS cache loglarını da dahil et)
        filtered_lines = []
        dns_tags = ["SystemDnsCacheServer", "DnsCacheManager", "DNS", "dns"]
        for line in result.split("\n"):
            if (PACKAGE_NAME in line or "HyperXray" in line or "TProxyService" in line or
                    "XrayRuntime" in line or any(tag in line for tag in dns_tags)):
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
    """DNS cache dosyasını analiz et."""
    if not cache_file or not cache_file.exists():
        return {"status": "not_found"}

    try:
        content = cache_file.read_text(encoding="utf-8", errors="ignore")
        cache_data = json.loads(content)

        # Cache istatistikleri
        entries = cache_data.get("entries", {})
        total_entries = len(entries)

        # TTL analizi
        ttls = []
        for entry_data in entries.values():
            if isinstance(entry_data, dict):
                ttl = entry_data.get("ttl", 0)
                if ttl > 0:
                    ttls.append(ttl)

        avg_ttl = sum(ttls) / len(ttls) if ttls else 0

        # Domain analizi
        domains = list(entries.keys())
        popular_domains = [d for d in domains if any(
            p in d for p in ["google", "facebook", "youtube", "twitter", "instagram"])]

        return {
            "status": "analyzed",
            "total_entries": total_entries,
            "avg_ttl_seconds": round(avg_ttl, 2),
            "popular_domains_count": len(popular_domains),
            "sample_domains": domains[:20]
        }
    except json.JSONDecodeError:
        return {"status": "invalid_json"}
    except Exception as e:
        return {"status": "error", "error": str(e)}


def analyze_dns_logs(app_log_file: Path, logcat_file: Path) -> Dict[str, Any]:
    """DNS loglarını analiz et (app_log ve logcat'ten filtrele)."""
    dns_logs = []
    dns_cache_hits = 0
    dns_cache_misses = 0
    dns_resolved = 0
    dns_errors = 0
    dns_unhealthy_servers = 0
    dns_invalid_data = 0
    domains_queried = Counter()

    # DNS ile ilgili tag'ler
    dns_tags = ["SystemDnsCacheServer", "DnsCacheManager", "DNS", "dns"]

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

                # Hata kontrolü
                if "ERROR" in line_upper or "FAILED" in line_upper() or "EXCEPTION" in line_upper:
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
        "top_domains": dict(domains_queried.most_common(20)),
        "sample_logs": dns_logs[:15]
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


def generate_report(analyses: Dict[str, Any]) -> str:
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
                f"- **Ortalama TTL:** {analysis.get('avg_ttl_seconds', 0):.2f} saniye\n")
            report.append(
                f"- **Popüler Domain Sayısı:** {analysis.get('popular_domains_count', 0)}\n")

            if analysis.get("sample_domains"):
                report.append("\n### Örnek Domainler\n")
                report.append("| Domain |\n")
                report.append("|--------|\n")
                for domain in analysis.get("sample_domains", [])[:20]:
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

            if analysis.get("top_domains"):
                report.append("\n### En Çok Sorgulanan Domainler\n")
                report.append("| Domain | Sorgu Sayısı |\n")
                report.append("|--------|--------------|\n")
                for domain, count in list(analysis.get("top_domains", {}).items())[:20]:
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

    # Sonuç ve Öneriler
    report.append("## 💡 Sonuç ve Öneriler\n")

    total_errors = sum(
        a.get("error_count", 0) for a in analyses.values()
        if isinstance(a, dict) and "error_count" in a
    )

    if total_errors > 0:
        report.append(
            f"⚠️  **{total_errors} hata tespit edildi.** Detaylı inceleme önerilir.\n")
    else:
        report.append("✅ **Kritik hata tespit edilmedi.**\n")

    report.append("\n### Öneriler:\n")
    report.append("1. Log dosyalarını düzenli olarak temizleyin\n")
    report.append("2. Hata loglarını düzenli olarak inceleyin\n")
    report.append("3. Performance metriklerini takip edin\n")
    report.append("4. Log rotation ayarlarını kontrol edin\n")

    return "".join(report)


def main():
    """Ana fonksiyon."""
    print("=" * 60)
    print("HyperXray Log Collector ve Rapor Oluşturucu")
    print("=" * 60)
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
        "app_log.txt"
    )
    log_files["learner_log"] = pull_log_file(
        DEVICE_LOG_PATHS["learner_log"],
        "learner_log.jsonl"
    )
    log_files["runtime_log"] = pull_log_file(
        DEVICE_LOG_PATHS["runtime_log"],
        "tls_v5_runtime_log.jsonl"
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

    log_files["logcat"] = collect_logcat()

    print("\n📊 Loglar analiz ediliyor...\n")

    # Logları analiz et
    analyses = {}
    analyses["app_log"] = analyze_app_log(log_files.get("app_log"))
    analyses["learner_log"] = analyze_jsonl_log(log_files.get("learner_log"))
    analyses["runtime_log"] = analyze_jsonl_log(log_files.get("runtime_log"))
    analyses["dns_cache"] = analyze_dns_cache(log_files.get("dns_cache"))
    analyses["dns_logs"] = analyze_dns_logs(
        log_files.get("app_log"), log_files.get("logcat"))
    analyses["telegram_logs"] = analyze_telegram_logs(
        log_files.get("app_log"), log_files.get("logcat"))
    analyses["logcat"] = analyze_logcat(log_files.get("logcat"))

    print("\n📝 Rapor oluşturuluyor...\n")

    # Rapor oluştur
    report = generate_report(analyses)
    REPORT_FILE.write_text(report, encoding="utf-8")

    print(f"✅ Rapor oluşturuldu: {REPORT_FILE}")
    print(f"\n📁 Tüm log dosyaları: {OUTPUT_DIR.absolute()}")
    print("\n" + "=" * 60)
    print("Tamamlandı! ✅")
    print("=" * 60)


if __name__ == "__main__":
    main()
