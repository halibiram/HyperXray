# HyperXray - Gerekli Paketler ve Bağımlılıklar

## 📦 Build Araçları ve Versiyonlar

### Gradle
- **Gradle**: 8.8
- **Android Gradle Plugin**: 8.6.1
- **Kotlin**: 2.1.21

### Android SDK
- **compileSdk**: 35
- **targetSdk**: 35
- **minSdk**: 29 (Android 10+)
- **NDK Version**: 28.2.13676358

### Protobuf
- **protoc**: 3.25.1
- **grpc-java**: 1.65.0

---

## 📚 Java/Kotlin Kütüphaneleri (Gradle Dependencies)

### gRPC Kütüphaneleri
```gradle
io.grpc:grpc-okhttp:1.74.0
io.grpc:grpc-protobuf:1.74.0
io.grpc:grpc-stub:1.74.0
io.grpc:grpc-kotlin-stub:1.4.3
```

### AndroidX Kütüphaneleri
```gradle
androidx.core:core-ktx:1.16.0
androidx.preference:preference-ktx:1.2.1
androidx.activity:activity-compose
```

### Jetpack Compose
```gradle
androidx.compose:compose-bom:2025.08.00
androidx.compose.ui:ui
androidx.compose.ui:ui-graphics
androidx.compose.material3:material3
androidx.compose.ui:ui-tooling-preview-android
androidx.compose.runtime:runtime-livedata
androidx.navigation:navigation-compose-android:2.9.3
```

### Material Design
```gradle
com.google.android.material:material:1.12.0
```

### Diğer Kütüphaneler
```gradle
com.google.code.gson:gson:2.13.1
com.squareup.okhttp3:okhttp:4.12.0
com.github.nanihadesuka:LazyColumnScrollbar:2.2.0
sh.calvin.reorderable:reorderable:2.5.1
```

---

## 🔧 Native Dependencies (NDK)

### Submodule'ler
1. **hev-socks5-tunnel** (Git Submodule)
   - URL: https://github.com/heiher/hev-socks5-tunnel
   - Konum: `app/src/main/jni/hev-socks5-tunnel/`
   - Alt bağımlılıklar:
     - **hev-socks5-core** (submodule)
     - **hev-task-system** (submodule)
     - **lwip** (submodule)
     - **yaml** (submodule)
     - **wintun** (Windows için)

### Native Build Araçları
- **NDK Build** (Android.mk kullanılıyor)
- **ABI Filters**: arm64-v8a, x86_64
- **C++ Standard**: C++11

---

## 🚀 Harici Binary'ler (Manuel Eklenmesi Gereken)

### Xray-core Binary
- **Versiyon**: v25.10.15
- **Konum**: `app/src/main/jniLibs/[architecture]/libxray.so`
- **Gerekli Mimari**: 
  - arm64-v8a
  - x86_64
- **Kaynak**: [XTLS/Xray-core](https://github.com/XTLS/Xray-core)

### GeoIP ve GeoSite Dosyaları
- **geoip.dat**
  - Versiyon: 202507212216
  - URL: https://github.com/lhear/v2ray-rules-dat/releases/latest/download/geoip.dat
  - Konum: `app/src/main/assets/geoip.dat`

- **geosite.dat**
  - Versiyon: 202507212216
  - URL: https://github.com/lhear/v2ray-rules-dat/releases/latest/download/geosite.dat
  - Konum: `app/src/main/assets/geosite.dat`

---

## 📋 Repository'ler

### Maven Repository'ler
```gradle
google()
mavenCentral()
maven { url 'https://jitpack.io' }
```

---

## 🛠️ Build Gereksinimleri

### Sistem Gereksinimleri
- **Java**: JVM 1.8+
- **RAM**: Minimum 4GB (gradle.properties'de -Xmx4g)
- **Disk**: Yeterli alan (NDK, Gradle cache, build output için)

### Gerekli Araçlar
1. **Android Studio** (önerilen) veya command line tools
2. **Android SDK** (API 35)
3. **NDK** (Version 28.2.13676358)
4. **Git** (submodule'ler için)

---

## 📝 Kurulum Adımları

1. **Repository'yi klonla**:
   ```bash
   git clone --recursive https://github.com/halibiram/HyperXray
   ```

2. **Xray-core binary'sini ekle**:
   - Her mimari için `libxray.so` dosyasını indir
   - `app/src/main/jniLibs/arm64-v8a/libxray.so`
   - `app/src/main/jniLibs/x86_64/libxray.so`

3. **GeoIP/GeoSite dosyalarını ekle**:
   - `geoip.dat` ve `geosite.dat` dosyalarını indir
   - `app/src/main/assets/` klasörüne kopyala

4. **Gradle sync yap**:
   - Android Studio'da "Sync Project with Gradle Files"

5. **Build**:
   ```bash
   ./gradlew assembleRelease
   ```

---

## 📌 Notlar

- Submodule'ler otomatik olarak `git clone --recursive` ile indirilir
- Xray-core binary'si ve geo dosyaları manuel olarak eklenmelidir (lisans/güvenlik nedenleriyle)
- ProGuard/R8 kullanılıyor (release build'de)
- Universal APK ve mimariye özel APK'lar oluşturulur

---

## 🔗 İlgili Linkler

- **Xray-core**: https://github.com/XTLS/Xray-core
- **hev-socks5-tunnel**: https://github.com/heiher/hev-socks5-tunnel
- **v2ray-rules-dat**: https://github.com/lhear/v2ray-rules-dat
- **Project Repository**: https://github.com/halibiram/HyperXray






