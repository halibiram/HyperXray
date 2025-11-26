package com.hyperxray.an.core.network

import android.util.Log
import java.io.BufferedReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocketFactory

/**
 * Result of a connectivity test operation.
 */
sealed class ConnectivityTestResult {
    /**
     * Test succeeded with measured latency.
     * @param latencyMs Latency in milliseconds
     */
    data class Success(val latencyMs: Long) : ConnectivityTestResult()

    /**
     * Test failed with an error message.
     * @param errorMessage Error description
     */
    data class Failure(val errorMessage: String) : ConnectivityTestResult()
}

/**
 * Configuration for connectivity testing.
 */
data class ConnectivityTestConfig(
    /**
     * Target URL to test (e.g., "http://www.gstatic.com/generate_204")
     */
    val targetUrl: String,
    /**
     * SOCKS proxy address (e.g., "127.0.0.1")
     */
    val proxyAddress: String,
    /**
     * SOCKS proxy port (e.g., 10808)
     */
    val proxyPort: Int,
    /**
     * Connection timeout in milliseconds (e.g., 3000)
     */
    val timeoutMs: Int
)

/**
 * Tests network connectivity through a SOCKS proxy by making an HTTP request.
 * 
 * This class handles the low-level socket operations for connectivity testing,
 * including HTTP and HTTPS connections through a SOCKS proxy.
 */
class ConnectivityTester {
    /**
     * Performs a connectivity test using the provided configuration.
     * 
     * @param config Test configuration including target URL, proxy settings, and timeout
     * @return [ConnectivityTestResult] indicating success with latency or failure with error message
     */
    fun testConnectivity(config: ConnectivityTestConfig): ConnectivityTestResult {
        val url: URL
        try {
            url = URL(config.targetUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid URL: ${config.targetUrl}", e)
            return ConnectivityTestResult.Failure("Invalid URL: ${config.targetUrl}")
        }

        val host = url.host
        val port = if (url.port > 0) url.port else url.defaultPort
        val path = if (url.path.isNullOrEmpty()) "/" else url.path
        val isHttps = url.protocol == "https"
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(config.proxyAddress, config.proxyPort))
        val timeout = config.timeoutMs
        val start = System.currentTimeMillis()

        return try {
            Socket(proxy).use { socket ->
                socket.soTimeout = timeout
                socket.connect(InetSocketAddress(host, port), timeout)
                
                val (writer, reader) = if (isHttps) {
                    val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(socket, host, port, true) as javax.net.ssl.SSLSocket
                    sslSocket.startHandshake()
                    Pair(
                        sslSocket.outputStream.bufferedWriter(),
                        sslSocket.inputStream.bufferedReader()
                    )
                } else {
                    Pair(
                        socket.getOutputStream().bufferedWriter(),
                        socket.getInputStream().bufferedReader()
                    )
                }
                
                writer.write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n")
                writer.flush()
                val firstLine = reader.readLine()
                val latency = System.currentTimeMillis() - start
                
                if (firstLine != null && firstLine.startsWith("HTTP/")) {
                    ConnectivityTestResult.Success(latency)
                } else {
                    ConnectivityTestResult.Failure("Invalid HTTP response: $firstLine")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connectivity test failed", e)
            ConnectivityTestResult.Failure(e.message ?: "Unknown error")
        }
    }

    companion object {
        private const val TAG = "ConnectivityTester"
    }
}










