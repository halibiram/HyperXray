package com.hyperxray.an.ui.screens.utils.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
fun NetworkInfoTool() {
    val context = LocalContext.current
    var networkInfo by remember { mutableStateOf<NetworkDetails?>(null) }
    
    fun refreshInfo() {
        networkInfo = getNetworkDetails(context)
    }

    LaunchedEffect(Unit) {
        refreshInfo()
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A1A)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Device Network Info",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                IconButton(onClick = { refreshInfo() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
                }
            }

            networkInfo?.let { info ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow("Connection Type", info.type)
                    if (info.ssid != null) {
                        InfoRow("SSID", info.ssid)
                    }
                    InfoRow("Local IP", info.localIp)
                    if (info.gateway != null) {
                        InfoRow("Gateway", info.gateway)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )
    }
}

data class NetworkDetails(
    val type: String,
    val ssid: String?,
    val localIp: String,
    val gateway: String?
)

private fun getNetworkDetails(context: Context): NetworkDetails {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork
    val capabilities = connectivityManager.getNetworkCapabilities(network)
    
    val type = when {
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile Data"
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
        else -> "Unknown / Disconnected"
    }

    var ssid: String? = null
    var gateway: String? = null
    
    if (type == "Wi-Fi") {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifiManager.connectionInfo
        ssid = info.ssid.trim('"')
        if (ssid == "<unknown ssid>") ssid = null
        
        val dhcp = wifiManager.dhcpInfo
        gateway = intToIp(dhcp.gateway)
    }

    val localIp = getLocalIpAddress() ?: "Unavailable"

    return NetworkDetails(type, ssid, localIp, gateway)
}

private fun getLocalIpAddress(): String? {
    try {
        val en = NetworkInterface.getNetworkInterfaces()
        while (en.hasMoreElements()) {
            val intf = en.nextElement()
            val enumIpAddr = intf.inetAddresses
            while (enumIpAddr.hasMoreElements()) {
                val inetAddress = enumIpAddr.nextElement()
                if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                    return inetAddress.hostAddress
                }
            }
        }
    } catch (ex: Exception) {
        ex.printStackTrace()
    }
    return null
}

private fun intToIp(i: Int): String {
    return "${i and 0xFF}.${i shr 8 and 0xFF}.${i shr 16 and 0xFF}.${i shr 24 and 0xFF}"
}
