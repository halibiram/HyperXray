# HyperXray VPN - Mimari Dokümantasyonu

## 📐 Yeni Mimari (Temizlenmiş)

### Genel Bakış

Uygulama artık **tek instance** ve **native JNI** tabanlı basitleştirilmiş bir mimari kullanıyor. Tüm legacy process/shell mantığı ve multi-instance karmaşıklığı kaldırıldı.

---

## 🏗️ Mimari Katmanları

```
┌─────────────────────────────────────────────────────────────┐
│                    UI / ViewModel Layer                      │
│  (MainActivity, ViewModels, UI Components)                 │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                  HyperVpnService                             │
│  (Android VPN Service - VpnService)                        │
│                                                              │
│  • VPN lifecycle management                                 │
│  • TUN interface creation                                   │
│  • Native tunnel management (WireGuard + Xray)             │
│  • State broadcasting                                        │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       │ (Optional: Xray-core standalone)
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                  XrayCoreManager                             │
│  (Lightweight Singleton Manager)                           │
│                                                              │
│  • Single-instance Xray-core management                     │
│  • Configuration management                                 │
│  • Force restart logic (stop → wait → start)                │
│  • Status flow exposure                                     │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                  NativeXrayBridge                            │
│  (JNI Bridge - Single Instance API)                         │
│                                                              │
│  • Simplified API: start(), stop(), isRunning()             │
│  • Instance ID hidden (constant ID=1)                      │
│  • Force restart implementation                             │
│  • Status flow (StateFlow<InstanceInfo>)                    │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       │ Reflection-based JNI calls
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│            HyperVpnService Native Methods                   │
│  (JNI Function Declarations)                                │
│                                                              │
│  • startHyperTunnel() - Main tunnel (WireGuard + Xray)     │
│  • stopHyperTunnel() - Stop tunnel                          │
│  • getTunnelStats() - Get tunnel statistics                │
│  • (Multi-instance methods REMOVED)                          │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       │ JNI Bridge (hyperxray-jni.so)
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│              libhyperxray.so                                │
│  (Go Native Library)                                        │
│                                                              │
│  • WireGuard tunnel implementation                          │
│  • Xray-core integration (embedded)                        │
│  • Network stack management                                 │
│  • (Multi-instance manager REMOVED)                          │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔄 Veri Akışı

### VPN Başlatma Akışı

```
1. UI/ViewModel
   └─> HyperVpnHelper.startVpnWithConfig()
       └─> Intent(ACTION_START) → HyperVpnService

2. HyperVpnService.onStartCommand()
   └─> startVpn()
       ├─> Config loading (WireGuard + Xray)
       ├─> TUN interface creation
       └─> startHyperTunnel() [Native JNI]
           └─> libhyperxray.so
               └─> WireGuard + Xray tunnel started

3. (Optional) Standalone Xray-core
   └─> XrayCoreManager.start()
       └─> NativeXrayBridge.start()
           └─> HyperVpnService.startMultiInstancesNative()
               └─> libhyperxray.so (multi-instance manager)
                   └─> Xray-core instance started (ID=1)
```

### VPN Durdurma Akışı

```
1. UI/ViewModel
   └─> HyperVpnHelper.stopVpn()
       └─> Intent(ACTION_STOP) → HyperVpnService

2. HyperVpnService.onStartCommand()
   └─> stopVpn()
       ├─> stopHyperTunnel() [Native JNI]
       │   └─> libhyperxray.so
       │       └─> Tunnel stopped
       └─> (Optional) XrayCoreManager.stop()
           └─> NativeXrayBridge.stop()
               └─> HyperVpnService.stopAllMultiInstancesNative()
                   └─> libhyperxray.so
                       └─> Xray-core instance stopped
```

---

## 📦 Ana Bileşenler

### 1. HyperVpnService

**Konum:** `app/src/main/kotlin/com/hyperxray/an/vpn/HyperVpnService.kt`

**Sorumluluklar:**

- Android VPN servisi lifecycle yönetimi
- TUN interface oluşturma ve yönetimi
- Native Go kütüphanesi ile WireGuard + Xray tunnel yönetimi
- State broadcasting (UI'ya durum güncellemeleri)
- DNS component yönetimi

**Önemli Metodlar:**

- `startVpn()` - VPN başlatma
- `stopVpn()` - VPN durdurma
- `startHyperTunnel()` - Native tunnel başlatma (JNI)
- `stopHyperTunnel()` - Native tunnel durdurma (JNI)

---

### 2. XrayCoreManager

**Konum:** `app/src/main/kotlin/com/hyperxray/an/core/XrayCoreManager.kt`

**Sorumluluklar:**

- Lightweight singleton manager
- NativeXrayBridge ile Xray-core yönetimi
- Atomik konfigürasyon değişiklikleri (force restart)
- Status flow exposure

**Önemli Metodlar:**

- `initialize(service: HyperVpnService)` - Manager'ı başlat
- `start(configPath, configJSON?)` - Xray-core başlat
- `stop()` - Xray-core durdur
- `isRunning()` - Çalışma durumu kontrolü
- `getStatus()` - Status flow al

**Kullanım:**

```kotlin
val manager = XrayCoreManager.getInstance(context)
manager.initialize(hyperVpnService)
manager.start(configPath, configJSON)
```

---

### 3. NativeXrayBridge

**Konum:** `app/src/main/kotlin/com/hyperxray/an/vpn/NativeXrayBridge.kt`

**Sorumluluklar:**

- Single-instance Xray-core API
- Instance ID gizleme (sabit ID=1)
- Force restart logic (stop → wait → start)
- JNI bridge (reflection-based)
- Status flow yönetimi

**Önemli Metodlar:**

- `initialize(service: HyperVpnService)` - Bridge'i başlat
- `start(configPath, configJSON?)` - Instance başlat (force restart içerir)
- `stop()` - Instance durdur
- `isRunning()` - Çalışma durumu
- `updateStatusFromNative()` - Status güncelle

**Force Restart Logic:**

```kotlin
if (isRunning()) {
    stop()
    Thread.sleep(300)  // Cleanup için bekle
}
start(configPath, configJSON)
```

---

### 4. HyperVpnService Native Methods

**Konum:** `app/src/main/kotlin/com/hyperxray/an/vpn/HyperVpnService.kt`

**JNI Function Declarations:**

```kotlin
// Main tunnel methods
private external fun startHyperTunnel(
    tunFd: Int,
    wgConfigJSON: String,
    xrayConfigJSON: String,
    warpEndpoint: String,
    warpPrivateKey: String,
    nativeLibDir: String,
    filesDir: String
): Int

private external fun stopHyperTunnel(): Int
private external fun getTunnelStats(): String
```

**Not:** Multi-instance native metodları kaldırıldı. Xray-core artık `startHyperTunnel()` içinde embedded olarak yönetiliyor.

---

## 🗑️ Kaldırılan / Deprecated Bileşenler

### 1. MultiXrayCoreManager

**Durum:** `SİLİNDİ`
**Konum:** ~~`app/src/main/kotlin/com/hyperxray/an/xray/runtime/MultiXrayCoreManager.kt`~~ (dosya kaldırıldı)

**Neden Kaldırıldı:**

- Multi-instance karmaşıklığı
- Process/shell tabanlı mantık
- Gereksiz overhead
- Artık gerekli değil

**Yerine:** `XrayCoreManager` kullanılır (kendisi de deprecated).

---

### 2. NativeMultiInstanceManager

**Durum:** `SİLİNDİ`
**Konum:** ~~`app/src/main/kotlin/com/hyperxray/an/vpn/NativeMultiInstanceManager.kt`~~ (dosya kaldırıldı)

**Neden Kaldırıldı:**

- Multi-instance API
- Karmaşık instance yönetimi
- Artık gerekli değil

**Yerine:** `NativeXrayBridge` kullanılır (kendisi de deprecated).

---

### 3. Legacy Process/Shell Logic

**Kaldırılan:**

- `XrayProcessManager` (zaten yok)
- `LegacyXrayRunner` (zaten yok)
- `MultiInstanceXrayRunner` (zaten yok)
- `ProcessBuilder` kullanımları
- `Runtime.exec()` kullanımları

**Yerine:** Native JNI kullanılır.

---

## 🔐 Thread Safety

### Singleton Pattern

Tüm manager'lar thread-safe singleton pattern kullanır:

- Double-checked locking
- `@Volatile` annotation
- `synchronized` blocks

### State Management

- `StateFlow` kullanımı (reactive state)
- Thread-safe state updates
- Immutable data classes

---

## 📊 Status Flow

### NativeXrayBridge Status

```kotlin
enum class InstanceStatus {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

data class InstanceInfo(
    val status: InstanceStatus,
    val apiPort: Int = 0,
    val startTime: Long = 0,
    val errorMsg: String? = null,
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val connections: Int = 0
)
```

**Kullanım:**

```kotlin
val statusFlow = nativeBridge.status
statusFlow.collect { info ->
    when (info.status) {
        InstanceStatus.RUNNING -> // Handle running
        InstanceStatus.ERROR -> // Handle error
        // ...
    }
}
```

---

## 🚀 Kullanım Örnekleri

### VPN Başlatma (Full Mode)

```kotlin
// HyperVpnHelper kullanarak
HyperVpnHelper.startVpnWithConfig(
    context = context,
    wgConfigJson = wireguardConfig,
    xrayConfigJson = xrayConfig
)
```

### Standalone Xray-core Başlatma

```kotlin
val manager = XrayCoreManager.getInstance(context)
manager.initialize(hyperVpnService)

val success = manager.start(
    configPath = "/path/to/config.json",
    configJSON = configJsonString  // Optional
)

if (success) {
    // Xray-core başarıyla başlatıldı
}
```

### Status Monitoring

```kotlin
val statusFlow = manager.getStatus()
statusFlow?.collect { info ->
    when (info.status) {
        NativeXrayBridge.InstanceStatus.RUNNING -> {
            Log.i(TAG, "Xray-core running on port ${info.apiPort}")
        }
        NativeXrayBridge.InstanceStatus.ERROR -> {
            Log.e(TAG, "Error: ${info.errorMsg}")
        }
        // ...
    }
}
```

---

## 🔧 Konfigürasyon

### Xray Config Injection

Xray konfigürasyonuna API port ve diğer ayarlar inject edilir:

```kotlin
ConfigInjector.injectApiPort(config, apiPort)
ConfigInjector.injectCommonConfig(prefs, config)
```

### Force Restart

Konfigürasyon değiştiğinde otomatik force restart:

```kotlin
// NativeXrayBridge.start() içinde
if (isRunning()) {
    stop()
    Thread.sleep(300)  // Cleanup
}
start(configPath, configJSON)
```

---

## 📝 Notlar

1. **Instance ID:** Public API'de instance ID yok. Internal olarak sabit ID=1 kullanılır.

2. **Multi-Instance:** Native Go layer'da multi-instance desteği var ama Kotlin layer'da sadece single instance kullanılır.

3. **Backward Compatibility:** Deprecated sınıflar `@Deprecated(ERROR)` ile işaretlendi, compile-time'da hata verir.

4. **JNI Reflection:** `NativeXrayBridge` internal extension functions ile private native metodlara erişir.

5. **State Flow:** Reactive state management için `StateFlow` kullanılır.

---

## 🎯 Sonuç

Yeni mimari:

- ✅ **Basit:** Single-instance, temiz API
- ✅ **Performanslı:** Native JNI, process overhead yok
- ✅ **Bakımı Kolay:** Legacy kod kaldırıldı
- ✅ **Thread-Safe:** Singleton pattern, StateFlow
- ✅ **Reactive:** StateFlow ile reactive state management
