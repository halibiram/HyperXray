# 🔌 MCP Tool Registry - HyperXray Project

Bu dosya, projede kullanılan tüm MCP araçlarının kayıt defteridir.

## 📋 MCP Server Status

| Server        | Status       | Purpose              | Tools Count | Last Updated |
| ------------- | ------------ | -------------------- | ----------- | ------------ |
| brave-search  | ✅ Active    | Web search, research | 5+          | 2025-01-XX   |
| memory-bank   | ✅ Active    | Long-term memory     | 10+         | 2025-01-XX   |
| playwright    | ✅ Active    | Browser automation   | 15+         | 2025-01-XX   |
| custom-github | ✅ Active    | GitHub operations    | 5           | 2025-01-XX   |
| custom-aws    | ✅ Active    | AWS services         | 4           | 2025-01-XX   |
| context7      | ✅ Available | Library docs         | Dynamic     | 2025-01-XX   |

## 🛠️ Tool Usage Guide

### brave-search

**Purpose**: Web search, current information, best practices

**Available Tools**:

- `brave_web_search`: General web search
- `brave_news_search`: News articles
- `brave_image_search`: Image search
- `brave_video_search`: Video search
- `brave_local_search`: Local business search

**Usage Examples**:

```
"Brave ile Kotlin coroutines best practices ara"
"Brave ile Android VPN service memory leak çözümleri ara"
```

**When to Use**:

- Library version compatibility
- Current best practices
- Troubleshooting solutions
- API changes

### memory-bank

**Purpose**: Long-term memory, project notes, decisions

**Available Tools**:

- `memory-bank-create`: Create new memory
- `memory-bank-read`: Read memory
- `memory-bank-update`: Update memory
- `memory-bank-delete`: Delete memory
- `memory-bank-search`: Search memories

**Usage Examples**:

```
"Memory bank'a bu kararı kaydet: JNI memory management için RAII pattern kullanılacak"
"Memory bank'tan JNI ile ilgili notları göster"
```

**When to Use**:

- Important decisions
- Project notes
- Context preservation
- Long-term knowledge

### playwright

**Purpose**: Browser automation, web testing, scraping

**Available Tools**:

- `browser_navigate`: Navigate to URL
- `browser_snapshot`: Capture page snapshot
- `browser_click`: Click element
- `browser_type`: Type text
- `browser_take_screenshot`: Screenshot

**Usage Examples**:

```
"Playwright ile bu URL'yi aç ve içeriği çıkar"
"Playwright ile web sayfasını test et"
```

**When to Use**:

- Web UI testing
- API documentation scraping
- E2E testing
- Web automation

### custom-github

**Purpose**: GitHub issue/PR management

**Available Tools**:

- `github_list_issues`: List issues
- `github_create_issue`: Create issue
- `github_get_issue`: Get issue details
- `github_list_pull_requests`: List PRs
- `github_search_repositories`: Search repos

**Usage Examples**:

```
"GitHub'da hyperxray repo'sunda açık issue'ları listele"
"GitHub'da yeni bir issue oluştur: JNI memory leak"
```

**When to Use**:

- Issue tracking
- PR management
- Repository search
- Project management

### custom-aws

**Purpose**: AWS service management

**Available Tools**:

- `aws_list_s3_buckets`: List S3 buckets
- `aws_list_ec2_instances`: List EC2 instances
- `aws_list_lambda_functions`: List Lambda functions
- `aws_describe_instance`: Describe EC2 instance

**Usage Examples**:

```
"AWS'de tüm S3 bucket'ları listele"
"AWS'de EC2 instance'ları göster"
```

**When to Use**:

- Infrastructure management
- Deployment
- Resource monitoring
- AWS operations

### context7

**Purpose**: Library documentation, API references

**Available Tools**:

- `resolve-library-id`: Resolve library name to ID
- `get-library-docs`: Get library documentation

**Usage Examples**:

```
"Context7'den Kotlin coroutines dokümantasyonunu getir"
"Context7'den Android VpnService API'sini göster"
```

**When to Use**:

- Library documentation
- API references
- Code examples
- Best practices

## 🎯 Tool Selection Matrix

| Task Type     | Primary Tool  | Secondary Tool |
| ------------- | ------------- | -------------- |
| Research      | brave-search  | context7       |
| Memory        | memory-bank   | -              |
| Web Testing   | playwright    | -              |
| GitHub        | custom-github | -              |
| AWS           | custom-aws    | -              |
| Documentation | context7      | brave-search   |

## 📊 Tool Usage Statistics

_Bu bölüm otomatik olarak güncellenir_

- **Most Used**: brave-search (research tasks)
- **Most Valuable**: memory-bank (context preservation)
- **Most Reliable**: custom-github (GitHub operations)

## 🔧 Tool Configuration

### brave-search

```json
{
  "command": "npx",
  "args": ["-y", "@brave/brave-search-mcp-server"],
  "env": {
    "BRAVE_API_KEY": "your_key_here"
  }
}
```

### memory-bank

```json
{
  "command": "npx",
  "args": ["-y", "memory-bank-mcp", "serve"]
}
```

### playwright

```json
{
  "command": "npx",
  "args": ["-y", "better-playwright-mcp3@latest", "mcp"]
}
```

### custom-github

```json
{
  "command": "python",
  "args": ["custom_github_mcp_server.py"]
}
```

### custom-aws

```json
{
  "command": "python",
  "args": ["custom_aws_mcp_server.py"]
}
```

## 🚀 Best Practices

1. **Tool Selection**: Göreve en uygun aracı seç
2. **Query Formulation**: Net ve spesifik sorgular oluştur
3. **Result Validation**: Sonuçları doğrula ve cross-reference yap
4. **Memory Integration**: Önemli bilgileri hafızaya kaydet
5. **Error Handling**: Tool hatalarını graceful handle et

## 📝 Tool Usage Log

_Son kullanımlar buraya kaydedilir_

- 2025-01-XX: brave-search - Kotlin coroutines research
- 2025-01-XX: memory-bank - JNI memory management decision
- 2025-01-XX: context7 - Android VpnService API lookup

---

_Bu registry otomatik olarak güncellenir. Tool kullanımı buraya loglanır._

