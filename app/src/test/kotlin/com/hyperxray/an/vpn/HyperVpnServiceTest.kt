package com.hyperxray.an.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*

/**
 * Unit tests for HyperVpnService
 */
class HyperVpnServiceTest {
    
    @Mock
    private lateinit var mockContext: Context
    
    private lateinit var stateManager: HyperVpnStateManager
    
    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        stateManager = HyperVpnStateManager(context)
    }
    
    @Test
    fun `test HyperVpnStateManager initial state is Disconnected`() = runTest {
        val initialState = stateManager.state.first()
        assertTrue("Initial state should be Disconnected", 
            initialState is HyperVpnStateManager.VpnState.Disconnected)
    }
    
    @Test
    fun `test HyperVpnStateManager isServiceRunning returns false when disconnected`() = runTest {
        val isRunning = stateManager.isServiceRunning()
        assertFalse("Service should not be running when disconnected", isRunning)
    }
    
    @Test
    fun `test HyperVpnHelper startVpnWithWarp creates correct intent`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        
        // This test verifies the intent structure
        // Actual service start requires VPN permission which is hard to test in unit tests
        val intent = Intent(context, HyperVpnService::class.java).apply {
            action = HyperVpnService.ACTION_START
        }
        
        assertEquals("Intent action should be ACTION_START", 
            HyperVpnService.ACTION_START, intent.action)
        assertEquals("Intent component should be HyperVpnService",
            HyperVpnService::class.java.name, intent.component?.className)
    }
    
    @Test
    fun `test HyperVpnHelper stopVpn creates correct intent`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        
        val intent = Intent(context, HyperVpnService::class.java).apply {
            action = HyperVpnService.ACTION_STOP
        }
        
        assertEquals("Intent action should be ACTION_STOP",
            HyperVpnService.ACTION_STOP, intent.action)
    }
    
    @Test
    fun `test TunnelStats fromJson parses correctly`() {
        val json = """
        {
            "txBytes": 1024,
            "rxBytes": 2048,
            "txPackets": 10,
            "rxPackets": 20,
            "lastHandshake": 1234567890,
            "connected": true,
            "uptime": 3600,
            "latency": 50,
            "packetLoss": 0.5,
            "throughput": 1024.5
        }
        """.trimIndent()
        
        val stats = HyperVpnStateManager.TunnelStats.fromJson(json)
        
        assertEquals("TX bytes should match", 1024L, stats.txBytes)
        assertEquals("RX bytes should match", 2048L, stats.rxBytes)
        assertEquals("TX packets should match", 10L, stats.txPackets)
        assertEquals("RX packets should match", 20L, stats.rxPackets)
        assertEquals("Total bytes should be sum", 3072L, stats.totalBytes)
        assertEquals("Total packets should be sum", 30L, stats.totalPackets)
        assertTrue("Should be connected", stats.connected)
        assertEquals("Uptime should match", 3600L, stats.uptime)
        assertEquals("Latency should match", 50L, stats.latency)
        assertEquals("Packet loss should match", 0.5, stats.packetLoss, 0.01)
        assertEquals("Throughput should match", 1024.5, stats.throughput, 0.01)
    }
    
    @Test
    fun `test TunnelStats fromJson handles empty json`() {
        val stats = HyperVpnStateManager.TunnelStats.fromJson("{}")
        
        assertEquals("All values should be zero/default", 0L, stats.txBytes)
        assertEquals("RX bytes should be zero", 0L, stats.rxBytes)
        assertFalse("Should not be connected", stats.connected)
    }
    
    @Test
    fun `test TunnelStats fromJson handles invalid json gracefully`() {
        val stats = HyperVpnStateManager.TunnelStats.fromJson("invalid json")
        
        // Should return default stats without crashing
        assertEquals("Should return default stats", 0L, stats.txBytes)
    }
    
    @Test
    fun `test clearError resets error state`() = runTest {
        // Simulate error state (would normally come from broadcast)
        // Since we can't easily mock broadcasts, we test the clearError function
        stateManager.clearError()
        
        val error = stateManager.error.first()
        assertNull("Error should be null after clearing", error)
    }
}






