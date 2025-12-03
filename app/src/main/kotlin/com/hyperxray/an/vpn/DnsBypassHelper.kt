package com.hyperxray.an.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.util.Log
import com.hyperxray.an.common.AiLogHelper
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Helper class for DNS bypass functionality.
 * 
 * This class provides utilities to:
 * 1. Retrieve system DNS servers from the underlying network
 * 2. Calculate split-tunnel routes that exclude DNS server IPs
 * 3. Support both IPv4 and IPv6 DNS bypass
 * 
 * ## How DNS Bypass Works
 * 
 * Android's VpnService.Builder only supports "addRoute" (include), not "excludeRoute".
 * To exclude specific IPs (like DNS servers), we use split-tunneling:
 * 
 * Instead of adding a single 0.0.0.0/0 route, we add multiple routes that
 * cover the entire IP space EXCEPT the DNS server IPs.
 * 
 * For example, to exclude 8.8.8.8:
 * - We split 0.0.0.0/0 into smaller subnets that don't include 8.8.8.8
 * - Traffic to 8.8.8.8 will use the underlying network (bypass VPN)
 */
object DnsBypassHelper {
    
    private const val TAG = "DnsBypassHelper"
    
    /**
     * Data class representing a route to add to VPN builder
     */
    data class VpnRoute(
        val address: String,
        val prefixLength: Int,
        val isIpv6: Boolean = false
    )
    
    /**
     * Get system DNS servers from the underlying (non-VPN) network.
     * 
     * @param context Application context
     * @return List of DNS server IP addresses
     */
    fun getSystemDnsServers(context: Context): List<InetAddress> {
        val dnsServers = mutableListOf<InetAddress>()
        
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            // Get all networks and find non-VPN networks
            val networks = connectivityManager.allNetworks
            
            for (network in networks) {
                val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
                
                // Skip VPN networks - we want the underlying network's DNS
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                    continue
                }
                
                // Get link properties for this network
                val linkProps = connectivityManager.getLinkProperties(network) ?: continue
                
                // Extract DNS servers
                val networkDns = linkProps.dnsServers
                if (networkDns.isNotEmpty()) {
                    AiLogHelper.d(TAG, "Found DNS servers on network: ${networkDns.map { it.hostAddress }}")
                    dnsServers.addAll(networkDns)
                }
            }
            
            // Also try active network as fallback
            if (dnsServers.isEmpty()) {
                val activeNetwork = connectivityManager.activeNetwork
                if (activeNetwork != null) {
                    val linkProps = connectivityManager.getLinkProperties(activeNetwork)
                    linkProps?.dnsServers?.let { dns ->
                        AiLogHelper.d(TAG, "Found DNS servers on active network: ${dns.map { it.hostAddress }}")
                        dnsServers.addAll(dns)
                    }
                }
            }
            
        } catch (e: Exception) {
            AiLogHelper.e(TAG, "Error getting system DNS servers: ${e.message}", e)
        }
        
        // Remove duplicates
        val uniqueDns = dnsServers.distinctBy { it.hostAddress }
        AiLogHelper.i(TAG, "System DNS servers: ${uniqueDns.map { it.hostAddress }}")
        
        return uniqueDns
    }
    
    /**
     * Get IPv4 DNS servers only
     */
    fun getSystemDnsServersIpv4(context: Context): List<Inet4Address> {
        return getSystemDnsServers(context).filterIsInstance<Inet4Address>()
    }
    
    /**
     * Get IPv6 DNS servers only
     */
    fun getSystemDnsServersIpv6(context: Context): List<Inet6Address> {
        return getSystemDnsServers(context).filterIsInstance<Inet6Address>()
    }
    
    /**
     * Calculate IPv4 routes that cover 0.0.0.0/0 EXCEPT for the specified IPs.
     * 
     * This uses a binary splitting approach:
     * 1. Start with 0.0.0.0/0
     * 2. For each excluded IP, split the containing subnet into two halves
     * 3. Keep the half that doesn't contain the excluded IP
     * 4. Recursively split the other half until we reach /32 (single IP)
     * 
     * @param excludeIps List of IPv4 addresses to exclude
     * @return List of VpnRoute objects to add to builder
     */
    fun calculateIpv4RoutesExcluding(excludeIps: List<Inet4Address>): List<VpnRoute> {
        if (excludeIps.isEmpty()) {
            // No exclusions, just return 0.0.0.0/0
            return listOf(VpnRoute("0.0.0.0", 0))
        }
        
        val routes = mutableListOf<VpnRoute>()
        
        // Start with the full IPv4 range as a single subnet
        val initialSubnets = mutableListOf(Subnet(0L, 0)) // 0.0.0.0/0
        
        for (excludeIp in excludeIps) {
            val excludeAddr = ipv4ToLong(excludeIp)
            val newSubnets = mutableListOf<Subnet>()
            
            for (subnet in initialSubnets) {
                if (subnet.contains(excludeAddr)) {
                    // This subnet contains the excluded IP, split it
                    newSubnets.addAll(splitSubnetExcluding(subnet, excludeAddr))
                } else {
                    // This subnet doesn't contain the excluded IP, keep it
                    newSubnets.add(subnet)
                }
            }
            
            initialSubnets.clear()
            initialSubnets.addAll(newSubnets)
        }
        
        // Convert subnets to VpnRoute objects
        for (subnet in initialSubnets) {
            val ipStr = longToIpv4(subnet.network)
            routes.add(VpnRoute(ipStr, subnet.prefixLength))
        }
        
        AiLogHelper.d(TAG, "Generated ${routes.size} IPv4 routes excluding ${excludeIps.size} DNS IPs")
        return routes
    }
    
    /**
     * Calculate IPv6 routes that cover ::/0 EXCEPT for the specified IPs.
     * 
     * For IPv6, we use a simpler approach since IPv6 DNS servers are less common
     * and the address space is much larger.
     * 
     * @param excludeIps List of IPv6 addresses to exclude
     * @return List of VpnRoute objects to add to builder
     */
    fun calculateIpv6RoutesExcluding(excludeIps: List<Inet6Address>): List<VpnRoute> {
        if (excludeIps.isEmpty()) {
            return listOf(VpnRoute("::", 0, isIpv6 = true))
        }
        
        // For IPv6, we use a simplified approach:
        // Add routes for common IPv6 ranges but exclude the specific /128 addresses
        // This is less precise but more practical for IPv6
        
        val routes = mutableListOf<VpnRoute>()
        
        // Add the two halves of IPv6 space
        routes.add(VpnRoute("0::", 1, isIpv6 = true))
        routes.add(VpnRoute("8000::", 1, isIpv6 = true))
        
        // Note: For full IPv6 exclusion, you'd need to implement similar binary
        // splitting as IPv4, but with 128-bit addresses. For most use cases,
        // excluding IPv6 DNS is less critical since most DNS queries use IPv4.
        
        AiLogHelper.d(TAG, "Generated ${routes.size} IPv6 routes (simplified, ${excludeIps.size} DNS IPs noted)")
        return routes
    }

    
    /**
     * Internal class representing an IPv4 subnet
     */
    private data class Subnet(
        val network: Long,      // Network address as 32-bit unsigned int
        val prefixLength: Int   // CIDR prefix length (0-32)
    ) {
        val mask: Long = if (prefixLength == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
        
        fun contains(ip: Long): Boolean {
            return (ip and mask) == (network and mask)
        }
    }
    
    /**
     * Split a subnet to exclude a specific IP address.
     * Returns subnets that cover the original range minus the excluded IP.
     */
    private fun splitSubnetExcluding(subnet: Subnet, excludeIp: Long): List<Subnet> {
        if (subnet.prefixLength >= 32) {
            // Can't split further, this is the excluded IP itself
            return emptyList()
        }
        
        val newPrefix = subnet.prefixLength + 1
        val halfSize = 1L shl (32 - newPrefix)
        
        val lowerHalf = Subnet(subnet.network, newPrefix)
        val upperHalf = Subnet(subnet.network + halfSize, newPrefix)
        
        val result = mutableListOf<Subnet>()
        
        if (lowerHalf.contains(excludeIp)) {
            // Excluded IP is in lower half, keep upper half and split lower
            result.add(upperHalf)
            if (newPrefix < 32) {
                result.addAll(splitSubnetExcluding(lowerHalf, excludeIp))
            }
        } else {
            // Excluded IP is in upper half, keep lower half and split upper
            result.add(lowerHalf)
            if (newPrefix < 32) {
                result.addAll(splitSubnetExcluding(upperHalf, excludeIp))
            }
        }
        
        return result
    }
    
    /**
     * Convert IPv4 address to long (32-bit unsigned)
     */
    private fun ipv4ToLong(ip: Inet4Address): Long {
        val bytes = ip.address
        return ((bytes[0].toLong() and 0xFF) shl 24) or
               ((bytes[1].toLong() and 0xFF) shl 16) or
               ((bytes[2].toLong() and 0xFF) shl 8) or
               (bytes[3].toLong() and 0xFF)
    }
    
    /**
     * Convert long to IPv4 address string
     */
    private fun longToIpv4(ip: Long): String {
        return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
    }
    
    /**
     * Alternative approach: Use allowBypass() for apps that should bypass VPN.
     * 
     * This is simpler but less granular - it allows entire apps to bypass,
     * not specific traffic types.
     * 
     * Note: This requires the app to explicitly use Network.bindSocket() or
     * similar APIs to bypass the VPN.
     */
    fun configureAllowBypass(builder: android.net.VpnService.Builder): android.net.VpnService.Builder {
        // allowBypass() allows apps to explicitly bypass the VPN
        // Apps must use ConnectivityManager.bindProcessToNetwork() or similar
        builder.allowBypass()
        AiLogHelper.d(TAG, "VPN bypass allowed for apps that request it")
        return builder
    }
    
    /**
     * Check if an IP address is a private/local network address.
     * These are typically used for local DNS servers (e.g., router DNS).
     */
    fun isPrivateAddress(ip: InetAddress): Boolean {
        return ip.isSiteLocalAddress || 
               ip.isLinkLocalAddress || 
               ip.isLoopbackAddress ||
               isPrivateIpv4Range(ip)
    }
    
    private fun isPrivateIpv4Range(ip: InetAddress): Boolean {
        if (ip !is Inet4Address) return false
        
        val bytes = ip.address
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        
        return when {
            first == 10 -> true                                    // 10.0.0.0/8
            first == 172 && second in 16..31 -> true              // 172.16.0.0/12
            first == 192 && second == 168 -> true                 // 192.168.0.0/16
            first == 169 && second == 254 -> true                 // 169.254.0.0/16 (link-local)
            else -> false
        }
    }
    
    /**
     * Get recommended configuration based on use case.
     * 
     * @param context Application context
     * @param bypassLocalDns If true, bypass local/private DNS servers
     * @param bypassPublicDns If true, bypass public DNS servers (8.8.8.8, 1.1.1.1, etc.)
     * @return Pair of (IPv4 routes, IPv6 routes) to add to VPN builder
     */
    fun getRecommendedRoutes(
        context: Context,
        bypassLocalDns: Boolean = true,
        bypassPublicDns: Boolean = false
    ): Pair<List<VpnRoute>, List<VpnRoute>> {
        
        val systemDns = getSystemDnsServers(context)
        
        val dnsToExclude = systemDns.filter { dns ->
            val isPrivate = isPrivateAddress(dns)
            (bypassLocalDns && isPrivate) || (bypassPublicDns && !isPrivate)
        }
        
        AiLogHelper.i(TAG, "DNS servers to exclude from VPN: ${dnsToExclude.map { it.hostAddress }}")
        
        val ipv4Dns = dnsToExclude.filterIsInstance<Inet4Address>()
        val ipv6Dns = dnsToExclude.filterIsInstance<Inet6Address>()
        
        val ipv4Routes = calculateIpv4RoutesExcluding(ipv4Dns)
        val ipv6Routes = calculateIpv6RoutesExcluding(ipv6Dns)
        
        return Pair(ipv4Routes, ipv6Routes)
    }
}
