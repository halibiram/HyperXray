# 🔄 MCP Server Alternatifleri

## GitHub MCP (Docker gerekli)
**Şu an:** Docker olmadan çalışmıyor
**Alternatif:** GitHub CLI entegrasyonu veya web API kullanımı

## AWS MCP (uv sorunu)
**Şu an:** Paket bulunamadı
**Alternatif:** AWS CLI + custom script veya farklı MCP server

## Önerilen Kurulum Planı:

### Faz 1: Çalışan MCP'leri Kur
```json
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
```

### Faz 2: Docker Kurulumu (İsteğe bağlı)
1. Docker Desktop indirin: https://www.docker.com/products/docker-desktop
2. GitHub MCP'yi ekleyin:
```json
"github": {
  "command": "docker",
  "args": ["run", "-i", "--rm", "ghcr.io/github/github-mcp-server:latest"],
  "env": {
    "GITHUB_PERSONAL_ACCESS_TOKEN": "your_token"
  }
}
```

### Faz 3: AWS MCP Alternatifi
AWS servisleri için custom MCP server geliştirebiliriz veya alternatif araçlar kullanabiliriz.


