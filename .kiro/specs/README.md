# HyperXray Specs

Bu klasör, spec-driven development yaklaşımıyla oluşturulan feature spesifikasyonlarını içerir.

## Spec Yapısı

Her feature için üç dosya oluşturulur:

```
.kiro/specs/<feature-name>/
├── requirements.md   # Gereksinimler (EARS formatında)
├── design.md         # Teknik tasarım
└── tasks.md          # Uygulama görevleri
```

## Yeni Spec Oluşturma

1. Feature klasörü oluştur: `.kiro/specs/<feature-name>/`
2. `requirements.md` ile başla - kullanıcı hikayeleri ve kabul kriterleri
3. `design.md` ile devam et - mimari ve teknik kararlar
4. `tasks.md` ile bitir - uygulama planı

## Template'ler

Template dosyaları `.kiro/templates/` klasöründe bulunur:
- `requirements-template.md`
- `design-template.md`
- `tasks-template.md`

## Mevcut Specs

| Feature | Durum | Açıklama |
|---------|-------|----------|
| - | - | Henüz spec oluşturulmadı |

## Referanslar

- [EARS Format](https://www.iaria.org/conferences2013/filesICCGI13/ICCGI_2013_Tutorial_Terzakis.pdf)
- [Spec-Driven Development Guide](https://github.com/jasonkneen/kiro)
