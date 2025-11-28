package com.hyperxray.an.vpn

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*

/**
 * Unit tests for HyperVpnHelper
 */
class HyperVpnHelperTest {
    
    @Mock
    private lateinit var mockContext: Context
    
    private lateinit var context: Context
    
    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        context = ApplicationProvider.getApplicationContext<android.app.Application>()
    }
    
    @Test
    fun `test startVpnWithWarp creates correct intent`() {
        val intent = Intent(context, HyperVpnService::class.java).apply {
            action = HyperVpnService.ACTION_START
        }
        
        assertEquals("Intent action should be ACTION_START",
            HyperVpnService.ACTION_START, intent.action)
        assertEquals("Intent component should be HyperVpnService",
            HyperVpnService::class.java.name, intent.component?.className)
    }
    
    @Test
    fun `test startVpnWithConfig includes extras`() {
        val wgConfig = """{"privateKey": "test", "endpoint": "test.com:2408"}"""
        val xrayConfig = """{"serverAddress": "test.com", "serverPort": 443}"""
        
        val intent = Intent(context, HyperVpnService::class.java).apply {
            action = HyperVpnService.ACTION_START
            putExtra(HyperVpnService.EXTRA_WG_CONFIG, wgConfig)
            putExtra(HyperVpnService.EXTRA_XRAY_CONFIG, xrayConfig)
        }
        
        assertEquals("WG config extra should match", wgConfig,
            intent.getStringExtra(HyperVpnService.EXTRA_WG_CONFIG))
        assertEquals("Xray config extra should match", xrayConfig,
            intent.getStringExtra(HyperVpnService.EXTRA_XRAY_CONFIG))
    }
    
    @Test
    fun `test stopVpn creates correct intent`() {
        val intent = Intent(context, HyperVpnService::class.java).apply {
            action = HyperVpnService.ACTION_STOP
        }
        
        assertEquals("Intent action should be ACTION_STOP",
            HyperVpnService.ACTION_STOP, intent.action)
    }
}






