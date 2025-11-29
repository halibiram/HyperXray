#!/bin/bash

# Eksik MCP araçlarını kurma script'i

echo "🔧 Eksik MCP araçlarını kuruyorum..."

# uv kurulumu (Python paket yöneticisi)
if ! command -v uv &> /dev/null; then
    echo "📦 uv kuruluyor..."
    curl -LsSf https://astral.sh/uv/install.sh | sh
    export PATH="$HOME/.cargo/bin:$PATH"
    echo "✅ uv kuruldu"
else
    echo "✅ uv zaten kurulu"
fi

# Docker kontrolü ve kurulum önerisi
if ! command -v docker &> /dev/null; then
    echo "🐳 Docker bulunamadı."
    echo "Docker'ı kurmak için:"
    echo "- Windows: https://docs.docker.com/desktop/install/windows/"
    echo "- WSL/Linux: https://docs.docker.com/engine/install/"
    echo ""
    echo "Alternatif: Docker olmadan GitHub MCP'yi kullanmak için npx versiyonu mevcut:"
    echo "npx -y @github/github-mcp-server"
else
    echo "✅ Docker zaten kurulu"
fi

# AWS CLI kontrolü (AWS MCP için)
if ! command -v aws &> /dev/null; then
    echo "☁️ AWS CLI kuruluyor..."
    curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
    unzip awscliv2.zip
    sudo ./aws/install
    rm -rf aws awscliv2.zip
    echo "✅ AWS CLI kuruldu"
else
    echo "✅ AWS CLI zaten kurulu"
fi

echo ""
echo "🎯 Tüm araçlar kuruldu! Şimdi setup_mcp_servers.sh'yi tekrar çalıştırın."


