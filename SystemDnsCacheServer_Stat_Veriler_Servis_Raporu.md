# SystemDnsCacheServer Stat Veriler Servis Raporu

**Tarih**: 25 Kasım 2024  
**Kontrol**: Stat verilerinin neden düzgün servis çekilmediği analizi

## 🔍 Sorun Analizi

### 1. StateFlow Subscription Problemi

**Lokasyon**: `app/src/main/kotlin/com/hyperxray/an/viewmodel/MainViewModelDashboardAdapter.kt:103-122`

```103:122:app/src/main/kotlin/com/hyperxray/an/viewmodel/MainViewModelDashboardAdapter.kt
    // Connect directly to DnsCacheManager's StateFlow and map to feature state
    override val dnsCacheStats: StateFlow<FeatureDnsCacheStats?> =
        DnsCacheManager.dashboardStats
            .map { metrics ->
                try {
                    metrics.toFeatureState()
                } catch (e: Exception) {
                    Log.e(TAG, "Error converting DNS cache metrics to feature state", e)
                    null
                }
            }
            .stateIn(
                scope = mainViewModel.viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = try {
                    DnsCacheManager.dashboardStats.value.toFeatureState()
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting initial DNS cache metrics", e)
                    null
                }
            )
```

**Problem**:

- `SharingStarted.WhileSubscribed(5000)` kullanılıyor
- 5 saniye boyunca subscriber yoksa StateFlow durduruluyor
- Ancak DnsCacheManager'ın `dashboardStats` StateFlow'u her zaman aktif olmalı çünkü metrics job sürekli çalışıyor
- Dashboard ekranından ayrıldıktan sonra tekrar geldiğinde, StateFlow'un subscription'ı yeniden başlatılıyor ama metrics job'ı çalışmıyor olabilir

### 2. Metrics Job Bağımlılığı

**Lokasyon**: `core/core-network/src/main/kotlin/com/hyperxray/an/core/network/dns/DnsCacheManager.kt:222-235`

```222:235:core/core-network/src/main/kotlin/com/hyperxray/an/core/network/dns/DnsCacheManager.kt
    private fun startMetricsUpdateJob() {
        metricsUpdateJob?.cancel()
        metricsUpdateJob = metricsScope.launch {
            while (isActive) {
                try {
                    updateMetrics()
                    delay(500) // Update every 500ms
                } catch (e: Exception) {
                    Log.w(TAG, "Error updating metrics", e)
                    delay(1000) // Wait longer on error
                }
            }
        }
    }
```

**Problem**:

- Metrics job sadece `initialize()` çağrıldığında başlatılıyor
- Eğer initialize edilmeden önce StateFlow'a subscribe olunursa, stat verileri güncellenmez
- Metrics job'ı `metricsScope` içinde çalışıyor, bu scope'un lifecycle'ı kontrol edilmiyor
- Eğer scope iptal edilirse, metrics job durur ve stat verileri güncellenmez

### 3. updateDnsCacheStats() Yetersizliği

**Lokasyon**: `app/src/main/kotlin/com/hyperxray/an/viewmodel/MainViewModelDashboardAdapter.kt:148-166`

```148:166:app/src/main/kotlin/com/hyperxray/an/viewmodel/MainViewModelDashboardAdapter.kt
    override fun updateDnsCacheStats() {
        mainViewModel.viewModelScope.launch {
            try {
                // Try to initialize DnsCacheManager if not already initialized
                // Use prefs to get context (prefs has access to Application)
                val context = mainViewModel.prefs.getContext()
                try {
                    DnsCacheManager.initialize(context)
                    // StateFlow is automatically updated by DnsCacheManager's metrics update job
                    // No manual update needed - the StateFlow connection handles it
                    Log.d(TAG, "DNS cache stats StateFlow connected and will update automatically")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to initialize DnsCacheManager: ${e.message}", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing DNS cache stats: ${e.message}", e)
            }
        }
    }
```

**Problem**:

- `updateDnsCacheStats()` sadece initialize ediyor
- Eğer DnsCacheManager zaten initialize edilmişse, `initialize()` fonksiyonu erken return yapıyor ve metrics job kontrol edilmiyor
- Metrics job'ının çalışıp çalışmadığı kontrol edilmiyor
- StateFlow'un güncellenmesini garanti etmiyor

### 4. StateFlow Initial Value Problemi

**Lokasyon**: `core/core-network/src/main/kotlin/com/hyperxray/an/core/network/dns/DnsCacheManager.kt:83-98`

```83:98:core/core-network/src/main/kotlin/com/hyperxray/an/core/network/dns/DnsCacheManager.kt
    private val _dashboardStats = MutableStateFlow<DnsCacheMetrics>(
        DnsCacheMetrics(
            entryCount = 0,
            totalLookups = 0L,
            hits = 0L,
            misses = 0L,
            hitRate = 0,
            memoryUsageBytes = 0L,
            memoryLimitBytes = MEMORY_LIMIT_MB * 1024 * 1024,
            memoryUsagePercent = 0,
            avgHitLatencyMs = 0.0,
            avgMissLatencyMs = 0.0,
            avgDomainHitRate = 0,
            topDomains = emptyList()
        )
    )
```

**Problem**:

- StateFlow'un initial value'su sıfır değerlerle başlıyor
- Eğer metrics job çalışmıyorsa, bu değerler hiç güncellenmez
- Dashboard ekranında sürekli sıfır değerler görünür

## 📊 Log Analizi Sonuçları

### Metrics Job Logları

Log dosyalarında `updateMetrics`, `startMetricsUpdateJob` veya `dashboardStats` ile ilgili log bulunamadı. Bu şu anlama geliyor:

1. **Metrics job çalışmıyor olabilir**: Eğer job çalışsaydı, `updateMetrics()` içinde log olurdu
2. **Initialize edilmemiş olabilir**: DnsCacheManager initialize edilmemişse, metrics job başlatılmaz
3. **Job iptal edilmiş olabilir**: Metrics scope iptal edilmişse, job durur

### DnsCacheManager Logları

Log dosyalarında sadece DNS cache hit/miss logları var, metrics update logları yok:

```
11-25 12:50:56.637 D DnsCacheManager: 🔍 Checking DNS cache for: graph.facebook.com
11-25 12:50:56.637 I DnsCacheManager: ✅ DNS cache HIT: graph.facebook.com -> [204.111.0.185]
```

**Eksik Loglar**:

- `DnsCacheManager initialized: X entries loaded, hits=Y, misses=Z` (initialize logu)
- `Error updating metrics` (metrics job hata logu)
- `DNS cache stats StateFlow connected` (adapter logu)

## 🔧 Tespit Edilen Sorunlar

### 1. Metrics Job Kontrolü Eksik

**Sorun**: Metrics job'ının çalışıp çalışmadığı kontrol edilmiyor.

**Etki**: Eğer job çalışmıyorsa, stat verileri hiç güncellenmez.

### 2. StateFlow Subscription Lifecycle Problemi

**Sorun**: `SharingStarted.WhileSubscribed(5000)` kullanılıyor, bu da subscription'ı geçici olarak durdurabilir.

**Etki**: Dashboard ekranından ayrıldıktan sonra tekrar geldiğinde, StateFlow'un subscription'ı yeniden başlatılıyor ama metrics job çalışmıyor olabilir.

### 3. Initialize Kontrolü Yetersiz

**Sorun**: `updateDnsCacheStats()` sadece initialize ediyor, ama initialize edilmişse hiçbir şey yapmıyor.

**Etki**: Eğer DnsCacheManager zaten initialize edilmişse ama metrics job çalışmıyorsa, stat verileri güncellenmez.

### 4. Metrics Scope Lifecycle Kontrolü Yok

**Sorun**: Metrics job'ı `metricsScope` içinde çalışıyor, bu scope'un lifecycle'ı kontrol edilmiyor.

**Etki**: Eğer scope iptal edilirse, metrics job durur ve stat verileri güncellenmez.

## 🎯 Çözüm Önerileri

### 1. Metrics Job Kontrolü Ekle

**Lokasyon**: `core/core-network/src/main/kotlin/com/hyperxray/an/core/network/dns/DnsCacheManager.kt`

```kotlin
fun isMetricsJobRunning(): Boolean {
    return metricsUpdateJob?.isActive == true
}

fun ensureMetricsJobRunning() {
    if (metricsUpdateJob?.isActive != true) {
        Log.w(TAG, "Metrics job not running, restarting...")
        startMetricsUpdateJob()
    }
}
```

### 2. StateFlow Subscription Stratejisi Değiştir

**Lokasyon**: `app/src/main/kotlin/com/hyperxray/an/viewmodel/MainViewModelDashboardAdapter.kt`

```kotlin
override val dnsCacheStats: StateFlow<FeatureDnsCacheStats?> =
    DnsCacheManager.dashboardStats
        .map { metrics ->
            try {
                metrics.toFeatureState()
            } catch (e: Exception) {
                Log.e(TAG, "Error converting DNS cache metrics to feature state", e)
                null
            }
        }
        .stateIn(
            scope = mainViewModel.viewModelScope,
            started = SharingStarted.Lazily, // Always keep subscription alive
            initialValue = try {
                DnsCacheManager.dashboardStats.value.toFeatureState()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting initial DNS cache metrics", e)
                null
            }
        )
```

### 3. updateDnsCacheStats() İyileştir

**Lokasyon**: `app/src/main/kotlin/com/hyperxray/an/viewmodel/MainViewModelDashboardAdapter.kt`

```kotlin
override fun updateDnsCacheStats() {
    mainViewModel.viewModelScope.launch {
        try {
            val context = mainViewModel.prefs.getContext()
            try {
                DnsCacheManager.initialize(context)
                // Ensure metrics job is running
                DnsCacheManager.ensureMetricsJobRunning()
                // Force update metrics immediately
                DnsCacheManager.updateMetrics()
                Log.d(TAG, "DNS cache stats StateFlow connected and metrics job ensured")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize DnsCacheManager: ${e.message}", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing DNS cache stats: ${e.message}", e)
        }
    }
}
```

### 4. Metrics Update Logging Ekle

**Lokasyon**: `core/core-network/src/main/kotlin/com/hyperxray/an/core/network/dns/DnsCacheManager.kt`

```kotlin
private fun updateMetrics() {
    val startTime = System.currentTimeMillis()
    // ... existing code ...

    // Emit to StateFlow
    _dashboardStats.value = metrics

    // Log periodically (every 10 updates = 5 seconds)
    if (metricsUpdateCount++ % 10 == 0) {
        Log.d(TAG, "Metrics updated: hits=${metrics.hits}, misses=${metrics.misses}, hitRate=${metrics.hitRate}%")
    }
}
```

### 5. Public updateMetrics() Metodu Ekle

**Lokasyon**: `core/core-network/src/main/kotlin/com/hyperxray/an/core/network/dns/DnsCacheManager.kt`

```kotlin
/**
 * Force update metrics immediately (public method for manual updates)
 */
fun updateMetrics() {
    updateMetrics()
}
```

## 📋 Öncelik Sırası

1. **YÜKSEK**: Metrics job kontrolü ekle ve `ensureMetricsJobRunning()` çağır
2. **YÜKSEK**: `updateDnsCacheStats()` içinde metrics job'ının çalıştığını garanti et
3. **ORTA**: StateFlow subscription stratejisini `SharingStarted.Lazily` olarak değiştir
4. **ORTA**: Metrics update logging ekle (debug için)
5. **DÜŞÜK**: Public `updateMetrics()` metodu ekle (manuel güncelleme için)

## ✅ Sonuç

**Ana Sorun**: Metrics job'ının çalışıp çalışmadığı kontrol edilmiyor ve garanti edilmiyor. Bu yüzden stat verileri düzgün servis çekilmiyor.

**Çözüm**: Metrics job kontrolü eklemek ve `updateDnsCacheStats()` içinde job'ın çalıştığını garanti etmek gerekiyor.

**Durum**: ⚠️ SORUN VAR  
**Öncelik**: 🔴 YÜKSEK  
**Etki**: Stat verileri dashboard'da görünmüyor veya güncellenmiyor
