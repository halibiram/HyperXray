#!/bin/bash

# 🔧 MCP Sorunlarını Düzeltme Script'i

echo "🔧 MCP sorunlarını düzeltiyorum..."

# 1. uv PATH sorununu çöz
echo "1. uv PATH sorununu çözüyorum..."
export PATH="/c/Users/halil/.local/bin:$PATH"

# 2. Çalışan MCP server'larını doğrula
echo "2. Çalışan MCP server'larını doğruluyorum..."

# Brave Search test
echo "🧪 Brave Search MCP..."
if npx -y @brave/brave-search-mcp-server --help > /dev/null 2>&1; then
    echo "✅ Brave Search: Çalışıyor"
else
    echo "❌ Brave Search: Sorun var"
fi

# Memory Bank test
echo "🧪 Memory Bank MCP..."
if npx -y memory-bank-mcp --help > /dev/null 2>&1; then
    echo "✅ Memory Bank: Çalışıyor"
else
    echo "❌ Memory Bank: Sorun var"
fi

# Playwright test
echo "🧪 Better Playwright MCP..."
if npx -y better-playwright-mcp3@latest --help > /dev/null 2>&1; then
    echo "✅ Playwright: Çalışıyor"
else
    echo "❌ Playwright: Sorun var"
fi

# 3. AWS MCP alternatif çözümü
echo "3. AWS MCP için alternatif çözümler hazırlıyorum..."
echo "AWS MCP henüz PyPI'de yayınlanmamış. Alternatifler:"
echo "  - AWS CLI entegrasyonu"
echo "  - Custom AWS MCP server geliştirme"
echo "  - Manual AWS API çağrıları"

# 4. GitHub MCP alternatif çözümü
echo "4. GitHub MCP için alternatif çözümler hazırlıyorum..."
echo "Docker kurulumu gerekli. Kurulum seçenekleri:"
echo "  - Docker Desktop: https://www.docker.com/products/docker-desktop"
echo "  - WSL2 + Docker"
echo "  - Alternatif: GitHub CLI + REST API"

# 5. Güncellenmiş Cursor konfigurasyonu
echo "5. Güncellenmiş Cursor MCP konfigurasyonu:"

cat << 'EOF'
{
  "mcpServers": {
    "brave-search": {
      "command": "npx",
      "args": ["-y", "@brave/brave-search-mcp-server"]
    },
    "memory-bank": {
      "command": "npx",
      "args": ["-y", "memory-bank-mcp", "serve"]
    },
    "playwright": {
      "command": "npx",
      "args": ["-y", "better-playwright-mcp3@latest", "mcp"]
    }
  }
}
EOF

echo ""
echo "📋 Özet:"
echo "✅ Çalışan: Brave Search, Memory Bank, Playwright"
echo "⚠️  Bekleyen: GitHub MCP (Docker gerekli)"
echo "⚠️  Bekleyen: AWS MCP (paket henüz yayınlanmamış)"
echo ""
echo "🎯 Öneri: Önce çalışan 3 MCP'yi Cursor'a ekleyin, sonra eksik olanları tamamlayın."


