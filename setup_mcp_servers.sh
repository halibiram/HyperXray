#!/bin/bash

# 🚀 SuperClaude MCP Arsenal Setup Script
# Bu script Cursor MCP server'larını otomatik kurar

echo "🚀 SuperClaude MCP Arsenal Kurulumu Başlıyor..."

# Gerekli paketleri kontrol et
echo "📦 Gerekli paketleri kontrol ediyorum..."

# npx kontrolü
if ! command -v npx &> /dev/null; then
    echo "❌ npx bulunamadı. Node.js kurmanız gerekiyor."
    exit 1
fi

# uv kontrolü
if ! command -v uv &> /dev/null; then
    echo "⚠️  uv bulunamadı. AWS MCP için gerekli. Kurmak için: pip install uv"
fi

# Docker kontrolü
if ! command -v docker &> /dev/null; then
    echo "⚠️  Docker bulunamadı. GitHub MCP için gerekli."
fi

echo "✅ Temel kontroller tamamlandı."

# MCP Server'ları test et
echo "🧪 MCP Server'larını test ediyorum..."

# Brave Search MCP test
echo "🧪 Brave Search MCP test ediliyor..."
npx -y @brave/brave-search-mcp-server --help > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo "✅ Brave Search MCP: Hazır"
else
    echo "❌ Brave Search MCP: Kurulum gerekli - npx -y @brave/brave-search-mcp-server"
fi

# Memory Bank MCP test
echo "🧪 Memory Bank MCP test ediliyor..."
npx -y memory-bank-mcp --help > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo "✅ Memory Bank MCP: Hazır"
else
    echo "❌ Memory Bank MCP: Kurulum gerekli - npm install -g memory-bank-mcp"
fi

# Playwright MCP test
echo "🧪 Better Playwright MCP test ediliyor..."
npx -y better-playwright-mcp3@latest --help > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo "✅ Better Playwright MCP: Hazır"
else
    echo "❌ Better Playwright MCP: Kurulum gerekli - npm install -g better-playwright-mcp3"
fi

# AWS MCP test (uv varsa)
if command -v uv &> /dev/null; then
    echo "🧪 AWS MCP test ediliyor..."
    uvx awslabs.mcp@latest --help > /dev/null 2>&1
    if [ $? -eq 0 ]; then
        echo "✅ AWS MCP: Hazır"
    else
        echo "❌ AWS MCP: Kurulum gerekli - uvx awslabs.mcp@latest"
    fi
else
    echo "⚠️  AWS MCP: uv yüklü değil, atlanıyor"
fi

# Docker test (GitHub MCP için)
if command -v docker &> /dev/null; then
    echo "🧪 GitHub MCP Docker imajı kontrol ediliyor..."
    docker pull ghcr.io/github/github-mcp-server:latest > /dev/null 2>&1
    if [ $? -eq 0 ]; then
        echo "✅ GitHub MCP: Hazır"
    else
        echo "❌ GitHub MCP: Docker imajı çekilemedi"
    fi
else
    echo "⚠️  GitHub MCP: Docker yüklü değil, atlanıyor"
fi

echo ""
echo "🎯 Cursor MCP Konfigurasyon JSON'u:"
echo ""
cat << 'EOF'
{
  "mcpServers": {
    "brave-search": {
      "command": "npx",
      "args": ["-y", "@brave/brave-search-mcp-server"]
    },
    "github": {
      "command": "docker",
      "args": ["run", "-i", "--rm", "ghcr.io/github/github-mcp-server:latest"],
      "env": {
        "GITHUB_PERSONAL_ACCESS_TOKEN": "your_github_token_here"
      }
    },
    "aws": {
      "command": "uvx",
      "args": ["awslabs.mcp@latest"]
    },
    "memory-bank": {
      "command": "npx",
      "args": ["-y", "memory-bank-mcp"]
    },
    "playwright": {
      "command": "npx",
      "args": ["-y", "better-playwright-mcp3@latest"]
    }
  }
}
EOF

echo ""
echo "📋 Manuel Kurulum Adımları:"
echo "1. Cursor'u açın"
echo "2. Ctrl+, (Settings) → Features → MCP"
echo "3. Yukarıdaki JSON'u yapıştırın"
echo "4. API key'lerini ayarlayın:"
echo "   - GITHUB_PERSONAL_ACCESS_TOKEN"
echo "   - BRAVE_API_KEY (opsiyonel)"
echo "5. Cursor'u yeniden başlatın"

echo ""
echo "🧪 Test Komutları:"
echo "'Brave ile MCP server ara'"
echo "'GitHub'da issue'ları listele'"
echo "'AWS servislerini göster'"
echo "'Memory bank'a not ekle'"
echo "'Web sayfasını automate et'"

echo ""
echo "✅ Kurulum tamamlandı! Cursor'unuz artık Ultimate MCP Arsenal'a sahip."


