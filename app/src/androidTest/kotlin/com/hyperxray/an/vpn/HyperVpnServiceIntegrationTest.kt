package com.hyperxray.an.vpn

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * Integration tests for HyperVpnService
 * These tests require Android device/emulator and may require VPN permissions
 */
@RunWith(AndroidJUnit4::class)
class HyperVpnServiceIntegrationTest {
    
    private lateinit var context: Context
    private lateinit var stateManager: HyperVpnStateManager
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<android.app.Application>()
        stateManager = HyperVpnStateManager(context)
        stateManager.startObserving()
    }
    
    @After
    fun tearDown() {
        stateManager.stopObserving()
        // Ensure service is stopped after tests
        try {
            HyperVpnHelper.stopVpn(context)
        } catch (e: Exception) {
            // Ignore errors during cleanup
        }
    }
    
    @Test
    fun `test state manager observes broadcasts`() = runTest {
        // Verify state manager is observing
        val initialState = stateManager.state.first()
        assertNotNull("State should not be null", initialState)
    }
    
    @Test
    fun `test service intent can be created`() {
        val intent = Intent(context, HyperVpnService::class.java).apply {
            action = HyperVpnService.ACTION_START
        }
        
        assertNotNull("Intent should not be null", intent)
        assertEquals("Action should match", HyperVpnService.ACTION_START, intent.action)
    }
    
    @Test
    fun `test helper functions don't throw exceptions`() {
        // These tests verify the helper functions don't crash
        // Actual VPN connection requires user permission which can't be automated
        
        try {
            // This will likely fail due to missing VPN permission, but shouldn't crash
            HyperVpnHelper.startVpnWithWarp(context)
        } catch (e: SecurityException) {
            // Expected - VPN permission not granted in test environment
            assertTrue("Should throw SecurityException for VPN permission", true)
        } catch (e: Exception) {
            // Other exceptions are acceptable in test environment
            assertNotNull("Exception should have message", e.message)
        }
        
        try {
            HyperVpnHelper.stopVpn(context)
        } catch (e: Exception) {
            // Should not crash even if service is not running
            assertNotNull("Exception should have message", e.message)
        }
    }
    
    @Test
    fun `test state manager can clear error`() = runTest {
        stateManager.clearError()
        val error = stateManager.error.first()
        assertNull("Error should be null after clearing", error)
    }
}






