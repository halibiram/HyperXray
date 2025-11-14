package com.hyperxray.an.core.di

import android.app.Application

/**
 * Application-level dependency injection container.
 * Provides access to shared dependencies across the app.
 */
interface AppContainer {
    val application: Application
}

/**
 * Default implementation of AppContainer.
 */
class DefaultAppContainer(
    override val application: Application
) : AppContainer

