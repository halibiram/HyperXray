# Agent Hooks

Bu klasör, Kiro agent hook'larını içerir. Hook'lar, belirli olaylar gerçekleştiğinde otomatik olarak agent execution'ı tetikler.

## Hook Türleri

### Event-Based Hooks
- **onSave**: Dosya kaydedildiğinde tetiklenir
- **onSessionStart**: Yeni session başladığında tetiklenir
- **onAgentComplete**: Agent execution tamamlandığında tetiklenir
- **onMessage**: Mesaj gönderildiğinde tetiklenir

### Manual Hooks
- Kullanıcı tarafından manuel olarak tetiklenir
- Explorer view'da "Agent Hooks" bölümünden erişilebilir

## Örnek Hook'lar

### Test Runner Hook
Kotlin dosyası kaydedildiğinde ilgili testleri çalıştırır.

### Lint Hook
Kod değişikliklerinde otomatik lint kontrolü yapar.

### Build Verification Hook
Native Go kodu değiştiğinde build doğrulaması yapar.

## Hook Oluşturma

1. Command Palette'den "Open Kiro Hook UI" seçin
2. Hook türünü ve tetikleyiciyi belirleyin
3. Çalıştırılacak komutu veya mesajı tanımlayın
4. Hook'u kaydedin

## Mevcut Hook'lar

| Hook | Tetikleyici | Açıklama |
|------|-------------|----------|
| unlimited-commands | onMessage | Sınırsız shell komutu çalıştırma |

## Best Practices

- Hook'ları küçük ve odaklı tutun
- Uzun süren işlemler için background process kullanın
- Hata durumlarını düzgün handle edin
- Hook çıktılarını loglayın
