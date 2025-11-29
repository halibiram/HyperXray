#!/usr/bin/env python3
"""
MCP Helper Script - HyperXray Project
Proje-spesifik MCP araçları için helper fonksiyonlar
"""

import json
import os
from pathlib import Path
from typing import Dict, List, Optional
from datetime import datetime


class MCPHelper:
    """MCP araçları için helper class"""
    
    def __init__(self, project_root: Optional[str] = None):
        self.project_root = Path(project_root or os.getcwd())
        self.memory_dir = self.project_root / ".superclaude" / "memory"
        self.registry_file = self.project_root / ".superclaude" / "mcp_tool_registry.md"
        
    def log_tool_usage(self, tool_name: str, purpose: str, result: str = "success"):
        """MCP tool kullanımını logla"""
        log_entry = {
            "timestamp": datetime.now().isoformat(),
            "tool": tool_name,
            "purpose": purpose,
            "result": result
        }
        
        log_file = self.memory_dir / "mcp_usage_log.json"
        
        # Mevcut logları oku
        if log_file.exists():
            with open(log_file, "r", encoding="utf-8") as f:
                logs = json.load(f)
        else:
            logs = []
        
        # Yeni log ekle
        logs.append(log_entry)
        
        # Son 100 log'u tut
        logs = logs[-100:]
        
        # Kaydet
        with open(log_file, "w", encoding="utf-8") as f:
            json.dump(logs, f, indent=2, ensure_ascii=False)
    
    def get_tool_recommendation(self, task_description: str) -> List[str]:
        """Görev açıklamasına göre önerilen MCP araçlarını döndür"""
        task_lower = task_description.lower()
        
        recommendations = []
        
        # Research tasks
        if any(word in task_lower for word in ["ara", "research", "bul", "find", "search"]):
            recommendations.append("brave-search")
            recommendations.append("context7")
        
        # Memory tasks
        if any(word in task_lower for word in ["kaydet", "not", "hafıza", "memory", "remember"]):
            recommendations.append("memory-bank")
        
        # Web tasks
        if any(word in task_lower for word in ["web", "browser", "sayfa", "page", "url"]):
            recommendations.append("playwright")
        
        # GitHub tasks
        if any(word in task_lower for word in ["github", "issue", "pr", "pull request", "repo"]):
            recommendations.append("custom-github")
        
        # AWS tasks
        if any(word in task_lower for word in ["aws", "s3", "ec2", "lambda", "infrastructure"]):
            recommendations.append("custom-aws")
        
        # Documentation tasks
        if any(word in task_lower for word in ["doc", "api", "dokümantasyon", "library", "kütüphane"]):
            recommendations.append("context7")
            recommendations.append("brave-search")
        
        return list(set(recommendations))  # Remove duplicates
    
    def update_tool_registry(self, tool_name: str, status: str, notes: str = ""):
        """MCP tool registry'yi güncelle"""
        # Bu fonksiyon registry markdown dosyasını günceller
        # Şimdilik basit bir implementasyon
        pass
    
    def get_memory_context(self) -> Dict:
        """Mevcut memory context'ini döndür"""
        context = {}
        
        # Project status
        status_file = self.memory_dir / "project_status.md"
        if status_file.exists():
            context["project_status"] = status_file.read_text(encoding="utf-8")
        
        # Learned lessons
        lessons_file = self.memory_dir / "learned_lessons.md"
        if lessons_file.exists():
            context["learned_lessons"] = lessons_file.read_text(encoding="utf-8")
        
        # Active context
        active_file = self.memory_dir / "active_context.md"
        if active_file.exists():
            context["active_context"] = active_file.read_text(encoding="utf-8")
        
        return context
    
    def suggest_mcp_workflow(self, task_description: str) -> Dict:
        """Görev için önerilen MCP workflow'unu döndür"""
        recommendations = self.get_tool_recommendation(task_description)
        context = self.get_memory_context()
        
        workflow = {
            "task": task_description,
            "recommended_tools": recommendations,
            "workflow_steps": [],
            "context_available": bool(context)
        }
        
        # Workflow steps oluştur
        if "brave-search" in recommendations or "context7" in recommendations:
            workflow["workflow_steps"].append({
                "step": 1,
                "action": "Research",
                "tools": [t for t in recommendations if t in ["brave-search", "context7"]],
                "description": "Görevle ilgili araştırma yap"
            })
        
        if "memory-bank" in recommendations:
            workflow["workflow_steps"].append({
                "step": len(workflow["workflow_steps"]) + 1,
                "action": "Memory Check",
                "tools": ["memory-bank"],
                "description": "İlgili hafıza kayıtlarını kontrol et"
            })
        
        workflow["workflow_steps"].append({
            "step": len(workflow["workflow_steps"]) + 1,
            "action": "Implementation",
            "tools": [],
            "description": "Araştırma sonuçlarına göre implementasyon yap"
        })
        
        if "memory-bank" in recommendations:
            workflow["workflow_steps"].append({
                "step": len(workflow["workflow_steps"]) + 1,
                "action": "Save Results",
                "tools": ["memory-bank"],
                "description": "Sonuçları hafızaya kaydet"
            })
        
        return workflow


def main():
    """CLI interface"""
    import sys
    
    helper = MCPHelper()
    
    if len(sys.argv) < 2:
        print("Usage: python mcp_helper.py <command> [args]")
        print("Commands:")
        print("  recommend <task_description> - Get tool recommendations")
        print("  workflow <task_description> - Get workflow suggestion")
        print("  log <tool_name> <purpose> - Log tool usage")
        return
    
    command = sys.argv[1]
    
    if command == "recommend":
        if len(sys.argv) < 3:
            print("Error: Task description required")
            return
        task = " ".join(sys.argv[2:])
        recommendations = helper.get_tool_recommendation(task)
        print(f"Recommended tools for '{task}':")
        for tool in recommendations:
            print(f"  - {tool}")
    
    elif command == "workflow":
        if len(sys.argv) < 3:
            print("Error: Task description required")
            return
        task = " ".join(sys.argv[2:])
        workflow = helper.suggest_mcp_workflow(task)
        print(json.dumps(workflow, indent=2, ensure_ascii=False))
    
    elif command == "log":
        if len(sys.argv) < 4:
            print("Error: Tool name and purpose required")
            return
        tool_name = sys.argv[2]
        purpose = " ".join(sys.argv[3:])
        helper.log_tool_usage(tool_name, purpose)
        print(f"Logged: {tool_name} - {purpose}")
    
    else:
        print(f"Unknown command: {command}")


if __name__ == "__main__":
    main()


