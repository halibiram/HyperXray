#!/usr/bin/env python3
"""
HyperXray Log Collector v2.0 - Advanced Log Analysis & Reporting

Modern features:
- Async ADB operations with asyncio
- Rich terminal UI with progress bars
- Machine learning-based anomaly detection
- Real-time log streaming
- JSON/HTML/Markdown report generation
- SQLite log database for historical analysis
- Prometheus metrics export
"""

import asyncio
import json
import os
import sys
import sqlite3
import hashlib
from datetime import datetime, timedelta
from pathlib import Path
from collections import defaultdict, Counter
from typing import Dict, List, Any, Optional, Tuple
from dataclasses import dataclass, field, asdict
from enum import Enum
import re
import statistics
import argparse

# Optional imports with fallbacks
try:
    from rich.console import Console
    from rich.progress import Progress, SpinnerColumn, TextColumn, BarColumn
    from rich.table import Table
    from rich.panel import Panel
    from rich.live import Live
    RICH_AVAILABLE = True
except ImportError:
    RICH_AVAILABLE = False

try:
    import numpy as np
    from sklearn.ensemble import IsolationForest
    from sklearn.preprocessing import StandardScaler
    ML_AVAILABLE = True
except ImportError:
    ML_AVAILABLE = False

# Package configuration
PACKAGE_NAME = "com.hyperxray.an"
OUTPUT_DIR = Path("tools/logs_collected")
DB_PATH = OUTPUT_DIR / "logs_history.db"


class LogLevel(Enum):
    DEBUG = "DEBUG"
    INFO = "INFO"
    WARN = "WARN"
    ERROR = "ERROR"
    FATAL = "FATAL"


@dataclass
class LogEntry:
    timestamp: str
    level: LogLevel
    tag: str
    message: str
    source: str
    line_number: int = 0
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class AnalysisResult:
    status: str
    total_entries: int = 0
    error_count: int = 0
    warning_count: int = 0
    anomalies: List[Dict] = field(default_factory=list)
    metrics: Dict[str, Any] = field(default_factory=dict)
    recommendations: List[str] = field(default_factory=list)


class AsyncADBClient:
    """Async ADB client for non-blocking operations."""
    
    def __init__(self):
        self.console = Console() if RICH_AVAILABLE else None
    
    async def run_command(self, args: List[str], timeout: int = 30) -> Optional[str]:
        """Execute ADB command asynchronously."""
        try:
            proc = await asyncio.create_subprocess_exec(
                "adb", *args,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            stdout, stderr = await asyncio.wait_for(
                proc.communicate(), timeout=timeout
            )
            
            if proc.returncode == 0:
                return stdout.decode("utf-8", errors="ignore").strip()
            else:
                error = stderr.decode("utf-8", errors="ignore").strip()
                self._log_warning(f"ADB command failed: {' '.join(args)}\n{error}")
                return None
        except asyncio.TimeoutError:
            self._log_error(f"ADB command timed out: {' '.join(args)}")
            return None
        except FileNotFoundError:
            self._log_error("ADB not found! Please install Android SDK.")
            sys.exit(1)
        except Exception as e:
            self._log_error(f"ADB error: {e}")
            return None
    
    async def check_connection(self) -> bool:
        """Check ADB device connection."""
        result = await self.run_command(["devices"])
        if result:
            lines = result.split("\n")[1:]
            devices = [l for l in lines if l.strip() and "device" in l]
            if devices:
                self._log_success(f"Connected to {len(devices)} device(s)")
                return True
        self._log_error("No devices connected")
        return False
    
    async def pull_file(self, device_path: str, local_name: str) -> Optional[Path]:
        """Pull file from device using run-as."""
        local_path = OUTPUT_DIR / local_name
        relative_path = device_path.replace(f"/data/data/{PACKAGE_NAME}/", "")
        
        result = await self.run_command([
            "shell", "run-as", PACKAGE_NAME, "cat", relative_path
        ])
        
        if result:
            local_path.write_text(result, encoding="utf-8", errors="ignore")
            size = local_path.stat().st_size
            self._log_success(f"Pulled {local_name} ({size:,} bytes)")
            return local_path
        return None
    
    async def stream_logcat(self, callback, duration: int = 10):
        """Stream logcat in real-time."""
        proc = await asyncio.create_subprocess_exec(
            "adb", "logcat", "-v", "time",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )
        
        try:
            end_time = asyncio.get_event_loop().time() + duration
            while asyncio.get_event_loop().time() < end_time:
                line = await asyncio.wait_for(
                    proc.stdout.readline(), timeout=1.0
                )
                if line:
                    decoded = line.decode("utf-8", errors="ignore").strip()
                    if PACKAGE_NAME in decoded or "HyperXray" in decoded:
                        await callback(decoded)
        except asyncio.TimeoutError:
            pass
        finally:
            proc.terminate()
    
    def _log_success(self, msg: str):
        if self.console:
            self.console.print(f"[green]✓[/green] {msg}")
        else:
            print(f"✓ {msg}")
    
    def _log_warning(self, msg: str):
        if self.console:
            self.console.print(f"[yellow]⚠[/yellow] {msg}")
        else:
            print(f"⚠ {msg}")
    
    def _log_error(self, msg: str):
        if self.console:
            self.console.print(f"[red]✗[/red] {msg}")
        else:
            print(f"✗ {msg}")


class LogDatabase:
    """SQLite database for log history and analysis."""
    
    def __init__(self, db_path: Path):
        self.db_path = db_path
        self._init_db()
    
    def _init_db(self):
        """Initialize database schema."""
        with sqlite3.connect(self.db_path) as conn:
            conn.executescript("""
                CREATE TABLE IF NOT EXISTS log_sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp TEXT NOT NULL,
                    device_id TEXT,
                    app_version TEXT,
                    total_entries INTEGER,
                    error_count INTEGER,
                    anomaly_count INTEGER
                );
                
                CREATE TABLE IF NOT EXISTS log_entries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER,
                    timestamp TEXT,
                    level TEXT,
                    tag TEXT,
                    message TEXT,
                    hash TEXT UNIQUE,
                    FOREIGN KEY (session_id) REFERENCES log_sessions(id)
                );
                
                CREATE TABLE IF NOT EXISTS anomalies (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER,
                    entry_id INTEGER,
                    anomaly_type TEXT,
                    score REAL,
                    description TEXT,
                    FOREIGN KEY (session_id) REFERENCES log_sessions(id),
                    FOREIGN KEY (entry_id) REFERENCES log_entries(id)
                );
                
                CREATE TABLE IF NOT EXISTS metrics (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER,
                    metric_name TEXT,
                    metric_value REAL,
                    timestamp TEXT,
                    FOREIGN KEY (session_id) REFERENCES log_sessions(id)
                );
                
                CREATE INDEX IF NOT EXISTS idx_entries_session ON log_entries(session_id);
                CREATE INDEX IF NOT EXISTS idx_entries_level ON log_entries(level);
                CREATE INDEX IF NOT EXISTS idx_entries_tag ON log_entries(tag);
            """)
    
    def create_session(self, device_id: str = None, app_version: str = None) -> int:
        """Create new log collection session."""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.execute("""
                INSERT INTO log_sessions (timestamp, device_id, app_version, total_entries, error_count, anomaly_count)
                VALUES (?, ?, ?, 0, 0, 0)
            """, (datetime.now().isoformat(), device_id, app_version))
            return cursor.lastrowid
    
    def add_entry(self, session_id: int, entry: LogEntry):
        """Add log entry to database."""
        entry_hash = hashlib.md5(
            f"{entry.timestamp}{entry.tag}{entry.message}".encode()
        ).hexdigest()
        
        with sqlite3.connect(self.db_path) as conn:
            try:
                conn.execute("""
                    INSERT OR IGNORE INTO log_entries 
                    (session_id, timestamp, level, tag, message, hash)
                    VALUES (?, ?, ?, ?, ?, ?)
                """, (session_id, entry.timestamp, entry.level.value, 
                      entry.tag, entry.message[:1000], entry_hash))
            except sqlite3.IntegrityError:
                pass  # Duplicate entry
    
    def add_anomaly(self, session_id: int, entry_id: int, 
                    anomaly_type: str, score: float, description: str):
        """Record detected anomaly."""
        with sqlite3.connect(self.db_path) as conn:
            conn.execute("""
                INSERT INTO anomalies (session_id, entry_id, anomaly_type, score, description)
                VALUES (?, ?, ?, ?, ?)
            """, (session_id, entry_id, anomaly_type, score, description))
    
    def get_historical_stats(self, days: int = 7) -> Dict[str, Any]:
        """Get historical statistics for comparison."""
        cutoff = (datetime.now() - timedelta(days=days)).isoformat()
        
        with sqlite3.connect(self.db_path) as conn:
            conn.row_factory = sqlite3.Row
            
            # Session stats
            sessions = conn.execute("""
                SELECT COUNT(*) as count, 
                       AVG(total_entries) as avg_entries,
                       AVG(error_count) as avg_errors,
                       AVG(anomaly_count) as avg_anomalies
                FROM log_sessions WHERE timestamp > ?
            """, (cutoff,)).fetchone()
            
            # Error trends
            errors = conn.execute("""
                SELECT DATE(timestamp) as date, COUNT(*) as count
                FROM log_entries 
                WHERE level = 'ERROR' AND timestamp > ?
                GROUP BY DATE(timestamp)
                ORDER BY date
            """, (cutoff,)).fetchall()
            
            return {
                "sessions": dict(sessions) if sessions else {},
                "error_trends": [dict(e) for e in errors]
            }


class AnomalyDetector:
    """ML-based anomaly detection for logs."""
    
    def __init__(self):
        self.model = None
        self.scaler = None
        if ML_AVAILABLE:
            self.model = IsolationForest(
                contamination=0.1,
                random_state=42,
                n_estimators=100
            )
            self.scaler = StandardScaler()
    
    def extract_features(self, entries: List[LogEntry]) -> np.ndarray:
        """Extract numerical features from log entries."""
        if not ML_AVAILABLE or not entries:
            return np.array([])
        
        features = []
        for entry in entries:
            # Feature vector: [message_length, level_score, hour, has_exception, has_numbers]
            level_scores = {
                LogLevel.DEBUG: 0, LogLevel.INFO: 1, 
                LogLevel.WARN: 2, LogLevel.ERROR: 3, LogLevel.FATAL: 4
            }
            
            try:
                hour = int(entry.timestamp.split()[1].split(":")[0]) if entry.timestamp else 12
            except:
                hour = 12
            
            features.append([
                len(entry.message),
                level_scores.get(entry.level, 1),
                hour,
                1 if "exception" in entry.message.lower() else 0,
                len(re.findall(r'\d+', entry.message))
            ])
        
        return np.array(features)
    
    def detect(self, entries: List[LogEntry]) -> List[Tuple[LogEntry, float]]:
        """Detect anomalous log entries."""
        if not ML_AVAILABLE or len(entries) < 10:
            return []
        
        features = self.extract_features(entries)
        if features.size == 0:
            return []
        
        # Fit and predict
        scaled = self.scaler.fit_transform(features)
        predictions = self.model.fit_predict(scaled)
        scores = self.model.decision_function(scaled)
        
        # Return anomalies (prediction == -1)
        anomalies = []
        for i, (pred, score) in enumerate(zip(predictions, scores)):
            if pred == -1:
                anomalies.append((entries[i], float(score)))
        
        return sorted(anomalies, key=lambda x: x[1])[:20]  # Top 20 anomalies
