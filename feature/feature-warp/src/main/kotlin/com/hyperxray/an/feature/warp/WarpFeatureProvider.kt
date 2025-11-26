package com.hyperxray.an.feature.warp

import android.content.Context
import com.hyperxray.an.feature.warp.data.datasource.WarpApiDataSource
import com.hyperxray.an.feature.warp.data.repository.WarpRepositoryImpl
import com.hyperxray.an.feature.warp.data.storage.WarpAccountStorage
import com.hyperxray.an.feature.warp.domain.usecase.GenerateWarpConfigUseCase
import com.hyperxray.an.feature.warp.domain.usecase.GetWarpDevicesUseCase
import com.hyperxray.an.feature.warp.domain.usecase.LoadWarpAccountUseCase
import com.hyperxray.an.feature.warp.domain.usecase.RegisterWarpAccountUseCase
import com.hyperxray.an.feature.warp.domain.usecase.RemoveWarpDeviceUseCase
import com.hyperxray.an.feature.warp.domain.usecase.UpdateWarpLicenseUseCase
import com.hyperxray.an.feature.warp.presentation.viewmodel.WarpViewModel

/**
 * Provider for WARP feature dependencies
 */
object WarpFeatureProvider {
    
    /**
     * Create WARP ViewModel with all dependencies
     */
    fun createViewModel(context: Context): WarpViewModel {
        val apiDataSource = WarpApiDataSource(useIosHeaders = false)
        val storage = WarpAccountStorage(context)
        val repository = WarpRepositoryImpl(context, apiDataSource, storage)
        
        val registerAccountUseCase = RegisterWarpAccountUseCase(repository)
        val updateLicenseUseCase = UpdateWarpLicenseUseCase(repository)
        val getDevicesUseCase = GetWarpDevicesUseCase(repository)
        val removeDeviceUseCase = RemoveWarpDeviceUseCase(repository)
        val generateConfigUseCase = GenerateWarpConfigUseCase(repository)
        val loadAccountUseCase = LoadWarpAccountUseCase(repository)
        
        return WarpViewModel(
            registerAccountUseCase = registerAccountUseCase,
            updateLicenseUseCase = updateLicenseUseCase,
            getDevicesUseCase = getDevicesUseCase,
            removeDeviceUseCase = removeDeviceUseCase,
            generateConfigUseCase = generateConfigUseCase,
            loadAccountUseCase = loadAccountUseCase
        )
    }
}

