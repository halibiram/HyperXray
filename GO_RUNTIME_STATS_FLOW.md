# Go Runtime Memory Stats - Çalışma Şeması

## 📊 Veri Akış Diyagramı

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Xray-Core (Native Go Process)                    │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  Go Runtime Memory Stats (runtime.MemStats)                     │   │
│  │  - alloc: Allocated memory                                       │   │
│  │  - totalAlloc: Total allocated memory                            │   │
│  │  - sys: System memory                                            │   │
│  │  - mallocs: Total mallocs                                        │   │
│  │  - frees: Total frees                                            │   │
│  │  - liveObjects: Live objects (mallocs - frees)                   │   │
│  │  - pauseTotalNs: Total GC pause time                            │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                              │                                           │
│                              ▼                                           │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  gRPC API (127.0.0.1:65276)                                     │   │
│  │  GetSysStats() → SysStatsResponse                               │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                              │
                              │ gRPC Call
                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    Native Bridge Layer (Go)                              │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  native/lib.go: GetXraySystemStats()                            │   │
│  │  1. tunnel.GetXrayInstance()                                    │   │
│  │  2. xrayInstance.GetGrpcClient()                                │   │
│  │  3. grpcClient.GetSystemStats() → gRPC call                     │   │
│  │  4. Convert to JSON:                                            │   │
│  │     {                                                             │   │
│  │       "alloc": ..., "totalAlloc": ..., "sys": ...,              │   │
│  │       "mallocs": ..., "frees": ..., "liveObjects": ...,         │   │
│  │       "pauseTotalNs": ..., "numGoroutine": ..., "numGC": ...   │   │
│  │     }                                                             │   │
│  │  5. Return C.CString(JSON)                                       │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                              │
                              │ JNI Call
                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    JNI Layer (C)                                        │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  hyperxray-jni.c: getXraySystemStatsNative()                   │   │
│  │  1. go_GetXraySystemStats() → Call Go function                  │   │
│  │  2. Convert C string to Java String                            │   │
│  │  3. Free C string memory                                       │   │
│  │  4. Return jstring (JSON)                                      │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                              │
                              │ Native Method Call
                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    Kotlin Layer - XrayStatsManager                     │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  XrayStatsManager.kt                                            │   │
│  │                                                                  │   │
│  │  1. getXraySystemStatsNative(): String?                         │   │
│  │     → Native JNI call                                           │   │
│  │                                                                  │   │
│  │  2. parseSystemStatsFromJson(jsonStr: String)                   │   │
│  │     → Parse JSON to SysStatsResponse                            │   │
│  │                                                                  │   │
│  │  3. updateStatsState(stats, traffic)                            │   │
│  │     → Update _statsState (MutableStateFlow)                     │   │
│  │     → Create CoreStatsState with Go runtime data                │   │
│  │                                                                  │   │
│  │  4. stats: StateFlow<CoreStatsState>                            │   │
│  │     → Exposed StateFlow for consumption                         │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                              │
                              │ StateFlow.collectLatest
                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│              AndroidMemoryStatsManager                                  │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  startMonitoring()                                               │   │
│  │                                                                  │   │
│  │  1. xrayStatsCollectJob = scope.launch {                         │   │
│  │       xrayStatsManager.stats.collectLatest { stats ->           │   │
│  │         currentGoRuntimeStats = stats  // Cache update          │   │
│  │       }                                                          │   │
│  │     }                                                            │   │
│  │                                                                  │   │
│  │  2. monitoringJob = scope.launch {                              │   │
│  │       while (isActive) {                                         │   │
│  │         delay(2000L)  // Every 2 seconds                         │   │
│  │         updateMemoryStats()                                      │   │
│  │       }                                                          │   │
│  │     }                                                            │   │
│  │                                                                  │   │
│  │  updateMemoryStats() {                                           │   │
│  │    1. Collect Android memory (PSS, heap, etc.)                  │   │
│  │    2. Get Go runtime stats from cache:                          │   │
│  │       currentGoRuntimeStats ?: xrayStatsManager?.stats?.value   │   │
│  │    3. Combine both into AndroidMemoryStats                       │   │
│  │    4. Emit to _memoryStats StateFlow                            │   │
│  │  }                                                               │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                              │
                              │ StateFlow
                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    MainViewModel                                        │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  androidMemoryStats: StateFlow<AndroidMemoryStats>               │   │
│  │    = androidMemoryStatsManager.memoryStats                       │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                              │
                              │ StateFlow.map
                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│              MainViewModelDashboardAdapter                              │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  androidMemoryStats: StateFlow<FeatureAndroidMemoryStats>       │   │
│  │    = mainViewModel.androidMemoryStats                            │   │
│  │      .map { it.toFeatureState() }                               │   │
│  │      .stateIn(...)                                               │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                              │
                              │ collectAsState
                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    DashboardScreen (Compose UI)                          │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  val androidMemoryStats by viewModel.androidMemoryStats        │   │
│  │    .collectAsState()                                            │   │
│  │                                                                  │   │
│  │  // Display Go Runtime Memory Stats:                            │   │
│  │  - androidMemoryStats.goAlloc                                   │   │
│  │  - androidMemoryStats.goTotalAlloc                              │   │
│  │  - androidMemoryStats.goSys                                     │   │
│  │  - androidMemoryStats.goMallocs                                 │   │
│  │  - androidMemoryStats.goFrees                                    │   │
│  │  - androidMemoryStats.goLiveObjects                              │   │
│  │  - androidMemoryStats.goPauseTotalNs                             │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

## 🔄 Detaylı Akış Açıklaması

### 1. **Xray-Core (Native Go Process)**
- Xray-core Go runtime'ında çalışıyor
- `runtime.MemStats` ile Go runtime memory bilgileri toplanıyor
- gRPC API üzerinden (`GetSysStats`) bu bilgiler expose ediliyor

### 2. **Native Bridge (Go → C)**
- `native/lib.go:GetXraySystemStats()` fonksiyonu:
  - Xray instance'ı alır
  - gRPC client üzerinden `GetSystemStats()` çağrısı yapar
  - Sonuçları JSON formatına çevirir
  - C string olarak döner (`C.CString`)

### 3. **JNI Layer (C → Java/Kotlin)**
- `hyperxray-jni.c:getXraySystemStatsNative()`:
  - Go fonksiyonunu çağırır
  - C string'i Java String'e çevirir
  - Memory'yi temizler
  - JSON string döner

### 4. **XrayStatsManager (Kotlin)**
- Native method'u çağırır: `getXraySystemStatsNative()`
- JSON'u parse eder: `parseSystemStatsFromJson()`
- `CoreStatsState` oluşturur ve `_statsState` StateFlow'unu günceller
- Her güncellemede StateFlow emit edilir

### 5. **AndroidMemoryStatsManager (Kotlin)**
- **İki paralel coroutine çalışır:**
  
  **a) Stats Collector Job:**
  - `xrayStatsManager.stats.collectLatest` ile StateFlow'u dinler
  - Her güncellemede `currentGoRuntimeStats` cache'ini günceller
  - Real-time güncelleme sağlar
  
  **b) Monitoring Loop:**
  - Her 2 saniyede bir `updateMemoryStats()` çağrılır
  - Android memory bilgilerini toplar (PSS, heap, system memory)
  - Cached Go runtime stats'i kullanır
  - Her ikisini birleştirip `AndroidMemoryStats` oluşturur
  - `_memoryStats` StateFlow'unu günceller

### 6. **MainViewModel**
- `androidMemoryStats` StateFlow'unu expose eder
- `androidMemoryStatsManager.memoryStats`'i direkt olarak expose eder

### 7. **MainViewModelDashboardAdapter**
- Feature module için state transformation yapar
- `toFeatureState()` ile app module state'ini feature module state'ine çevirir

### 8. **DashboardScreen (Compose UI)**
- `viewModel.androidMemoryStats.collectAsState()` ile state'i dinler
- Go runtime memory bilgilerini UI'da gösterir

## ⚡ Önemli Noktalar

### Cache Mekanizması
- `@Volatile private var currentGoRuntimeStats` ile Go runtime stats cache'leniyor
- Bu sayede her 2 saniyede bir StateFlow.value okunması gerekmiyor
- `collectLatest` ile real-time güncelleme sağlanıyor

### İki Yönlü Veri Akışı
1. **Real-time Updates:** `collectLatest` ile anında cache güncellemesi
2. **Periodic Updates:** 2 saniyede bir Android memory + Go runtime birleştirme

### Fallback Mekanizması
```kotlin
val goRuntimeStats = currentGoRuntimeStats ?: xrayStatsManager?.stats?.value
```
- Önce cache'den okur
- Cache yoksa StateFlow.value'dan okur
- Her ikisi de yoksa 0 değerleri kullanır

## 📝 Veri Yapıları

### CoreStatsState (XrayStatsManager)
```kotlin
data class CoreStatsState(
    val alloc: Long = 0,
    val totalAlloc: Long = 0,
    val sys: Long = 0,
    val mallocs: Long = 0,
    val frees: Long = 0,
    val liveObjects: Long = 0,
    val pauseTotalNs: Long = 0,
    // ... traffic stats
)
```

### AndroidMemoryStats
```kotlin
data class AndroidMemoryStats(
    // Android memory
    val totalPss: Long = 0L,
    val nativeHeap: Long = 0L,
    val dalvikHeap: Long = 0L,
    // ...
    
    // Go runtime memory (from XrayStatsManager)
    val goAlloc: Long = 0L,
    val goTotalAlloc: Long = 0L,
    val goSys: Long = 0L,
    val goMallocs: Long = 0L,
    val goFrees: Long = 0L,
    val goLiveObjects: Long = 0L,
    val goPauseTotalNs: Long = 0L,
)
```

## 🔍 Debug Logları

### XrayStatsManager
- `"Stats updated from native gRPC client"`
- `"Core stats updated - Uplink: ..., Downlink: ..."`

### AndroidMemoryStatsManager
- `"Starting to collect XrayStatsManager stats updates"`
- `"✅ Go runtime stats received: alloc=..., sys=..., mallocs=..."`
- `"⚠️ Go runtime stats are zero - XrayStatsManager available but stats not yet received"`

## 🎯 Performans Optimizasyonları

1. **Cache Kullanımı:** StateFlow.value tekrar tekrar okunmuyor
2. **collectLatest:** Sadece son değer alınıyor, eski değerler skip ediliyor
3. **2 Saniye Interval:** Android memory toplama için optimal interval
4. **Paralel İşlem:** Stats collection ve memory polling paralel çalışıyor


