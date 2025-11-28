# Build Hataları - HyperXray Projesi

Bu dosya, uygulamayı ayağa kaldırma sırasında karşılaşılan derleme hatalarını içermektedir.

## Tarih

Build denemesi sırasında tespit edilen hatalar.

---

## 1. HyperVpnStateManager Import Hataları (ÇÖZÜLDÜ ✅)

### Hata Mesajları:

```
e: file:///C:/Users/halil/Desktop/hadila/feature/feature-dashboard/src/main/kotlin/com/hyperxray/an/feature/dashboard/DashboardScreen.kt:357:31 Property delegate must have a 'getValue(Nothing?, KProperty0<*>)' method.
e: file:///C:/Users/halil/Desktop/hadila/feature/feature-dashboard/src/main/kotlin/com/hyperxray/an/feature/dashboard/DashboardScreen.kt:358:34 Unresolved reference 'vpn'.
e: file:///C:/Users/halil/Desktop/hadila/feature/feature-dashboard/src/main/kotlin/com/hyperxray/an/feature/dashboard/components/HyperVpnControlCard.kt:39:25 Unresolved reference 'vpn'.
e: file:///C:/Users/halil/Desktop/hadila/feature/feature-dashboard/src/main/kotlin/com/hyperxray/an/feature/dashboard/components/HyperVpnControlCard.kt:47:12 Unresolved reference 'HyperVpnStateManager'.
```

### Çözüm:

- `HyperVpnStateManager` sınıfı `app` modülünden `core-network` modülüne taşındı
- Package adı `com.hyperxray.an.vpn` → `com.hyperxray.an.core.network.vpn` olarak değiştirildi
- Tüm import'lar güncellendi
- `feature-dashboard` modülüne `core-network` bağımlılığı eklendi

---

## 2. Preferences Import Hataları (ÇÖZÜLDÜ ✅)

### Hata Mesajları:

```
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/xray/runtime/MultiXrayCoreManager.kt:31:31 Unresolved reference 'Preferences'.
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/vpn/HyperVpnService.kt:18:31 Unresolved reference 'Preferences'.
```

### Çözüm:

- `app` modülüne `core-database` bağımlılığı eklendi
- `Preferences` sınıfı `core-database` modülünde `com.hyperxray.an.prefs` package'ında

---

## 3. MainActivity Import Hatası (ÇÖZÜLDÜ ✅)

### Hata Mesajı:

```
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/vpn/HyperVpnService.kt:586:55 Unresolved reference. None of the following candidates is applicable because of a receiver type mismatch:
val <T> KClass<T>.java: Class<T>
```

### Çözüm:

- `HyperVpnService.kt` dosyasındaki import düzeltildi:
  - `import com.hyperxray.an.ui.activity.MainActivity` → `import com.hyperxray.an.activity.MainActivity`

---

## 4. HyperVpnService - ConfigRepository Hataları (ÇÖZÜLMEDİ ❌)

### Hata Mesajları:

```
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/vpn/HyperVpnService.kt:464:47 Argument type mismatch: actual type is 'Context!', but 'Application' was expected.
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/vpn/HyperVpnService.kt:464:47 No value passed for parameter 'prefs'.
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/vpn/HyperVpnService.kt:465:39 Unresolved reference 'getAllProfiles'.
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/vpn/HyperVpnService.kt:468:62 Unresolved reference 'it'.
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/vpn/HyperVpnService.kt:469:53 Unresolved reference 'configContent'.
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/vpn/HyperVpnService.kt:481:39 Unresolved reference 'optJSONArray'.
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/vpn/HyperVpnService.kt:488:41 Unresolved reference 'optJSONObject'.
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/vpn/HyperVpnService.kt:489:47 Unresolved reference 'optJSONObject'.
```

### Sorun:

- `ConfigRepository` constructor'ı `Application` ve `Preferences` parametreleri bekliyor
- `HyperVpnService.kt:464` satırında `ConfigRepository(applicationContext)` çağrısı yapılıyor ama:
  - `applicationContext` bir `Context`, `Application` değil
  - `prefs` parametresi eksik
- `getAllProfiles()` metodu `ConfigRepository`'de yok
- `ConfigRepository`'de `configFiles` StateFlow var, `getAllProfiles()` yok

### Gerekli Düzeltmeler:

1. `ConfigRepository` instance'ı doğru şekilde oluşturulmalı:

   ```kotlin
   val app = applicationContext as Application
   val prefs = Preferences(app)
   val configRepo = ConfigRepository(app, prefs)
   ```

2. `getAllProfiles()` yerine `configFiles` StateFlow kullanılmalı veya config dosyalarından okunmalı

---

## 5. Notification Builder Hataları (ÇÖZÜLMEDİ ❌)

### Hata Mesajları:

```
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/vpn/HyperVpnService.kt:595:38 Unresolved reference 'ic_launcher_foreground'.
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/vpn/HyperVpnService.kt:596:14 Unresolved reference 'setContentIntent'.
```

### Sorun:

- `R.drawable.ic_launcher_foreground` resource'u bulunamıyor
- `Notification.Builder.setContentIntent()` metodu API seviyesine göre farklı olabilir

### Gerekli Düzeltmeler:

1. Drawable resource'unun varlığını kontrol et
2. Notification.Builder API seviyesine göre düzelt (Android O+ için NotificationCompat kullan)

---

## 6. WarpUtils Hataları (ÇÖZÜLMEDİ ❌)

### Hata Mesajları:

```
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/utils/WarpUtils.kt:14:59 Cannot infer type for type parameter 'T'. Specify it explicitly.
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/utils/WarpUtils.kt:15:20 Unresolved reference 'BouncyCastleProvider'.
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/utils/WarpUtils.kt:161:34 Unresolved reference 'PrivateKeyInfo'.
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/utils/WarpUtils.kt:164:34 Unresolved reference 'bouncycastle'.
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/utils/WarpUtils.kt:165:40 Unresolved reference 'octets'.
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/utils/WarpUtils.kt:334:17 Unresolved reference 'bouncycastle'.
```

### Sorun:

- BouncyCastle kütüphanesi eksik veya import'lar yanlış
- Type inference sorunları

### Gerekli Düzeltmeler:

1. BouncyCastle dependency'sini `build.gradle`'a ekle
2. Import'ları düzelt
3. Type parametrelerini açıkça belirt

---

## 7. MainViewModel Hataları (ÇÖZÜLMEDİ ❌)

### Hata Mesajları:

```
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/viewmodel/MainViewModel.kt:1042:57 Unresolved reference 'customGeositeImported'.
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/viewmodel/MainViewModel.kt:1051:55 Unresolved reference 'customGeoipImported'.
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/viewmodel/MainViewModel.kt:1052:57 Unresolved reference 'customGeositeImported'.
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/viewmodel/MainViewModel.kt:1157:25 Unresolved reference 'Preferences'.
```

### Sorun:

- `Preferences` import'u eksik veya yanlış
- `customGeositeImported` ve `customGeoipImported` property'leri bulunamıyor

### Gerekli Düzeltmeler:

1. `Preferences` import'unu kontrol et
2. `customGeositeImported` ve `customGeoipImported` property'lerinin tanımlarını kontrol et

---

## 8. MainViewModelDashboardAdapter Hataları (ÇÖZÜLMEDİ ❌)

### Hata Mesajı:

```
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/viewmodel/MainViewModelDashboardAdapter.kt:197:51 Unresolved reference 'getContext'.
```

### Sorun:

- `getContext()` metodu bulunamıyor

### Gerekli Düzeltmeler:

1. Context'e erişim yöntemini kontrol et
2. ViewModel'de context erişimi için doğru yöntemi kullan

---

## 9. SettingsState Hataları (ÇÖZÜLMEDİ ❌)

### Hata Mesajları:

```
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/viewmodel/SettingsState.kt:3:32 Unresolved reference 'ThemeMode'.
e: file:///C:/Users/halil/Desktop/hadila/app/src/main/kotlin/com/hyperxray/an/viewmodel/SettingsState.kt:17:20 Unresolved reference 'ThemeMode'.
```

### Sorun:

- `ThemeMode` enum/class'ı bulunamıyor

### Gerekli Düzeltmeler:

1. `ThemeMode` class'ının tanımını bul
2. Import'u düzelt

---

## Özet

### Çözülen Hatalar (3):

1. ✅ HyperVpnStateManager import hataları
2. ✅ Preferences import hataları
3. ✅ MainActivity import hatası

### Çözülmeyen Hatalar (6):

1. ❌ HyperVpnService - ConfigRepository hataları
2. ❌ Notification Builder hataları
3. ❌ WarpUtils hataları
4. ❌ MainViewModel hataları
5. ❌ MainViewModelDashboardAdapter hataları
6. ❌ SettingsState hataları

---

## Sonraki Adımlar

1. **HyperVpnService.kt** dosyasındaki `getXrayConfigFromProfile()` metodunu düzelt
2. **Notification Builder** API seviyesine göre güncelle
3. **BouncyCastle** dependency'sini ekle ve import'ları düzelt
4. **MainViewModel** ve ilgili dosyalardaki eksik property'leri kontrol et
5. **SettingsState** için `ThemeMode` import'unu düzelt

---

## Notlar

- Build başarısız olduğu için uygulama henüz çalıştırılamadı
- Kritik hatalar çözüldükten sonra tekrar build denemesi yapılmalı
- Android Studio'da projeyi açıp Gradle sync yapmak faydalı olabilir





