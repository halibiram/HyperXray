package com.hyperxray.an.ui.screens.log

/**
 * Parses log level from log entry string.
 * Optimized for performance with early returns and cached uppercase conversion.
 */
fun parseLogLevel(logEntry: String): LogLevel {
    if (logEntry.isEmpty()) return LogLevel.UNKNOWN
    
    val upperEntry = logEntry.uppercase()
    
    // Check for ERROR first (most critical)
    if (upperEntry.contains("ERROR") || upperEntry.contains("ERR")) {
        return LogLevel.ERROR
    }
    
    // Check for WARN
    if (upperEntry.contains("WARN") || upperEntry.contains("WARNING")) {
        return LogLevel.WARN
    }
    
    // "not socks request, try to parse http request" is a normal INFO message, not an error
    // This is a common message and doesn't affect performance
    if (upperEntry.contains("NOT SOCKS REQUEST") && upperEntry.contains("TRY TO PARSE HTTP")) {
        return LogLevel.INFO
    }
    
    // Check for INFO
    if (upperEntry.contains("INFO")) {
        return LogLevel.INFO
    }
    
    // Check for DEBUG patterns
    if (upperEntry.contains("DEBUG") || upperEntry.contains("DBG") || 
        upperEntry.contains("TLS") || upperEntry.contains("SSL") || 
        upperEntry.contains("HANDSHAKE") || upperEntry.contains("CERTIFICATE")) {
        return LogLevel.DEBUG
    }
    
    return LogLevel.UNKNOWN
}

/**
 * Checks if log entry contains AI-related information.
 * AI logs typically contain keywords like "DeepPolicyModel", "AI Optimizer", "ONNX", "inference", "model".
 */
fun isAiLog(logEntry: String): Boolean {
    val upperEntry = logEntry.uppercase()
    return upperEntry.contains("DEEPPOLICYMODEL") ||
           upperEntry.contains("AI OPTIMIZER") ||
           upperEntry.contains("HYPERXRAY AI OPTIMIZER") ||
           upperEntry.contains("ONNX") ||
           upperEntry.contains("INFERENCE") ||
           (upperEntry.contains("MODEL") && (upperEntry.contains("LOADED") || upperEntry.contains("VERIFIED") || upperEntry.contains("TENSOR"))) ||
           (upperEntry.contains("OPTIMIZER") && (upperEntry.contains("READY") || upperEntry.contains("INITIALIZ") || upperEntry.contains("STATUS"))) ||
           upperEntry.contains("MODELVERIFIER") ||
           upperEntry.contains("MODELFALLBACK") ||
           upperEntry.contains("REALITYARM") ||
           upperEntry.contains("BANDIT") ||
           (upperEntry.contains("TELEMETRY") && (upperEntry.contains("METRICS") || upperEntry.contains("CONTEXT")))
}

/**
 * Checks if log entry contains sniffing information.
 * Sniffing logs typically contain keywords like "sniffed", "sniffing", "domain", "target".
 * Xray sniffing logs can appear in various formats.
 */
fun isSniffingLog(logEntry: String): Boolean {
    val upperEntry = logEntry.uppercase()
    return upperEntry.contains("SNIFFED") ||
           upperEntry.contains("SNIFFING") ||
           upperEntry.contains("DESTOVERRIDE") ||
           // Pattern: "sniffed domain" or "sniffed target"
           (upperEntry.contains("SNIFFED") && (upperEntry.contains("DOMAIN") || upperEntry.contains("TARGET"))) ||
           // Pattern: "target domain" or "detected domain"
           (upperEntry.contains("TARGET") && upperEntry.contains("DOMAIN")) ||
           (upperEntry.contains("DETECT") && upperEntry.contains("DOMAIN")) ||
           // Pattern: "override destination" or "dest override"
           upperEntry.contains("OVERRIDE") && (upperEntry.contains("DEST") || upperEntry.contains("DESTINATION")) ||
           // Pattern: sniffing related keywords in context
           (upperEntry.contains("DOMAIN") && (upperEntry.contains("OVERRIDE") || upperEntry.contains("DETECT"))) ||
           // Pattern: "fakedns" related sniffing
           (upperEntry.contains("FAKEDNS") && (upperEntry.contains("DOMAIN") || upperEntry.contains("TARGET")))
}

/**
 * Extracts sniffed domain from sniffing log entries.
 * Tries multiple patterns to find sniffed domain in various log formats.
 */
fun extractSniffedDomain(logEntry: String): String? {
    return try {
        if (!isSniffingLog(logEntry)) return null
        
        // Pattern 1: sniffed domain=example.com or sniffed domain: example.com
        val sniffedPattern1 = Regex("""sniffed\s+domain\s*[=:]\s*([^\s,}\]]+)""", RegexOption.IGNORE_CASE)
        sniffedPattern1.find(logEntry)?.let {
            val domain = it.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'")
            if (domain.isNotEmpty() && domain != "null" && domain.contains(".")) {
                if (!domain.matches(Regex("""^\d+\.\d+\.\d+\.\d+$"""))) {
                    return domain
                }
            }
        }
        
        // Pattern 2: target domain=example.com or target domain: example.com
        val targetDomainPattern = Regex("""target\s+domain\s*[=:]\s*([^\s,}\]]+)""", RegexOption.IGNORE_CASE)
        targetDomainPattern.find(logEntry)?.let {
            val domain = it.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'")
            if (domain.isNotEmpty() && domain != "null" && domain.contains(".")) {
                if (!domain.matches(Regex("""^\d+\.\d+\.\d+\.\d+$"""))) {
                    return domain
                }
            }
        }
        
        // Pattern 3: domain: example.com (from sniffing logs)
        val domainPattern = Regex("""domain\s*[=:]\s*([a-zA-Z0-9][a-zA-Z0-9.-]+\.[a-zA-Z]{2,})""", RegexOption.IGNORE_CASE)
        domainPattern.find(logEntry)?.let {
            val domain = it.groupValues[1].trim()
            if (domain.isNotEmpty() && !domain.matches(Regex("""^\d+\.\d+\.\d+\.\d+$"""))) {
                return domain
            }
        }
        
        // Pattern 4: target: example.com (from sniffing logs)
        val targetPattern = Regex("""target\s*[=:]\s*([a-zA-Z0-9][a-zA-Z0-9.-]+\.[a-zA-Z]{2,})""", RegexOption.IGNORE_CASE)
        targetPattern.find(logEntry)?.let {
            val domain = it.groupValues[1].trim()
            if (domain.isNotEmpty() && !domain.matches(Regex("""^\d+\.\d+\.\d+\.\d+$"""))) {
                return domain
            }
        }
        
        // Pattern 5: detected domain=example.com
        val detectedPattern = Regex("""detected\s+domain\s*[=:]\s*([^\s,}\]]+)""", RegexOption.IGNORE_CASE)
        detectedPattern.find(logEntry)?.let {
            val domain = it.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'")
            if (domain.isNotEmpty() && domain != "null" && domain.contains(".")) {
                if (!domain.matches(Regex("""^\d+\.\d+\.\d+\.\d+$"""))) {
                    return domain
                }
            }
        }
        
        null
    } catch (e: Exception) {
        null
    }
}

/**
 * Checks if log entry contains DNS-related information.
 * DNS logs typically contain keywords like "dns", "query", "resolve", "cache", "A record", "AAAA record".
 */
fun isDnsLog(logEntry: String): Boolean {
    val upperEntry = logEntry.uppercase()
    return upperEntry.contains("DNS") ||
           (upperEntry.contains("QUERY") && (upperEntry.contains("DOMAIN") || upperEntry.contains("RESOLVE"))) ||
           upperEntry.contains("RESOLVE") ||
           (upperEntry.contains("CACHE") && (upperEntry.contains("HIT") || upperEntry.contains("MISS"))) ||
           upperEntry.contains("A RECORD") ||
           upperEntry.contains("AAAA RECORD") ||
           upperEntry.contains("CNAME RECORD") ||
           (upperEntry.contains("RECORD") && (upperEntry.contains("TYPE") || upperEntry.contains("CLASS")))
}

/**
 * Extracts DNS query domain from DNS log entries.
 */
fun extractDnsQuery(logEntry: String): String? {
    return try {
        if (!isDnsLog(logEntry)) return null
        
        // Pattern 1: query domain=example.com
        val queryPattern1 = Regex("""query\s+domain\s*[=:]\s*([^\s,}\]]+)""", RegexOption.IGNORE_CASE)
        queryPattern1.find(logEntry)?.let {
            val domain = it.groupValues[1].trim()
            if (domain.isNotEmpty() && domain != "null" && domain.contains(".")) {
                if (!domain.matches(Regex("""^\d+\.\d+\.\d+\.\d+$"""))) {
                    return domain
                }
            }
        }
        
        // Pattern 2: domain: example.com (from DNS logs)
        val domainPattern = Regex("""domain\s*[=:]\s*([a-zA-Z0-9][a-zA-Z0-9.-]+\.[a-zA-Z]{2,})""", RegexOption.IGNORE_CASE)
        domainPattern.find(logEntry)?.let {
            val domain = it.groupValues[1].trim()
            if (domain.isNotEmpty() && !domain.matches(Regex("""^\d+\.\d+\.\d+\.\d+$"""))) {
                return domain
            }
        }
        
        // Pattern 3: resolve example.com
        val resolvePattern = Regex("""resolve\s+([a-zA-Z0-9][a-zA-Z0-9.-]+\.[a-zA-Z]{2,})""", RegexOption.IGNORE_CASE)
        resolvePattern.find(logEntry)?.let {
            val domain = it.groupValues[1].trim()
            if (domain.isNotEmpty() && !domain.matches(Regex("""^\d+\.\d+\.\d+\.\d+$"""))) {
                return domain
            }
        }
        
        null
    } catch (e: Exception) {
        null
    }
}

/**
 * Parses connection type (TCP/UDP) from log entry string.
 */
fun parseConnectionType(logEntry: String): ConnectionType {
    val upperEntry = logEntry.uppercase()
    return when {
        // Check for TCP patterns
        upperEntry.contains(" TCP ") || 
        upperEntry.contains("TCP ") || 
        upperEntry.contains(" TCP") ||
        upperEntry.contains("\"TCP\"") ||
        upperEntry.contains("'TCP'") ||
        upperEntry.contains("TCP/") ||
        upperEntry.contains("ACCEPTING TCP") ||
        upperEntry.contains("TCP CONNECTION") ||
        (upperEntry.contains("TCP") && !upperEntry.contains("UDP")) -> ConnectionType.TCP
        
        // Check for UDP patterns
        upperEntry.contains(" UDP ") || 
        upperEntry.contains("UDP ") || 
        upperEntry.contains(" UDP") ||
        upperEntry.contains("\"UDP\"") ||
        upperEntry.contains("'UDP'") ||
        upperEntry.contains("UDP/") ||
        upperEntry.contains("ACCEPTING UDP") ||
        upperEntry.contains("UDP CONNECTION") -> ConnectionType.UDP
        
        else -> ConnectionType.UNKNOWN
    }
}

/**
 * Parses log entry to extract timestamp and message.
 * Returns a Pair of (timestamp, message).
 */
fun parseLogEntry(logEntry: String): Pair<String, String> {
    // Try to extract timestamp (format: YYYY/MM/DD HH:MM:SS or similar)
    var timestampEndIndex = 0
    while (timestampEndIndex < logEntry.length) {
        val c = logEntry[timestampEndIndex]
        if (Character.isDigit(c) || c == '/' || c == ' ' || c == ':' || c == '.' || c == '-') {
            timestampEndIndex++
        } else {
            break
        }
    }
    
    if (timestampEndIndex > 0) {
        val potentialTimestamp = logEntry.substring(0, timestampEndIndex).trim()
        // Check if it looks like a timestamp (contains date and time separators)
        if (potentialTimestamp.contains("/") && potentialTimestamp.contains(":") ||
            potentialTimestamp.contains("-") && potentialTimestamp.contains(":")) {
            val message = logEntry.substring(timestampEndIndex).trim()
            return Pair(potentialTimestamp, message)
        }
    }
    
    // No timestamp found, return empty timestamp and full message
    return Pair("", logEntry)
}

/**
 * Extracts SNI (Server Name Indication) information from Xray logs.
 * Tries multiple patterns to find SNI in various log formats.
 * Wrapped in try-catch to prevent crashes from malformed log entries.
 */
fun extractSNI(logEntry: String): String? {
    return try {
        if (logEntry.isBlank()) return null
        
        val upperEntry = logEntry.uppercase()
        
        // Pattern 1: serverName=example.com (from TLS handshake logs)
        try {
            val serverNamePattern = Regex("""serverName\s*[=:]\s*([^\s,}\]]+)""", RegexOption.IGNORE_CASE)
            serverNamePattern.find(logEntry)?.let {
                val sni = it.groupValues[1].trim()
                if (sni.isNotEmpty() && sni != "null" && sni != "nil" && sni != "''" && sni != "\"\"" && sni != "<nil>") {
                    if (!sni.matches(Regex("""^\d+\.\d+\.\d+\.\d+$""")) && sni.contains(".")) {
                        return sni
                    }
                }
            }
        } catch (e: Exception) {
            // Continue to next pattern
        }
        
        // Pattern 2: SNI: example.com or SNI=example.com (explicit SNI notation)
        try {
            val sniPattern1 = Regex("""\bSNI\s*[:=]\s*([^\s,}\]]+)""", RegexOption.IGNORE_CASE)
            sniPattern1.find(logEntry)?.let {
                val sni = it.groupValues[1].trim()
                if (sni.isNotEmpty() && sni != "null" && sni != "nil" && sni != "''" && sni != "\"\"" && sni != "<nil>") {
                    if (!sni.matches(Regex("""^\d+\.\d+\.\d+\.\d+$""")) && sni.contains(".")) {
                        return sni
                    }
                }
            }
        } catch (e: Exception) {
            // Continue to next pattern
        }
        
        // Pattern 3: "sni":"example.com" or 'sni':'example.com' (JSON format)
        try {
            val sniPattern2 = Regex("""["']sni["']\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            sniPattern2.find(logEntry)?.let {
                val sni = it.groupValues[1].trim()
                if (sni.isNotEmpty() && sni != "null" && sni != "nil" && sni.contains(".")) {
                    if (!sni.matches(Regex("""^\d+\.\d+\.\d+\.\d+$"""))) {
                        return sni
                    }
                }
            }
        } catch (e: Exception) {
            // Continue to next pattern
        }
        
        // Pattern 4: [TLS] Handshake start: serverName=example.com
        try {
            val tlsHandshakePattern = Regex("""\[TLS\].*?serverName\s*[=:]\s*([^\s,}\]]+)""", RegexOption.IGNORE_CASE)
            tlsHandshakePattern.find(logEntry)?.let {
                val sni = it.groupValues[1].trim()
                if (sni.isNotEmpty() && sni != "null" && sni != "nil" && sni.contains(".")) {
                    if (!sni.matches(Regex("""^\d+\.\d+\.\d+\.\d+$"""))) {
                        return sni
                    }
                }
            }
        } catch (e: Exception) {
            // Continue to next pattern
        }
        
        // Pattern 5: ClientHello with server_name extension
        try {
            val clientHelloPattern = Regex("""ClientHello.*?server_name[:\s=]+([a-zA-Z0-9][a-zA-Z0-9.-]+\.[a-zA-Z]{2,})""", RegexOption.IGNORE_CASE)
            clientHelloPattern.find(logEntry)?.let {
                val sni = it.groupValues[1].trim()
                if (sni.isNotEmpty() && sni.contains(".")) {
                    return sni
                }
            }
        } catch (e: Exception) {
            // Continue to next pattern
        }
        
        // Pattern 6: tls: serverName=example.com (Xray TLS log format)
        try {
            val tlsServerNamePattern = Regex("""tls[:\s].*?serverName\s*[=:]\s*([^\s,}\]]+)""", RegexOption.IGNORE_CASE)
            tlsServerNamePattern.find(logEntry)?.let {
                val sni = it.groupValues[1].trim()
                if (sni.isNotEmpty() && sni != "null" && sni != "nil" && sni.contains(".")) {
                    if (!sni.matches(Regex("""^\d+\.\d+\.\d+\.\d+$"""))) {
                        return sni
                    }
                }
            }
        } catch (e: Exception) {
            // Continue to next pattern
        }
        
        // Note: We don't extract SNI from general connection logs (tcp:example.com:443)
        // SNI should only come from TLS handshake logs with explicit serverName information
        
        null
    } catch (e: Exception) {
        // Return null on any error to prevent crash
        null
    }
}

/**
 * Extracts TLS certificate information from log entries.
 * Returns a map with certificate details if found.
 * Wrapped in try-catch to prevent crashes from malformed log entries.
 */
fun extractCertificateInfo(logEntry: String): Map<String, String>? {
    return try {
        if (logEntry.isBlank()) return null
        
        val upperEntry = logEntry.uppercase()
        
        if (!upperEntry.contains("CERTIFICATE") && !upperEntry.contains("CERT")) {
            return null
        }
        
        val certInfo = mutableMapOf<String, String>()
        
        // Extract certificate issuer
        try {
            val issuerPattern = Regex("""issuer[:\s=]+([^\n,}]+)""", RegexOption.IGNORE_CASE)
            issuerPattern.find(logEntry)?.let {
                val issuer = it.groupValues[1].trim()
                if (issuer.isNotEmpty() && issuer != "null") {
                    certInfo["issuer"] = issuer
                }
            }
        } catch (e: Exception) {
            // Continue
        }
        
        // Extract certificate subject
        try {
            val subjectPattern = Regex("""subject[:\s=]+([^\n,}]+)""", RegexOption.IGNORE_CASE)
            subjectPattern.find(logEntry)?.let {
                val subject = it.groupValues[1].trim()
                if (subject.isNotEmpty() && subject != "null") {
                    certInfo["subject"] = subject
                }
            }
        } catch (e: Exception) {
            // Continue
        }
        
        // Extract certificate expiration
        try {
            val expirationPattern = Regex("""(?:expires?|valid|notAfter)[:\s=]+([^\n,}]+)""", RegexOption.IGNORE_CASE)
            expirationPattern.find(logEntry)?.let {
                val expiration = it.groupValues[1].trim()
                if (expiration.isNotEmpty()) {
                    certInfo["expiration"] = expiration
                }
            }
        } catch (e: Exception) {
            // Continue
        }
        
        // Extract TLS version
        try {
            val tlsVersionPattern = Regex("""TLS\s*([0-9.]+)""", RegexOption.IGNORE_CASE)
            tlsVersionPattern.find(logEntry)?.let {
                certInfo["tlsVersion"] = it.groupValues[1].trim()
            }
        } catch (e: Exception) {
            // Continue
        }
        
        // Extract cipher suite
        try {
            val cipherPattern = Regex("""cipher[:\s=]+([^\n,}]+)""", RegexOption.IGNORE_CASE)
            cipherPattern.find(logEntry)?.let {
                val cipher = it.groupValues[1].trim()
                if (cipher.isNotEmpty()) {
                    certInfo["cipher"] = cipher
                }
            }
        } catch (e: Exception) {
            // Continue
        }
        
        if (certInfo.isNotEmpty()) certInfo else null
    } catch (e: Exception) {
        // Return null on any error to prevent crash
        null
    }
}

