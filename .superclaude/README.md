# 🚀 SuperClaude MCP System - Kullanım Kılavuzu

Bu dizin, HyperXray projesi için gelişmiş MCP (Model Context Protocol) tabanlı AI asistan sistemini içerir.

## 📁 Dosya Yapısı

```
.superclaude/
├── README.md                    # Bu dosya
├── mcp_tool_registry.md         # MCP araç kayıt defteri
├── mcp_helper.py                # MCP helper script
└── memory/
    ├── project_status.md        # Proje durumu
    ├── learned_lessons.md       # Öğrenilen dersler
    ├── active_context.md        # Aktif context
    └── mcp_usage_log.json       # Tool kullanım logları (otomatik)
```

## 🎯 Sistem Özellikleri

### 1. Memory-First Approach
- Her görev öncesi hafıza dosyaları okunur
- Geçmiş hatalardan öğrenme
- Context preservation

### 2. Agent Modes
- **@PM**: Project Manager
- **@Researcher**: Deep Research
- **@Architect**: System Architect
- **@Security**: Security Engineer
- **@PythonExpert**: Language Expert
- **@DevOps**: DevOps Engineer
- **@QA**: Quality Assurance

### 3. MCP Tool Integration
- **brave-search**: Web search
- **memory-bank**: Long-term memory
- **playwright**: Browser automation
- **custom-github**: GitHub operations
- **custom-aws**: AWS services
- **context7**: Library documentation

## 📖 Kullanım Örnekleri

### Örnek 1: Araştırma Görevi

```
Kullanıcı: "Kotlin coroutines memory leak çözümlerini ara"

Sistem:
1. Memory check (.superclaude/memory/learned_lessons.md)
2. @Researcher moduna geç
3. brave-search ile araştırma yap
4. context7 ile Kotlin docs kontrol et
5. Sonuçları memory-bank'a kaydet
```

### Örnek 2: Mimari Tasarım

```
Kullanıcı: "Yeni VPN modülü için mimari tasarla"

Sistem:
1. Memory check (project_status.md, active_context.md)
2. @Architect moduna geç
3. ASCII diagram çiz
4. Klasör yapısını öner
5. Design pattern'leri öner
6. project_status.md güncelle
```

### Örnek 3: Güvenlik Kontrolü

```
Kullanıcı: "VPN servisinde güvenlik audit yap"

Sistem:
1. Memory check (learned_lessons.md - security lessons)
2. @Security moduna geç
3. OWASP checklist kullan
4. Code review yap
5. Güvenlik açıklarını raporla
6. learned_lessons.md güncelle
```

## 🛠️ MCP Helper Script Kullanımı

### Tool Önerisi Al

```bash
python .superclaude/mcp_helper.py recommend "Android VPN memory leak araştır"
```

Çıktı:
```
Recommended tools for 'Android VPN memory leak araştır':
  - brave-search
  - context7
  - memory-bank
```

### Workflow Önerisi Al

```bash
python .superclaude/mcp_helper.py workflow "Yeni feature implementasyonu"
```

Çıktı: JSON formatında detaylı workflow

### Tool Kullanımını Logla

```bash
python .superclaude/mcp_helper.py log brave-search "Kotlin coroutines research"
```

## 🔄 Otomatik İşlemler

### Memory Güncelleme
- Her görev sonrası `project_status.md` güncellenir
- Hata durumunda `learned_lessons.md` güncellenir
- Aktif context `active_context.md`'de tutulur

### Tool Usage Logging
- MCP tool kullanımları otomatik loglanır
- `memory/mcp_usage_log.json` dosyasında saklanır
- Son 100 kullanım tutulur

## 📊 Mod Seçimi

Sistem görev türüne göre otomatik mod seçer:

| Görev Türü | Mod |
|------------|-----|
| "araştır", "research" | @Researcher |
| "tasarımla", "design" | @Architect |
| "güvenlik", "security" | @Security |
| "test", "test yaz" | @QA |
| "deploy", "deployment" | @DevOps |
| "kod yaz", "implement" | @PythonExpert / @KotlinExpert |
| "planla", "plan" | @PM |

Manuel mod seçimi için `@ModAdı` kullanın:
- `@PM` → Project Manager
- `@Researcher` → Research
- `@Architect` → Architecture
- vb.

## 🎓 Best Practices

### 1. Memory-First
Her görev öncesi mutlaka memory dosyalarını oku.

### 2. Tool Selection
Göreve en uygun MCP aracını seç:
- Research → brave-search, context7
- Memory → memory-bank
- Web → playwright
- GitHub → custom-github
- AWS → custom-aws

### 3. Context Preservation
Önemli kararları ve öğrenilenleri hafızaya kaydet.

### 4. Error Learning
Her hatadan ders çıkar ve `learned_lessons.md`'ye ekle.

### 5. Documentation
Kod yazarken dokümantasyonu da güncelle.

## 🔧 Konfigürasyon

### MCP Server Setup
`cursor_mcp_config.json` dosyasında MCP server'lar tanımlı:

```json
{
  "mcpServers": {
    "brave-search": { ... },
    "memory-bank": { ... },
    "playwright": { ... },
    "custom-github": { ... },
    "custom-aws": { ... }
  }
}
```

### Memory Files
Memory dosyaları `.superclaude/memory/` dizininde:
- `project_status.md`: Proje durumu
- `learned_lessons.md`: Öğrenilen dersler
- `active_context.md`: Aktif context

## 📈 Metrikler

Sistem şu metrikleri takip eder:
- Tool usage statistics
- Memory update frequency
- Error learning rate
- Context preservation rate

## 🚀 Gelişmiş Özellikler

### 1. Confidence Check
Her kod yazma öncesi confidence score hesaplanır:
- < 70%: Daha fazla araştırma
- 70-90%: Kullanıcı onayı
- > 90%: Direkt uygulama

### 2. Reflexion Loop
Hata durumunda:
1. Analiz
2. Dokümantasyon
3. Pattern çıkarma
4. Önleme stratejisi

### 3. Context-Aware Decisions
Her karar:
1. Memory check
2. Similar situations
3. MCP tools
4. Pattern matching

## 📝 Örnek Senaryolar

### Senaryo 1: Yeni Feature
```
1. @PM: Görevleri böl
2. @Researcher: Best practices araştır
3. @Architect: Mimari tasarla
4. @PythonExpert/@KotlinExpert: Kod yaz
5. @QA: Test yaz
6. @Security: Güvenlik kontrolü
7. Memory update
```

### Senaryo 2: Bug Fix
```
1. Memory check (learned_lessons.md)
2. @Researcher: Benzer bug'ları ara
3. Root cause analysis
4. Fix implementation
5. Test
6. learned_lessons.md güncelle
```

### Senaryo 3: Performance Optimization
```
1. @Researcher: Profiling tools araştır
2. Performance profiling
3. Bottleneck identification
4. Optimization
5. Benchmark
6. Memory update
```

## 🔗 İlgili Dosyalar

- `.cursorrules`: Ana sistem kuralları
- `cursor_mcp_config.json`: MCP server konfigürasyonu
- `docs/PROJECT_ARCHITECTURE.md`: Proje mimarisi

## 📞 Destek

Sorularınız için:
1. Memory dosyalarını kontrol et
2. MCP tool registry'yi incele
3. Örnek senaryoları takip et

---

*Bu sistem sürekli öğrenir ve gelişir. Her görevde hafıza güncellenir.*


