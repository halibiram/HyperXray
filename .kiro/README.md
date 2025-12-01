# HyperXray Kiro Configuration

Bu klasör, Kiro AI asistanı için proje konfigürasyonlarını içerir.

## Klasör Yapısı

```
.kiro/
├── steering/           # Bağlamsal rehberlik dosyaları
│   ├── product.md      # Ürün genel bakışı (always)
│   ├── structure.md    # Proje yapısı (always)
│   ├── tech.md         # Tech stack (always)
│   ├── git-workflow.md # Git workflow (always)
│   ├── development-environment.md  # Dev ortamı (fileMatch)
│   ├── android-standards.md        # Android standartları (fileMatch)
│   ├── native-go-standards.md      # Go/JNI standartları (fileMatch)
│   └── security-guidelines.md      # Güvenlik (manual)
├── specs/              # Feature spesifikasyonları
│   └── README.md       # Spec rehberi
├── templates/          # Spec template'leri
│   ├── requirements-template.md
│   ├── design-template.md
│   └── tasks-template.md
├── settings/           # Kiro ayarları
│   └── mcp.json        # MCP server konfigürasyonu
└── hooks/              # Agent hook'ları
    └── README.md       # Hook rehberi
```

## Steering Dosyaları

### Always Included
- `product.md` - HyperXray ürün bilgileri
- `structure.md` - Modül organizasyonu
- `tech.md` - Teknoloji stack'i ve komutlar
- `git-workflow.md` - Branch ve commit kuralları

### File Match (Otomatik)
- `development-environment.md` - Build dosyaları açıldığında
- `android-standards.md` - Kotlin/Java dosyaları açıldığında
- `native-go-standards.md` - Go/C dosyaları açıldığında

### Manual (#security-guidelines)
- `security-guidelines.md` - Güvenlik konularında

## Spec-Driven Development

Yeni feature geliştirmek için:

1. `.kiro/specs/<feature-name>/` klasörü oluştur
2. `requirements.md` - EARS formatında gereksinimler
3. `design.md` - Teknik tasarım
4. `tasks.md` - Uygulama planı

Template'ler `.kiro/templates/` klasöründe.

## MCP Servers

`settings/mcp.json` dosyasında tanımlı:
- `kiro-prompts` - Kiro system promptları (disabled)
- `aws-docs` - AWS dokümantasyonu (disabled)

Aktifleştirmek için `"disabled": false` yapın.

## Agent Hooks

Hook'lar otomatik agent execution tetikler:
- Dosya kaydedildiğinde test çalıştırma
- Kod değişikliğinde lint kontrolü
- Native kod değişikliğinde build doğrulama

Command Palette > "Open Kiro Hook UI" ile yeni hook oluşturun.

## Referanslar

- [Spec-Driven Development Guide](https://github.com/jasonkneen/kiro)
- [EARS Requirements Format](https://www.iaria.org/conferences2013/filesICCGI13/ICCGI_2013_Tutorial_Terzakis.pdf)
