package com.hyperxray.an.core.network.dns

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.InetAddress

private const val TAG = "DnsPacketUtils"
private const val BUFFER_SIZE = 512

/**
 * DNS query parser for extracting hostname and query details from DNS packets
 */
object DnsQueryParser {
    /**
     * Parse DNS query packet and extract query information
     */
    fun parseQuery(queryData: ByteArray): DnsQuery? {
        if (queryData.size < 12) {
            return null
        }
        
        try {
            val buffer = ByteBuffer.wrap(queryData).apply {
                order(ByteOrder.BIG_ENDIAN)
            }
            
            // Parse DNS header
            val transactionId = buffer.short.toInt() and 0xFFFF
            val flags = buffer.short.toInt() and 0xFFFF
            val questions = buffer.short.toInt() and 0xFFFF
            
            if (questions == 0) {
                return null
            }
            
            // Parse question section - extract hostname
            buffer.position(12) // Skip header
            val hostname = parseHostname(buffer) ?: return null
            val qtype = buffer.short.toInt() and 0xFFFF
            val qclass = buffer.short.toInt() and 0xFFFF
            
            return DnsQuery(transactionId, hostname, qtype, qclass)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse DNS query: ${e.message}")
            return null
        }
    }
    
    /**
     * Parse hostname from DNS packet buffer
     */
    private fun parseHostname(buffer: ByteBuffer): String? {
        val parts = mutableListOf<String>()
        
        while (buffer.hasRemaining()) {
            val length = buffer.get().toInt() and 0xFF
            if (length == 0) {
                break
            }
            if (length > 63) {
                // Compression pointer - simplified handling
                return null
            }
            val bytes = ByteArray(length)
            buffer.get(bytes)
            parts.add(String(bytes, Charsets.UTF_8))
        }
        
        return if (parts.isNotEmpty()) parts.joinToString(".") else null
    }
}

/**
 * DNS query data class
 */
data class DnsQuery(
    val transactionId: Int,
    val hostname: String,
    val qtype: Int,
    val qclass: Int
)

/**
 * DNS response builder for creating DNS response packets
 */
object DnsResponseBuilder {
    /**
     * Build DNS response packet from original query and resolved addresses
     */
    fun buildResponse(originalQuery: ByteArray, addresses: List<InetAddress>): ByteArray? {
        if (addresses.isEmpty()) {
            return null
        }
        
        try {
            // Parse query to get transaction ID and question
            val transactionId = ByteBuffer.wrap(originalQuery, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()
            val flags = 0x8180.toShort() // Standard response, no error
            val questions = 1.toShort()
            val answers = addresses.size.toShort()
            val authority = 0.toShort()
            val additional = 0.toShort()
            
            // Calculate response size
            val questionSize = originalQuery.size - 12 // Skip header
            val answerSize = addresses.sumOf { 16 } // Each A record is ~16 bytes
            val responseSize = 12 + questionSize + answerSize
            
            val response = ByteArray(responseSize)
            val buffer = ByteBuffer.wrap(response).order(ByteOrder.BIG_ENDIAN)
            
            // Write header
            buffer.putShort(transactionId.toShort())
            buffer.putShort(flags)
            buffer.putShort(questions)
            buffer.putShort(answers)
            buffer.putShort(authority)
            buffer.putShort(additional)
            
            // Copy question section
            System.arraycopy(originalQuery, 12, response, 12, questionSize)
            
            // Write answer section
            var offset = 12 + questionSize
            addresses.forEach { address ->
                val ipBytes = address.address
                // Name pointer to question
                response[offset++] = 0xC0.toByte()
                response[offset++] = 0x0C.toByte()
                // Type A (0x0001)
                response[offset++] = 0x00
                response[offset++] = 0x01
                // Class IN (0x0001)
                response[offset++] = 0x00
                response[offset++] = 0x01
                // TTL (3600 seconds)
                response[offset++] = 0x00
                response[offset++] = 0x00
                response[offset++] = 0x0E.toByte()
                response[offset++] = 0x10
                // Data length (4 bytes for IPv4)
                response[offset++] = 0x00
                response[offset++] = 0x04
                // IP address
                System.arraycopy(ipBytes, 0, response, offset, 4)
                offset += 4
            }
            
            return response
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build DNS response: ${e.message}")
            return null
        }
    }
    
    /**
     * Build DNS query packet from domain name
     */
    fun buildQuery(domain: String): ByteArray? {
        return try {
            val buffer = ByteBuffer.allocate(BUFFER_SIZE).apply {
                order(ByteOrder.BIG_ENDIAN)
            }
            
            // DNS header
            val transactionId = (System.currentTimeMillis().toInt() and 0xFFFF).toShort()
            buffer.putShort(transactionId) // Transaction ID
            buffer.putShort(0x0100.toShort()) // Flags: standard query, recursion desired
            buffer.putShort(1) // Questions: 1
            buffer.putShort(0) // Answer RRs: 0
            buffer.putShort(0) // Authority RRs: 0
            buffer.putShort(0) // Additional RRs: 0
            
            // Question section
            domain.split(".").forEach { part ->
                buffer.put(part.length.toByte())
                buffer.put(part.toByteArray(Charsets.UTF_8))
            }
            buffer.put(0) // Null terminator
            buffer.putShort(1) // QTYPE: A (IPv4)
            buffer.putShort(1) // QCLASS: IN (Internet)
            
            val querySize = buffer.position()
            val queryData = ByteArray(querySize)
            buffer.flip()
            buffer.get(queryData)
            
            queryData
        } catch (e: Exception) {
            Log.e(TAG, "Error building DNS query for $domain: ${e.message}")
            null
        }
    }
}

/**
 * DNS response parser for extracting IP addresses and TTL from DNS packets
 */
object DnsResponseParser {
    /**
     * Parse result from DNS response
     */
    data class DnsParseResult(
        val addresses: List<InetAddress>,
        val ttl: Long? = null,
        val isNxDomain: Boolean = false
    )
    
    /**
     * Parse DNS response and extract IP addresses, TTL, and NXDOMAIN flag
     * Full DNS packet parser implementation according to RFC 1035
     */
    fun parseResponseWithTtl(responseData: ByteArray, hostname: String): DnsParseResult {
        return try {
            Log.d(TAG, "🔍 [PARSER] Starting DNS response parse for $hostname (${responseData.size} bytes)")
            
            if (responseData.size < 12) {
                Log.w(TAG, "❌ [PARSER] DNS response too short: ${responseData.size} bytes (minimum 12)")
                return DnsParseResult(emptyList())
            }

            val buffer = ByteBuffer.wrap(responseData).apply {
                order(ByteOrder.BIG_ENDIAN)
            }

            // Parse DNS header
            val transactionId = buffer.short.toInt() and 0xFFFF
            val flags = buffer.short.toInt() and 0xFFFF
            val questions = buffer.short.toInt() and 0xFFFF
            val answers = buffer.short.toInt() and 0xFFFF
            val authority = buffer.short.toInt() and 0xFFFF
            val additional = buffer.short.toInt() and 0xFFFF
            
            Log.d(TAG, "📊 [PARSER] DNS header: TXID=0x%04x, flags=0x%04x, Q=%d, A=%d, NS=%d, AR=%d".format(
                transactionId, flags, questions, answers, authority, additional))

            // Check if response is valid (QR bit must be 1, RCODE should be 0)
            val qr = (flags shr 15) and 0x01
            val rcode = flags and 0x0F

            if (qr != 1) {
                val firstBytes = responseData.take(16).joinToString(" ") { "%02x".format(it) }
                Log.w(TAG, "Invalid DNS response: not a response packet (QR=$qr, flags=0x%04x, size=${responseData.size}, first 16 bytes: $firstBytes)".format(flags))
                if (responseData.size >= 12 && questions > 0) {
                    Log.d(TAG, "Attempting to parse anyway (might be malformed DNS packet)")
                } else {
                    return DnsParseResult(emptyList())
                }
            }

            if (rcode != 0) {
                // NXDOMAIN or other error
                if (rcode == 3) {
                    Log.d(TAG, "NXDOMAIN for $hostname")
                    return DnsParseResult(emptyList(), isNxDomain = true)
                } else {
                    Log.w(TAG, "DNS error response for $hostname: RCODE=$rcode")
                    return DnsParseResult(emptyList())
                }
            }

            // Skip question section
            buffer.position(12)
            Log.d(TAG, "📝 [PARSER] Skipping question section (position: ${buffer.position()})")
            for (i in 0 until questions) {
                val qStartPos = buffer.position()
                skipDnsName(buffer)
                val qtype = buffer.getShort().toInt() and 0xFFFF
                val qclass = buffer.getShort().toInt() and 0xFFFF
                Log.d(TAG, "  Q${i+1}: pos=$qStartPos→${buffer.position()}, type=$qtype, class=$qclass")
            }

            // Parse answer section
            val addresses = mutableListOf<InetAddress>()
            var minTtl: Long? = null
            val answerStartPos = buffer.position()
            Log.d(TAG, "📋 [PARSER] Parsing answer section: $answers answers (start position: $answerStartPos)")

            for (i in 0 until answers) {
                // Check buffer bounds before parsing
                if (buffer.position() >= buffer.limit()) {
                    Log.w(TAG, "❌ [PARSER] Buffer overflow: position ${buffer.position()} >= limit ${buffer.limit()}, stopping answer parsing")
                    break
                }

                val answerOffset = buffer.position()
                Log.d(TAG, "  📍 [PARSER] Answer ${i+1}/$answers: parsing at offset $answerOffset")

                // Parse name (may use compression)
                try {
                    val nameStartPos = buffer.position()
                    val nameEndPos = skipDnsName(buffer)
                    val nameBytes = nameEndPos - nameStartPos
                    if (nameEndPos != buffer.position()) {
                        Log.w(TAG, "⚠️ [PARSER] DNS name skip mismatch: expected position $nameEndPos, actual ${buffer.position()}, correcting... (name bytes: $nameBytes)")
                        buffer.position(nameEndPos)
                    } else {
                        Log.d(TAG, "    ✓ [PARSER] Name skipped: $nameBytes bytes (pos: $nameStartPos→$nameEndPos)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "❌ [PARSER] Error skipping DNS name at offset $answerOffset: ${e.message}")
                    break
                }

                // Check if we have enough bytes for type, class, TTL, and length
                if (buffer.remaining() < 10) {
                    Log.w(TAG, "❌ [PARSER] Not enough bytes for answer record at position ${buffer.position()} (need 10, have ${buffer.remaining()})")
                    break
                }

                val type = buffer.short.toInt() and 0xFFFF
                Log.d(TAG, "    📌 [PARSER] Record type: $type (0x%04x)".format(type))
                
                // Validate type - if it looks like a compression pointer (0xC000-0xFFFF), name skip failed
                if ((type and 0xC000) == 0xC000) {
                    Log.w(TAG, "⚠️ [PARSER] Invalid record type $type (looks like compression pointer 0x%04x), name skip may have failed at position $answerOffset".format(type))
                    if (buffer.remaining() >= 8) {
                        buffer.getShort() // Skip what we thought was class
                        buffer.int // Skip what we thought was TTL
                        val dataLength = buffer.short.toInt() and 0xFFFF
                        if (buffer.remaining() >= dataLength) {
                            buffer.position(buffer.position() + dataLength)
                        }
                    }
                    continue
                }
                
                val qclass = buffer.short.toInt() and 0xFFFF
                val ttl = buffer.int.toLong() and 0xFFFFFFFFL
                val dataLength = buffer.short.toInt() and 0xFFFF
                Log.d(TAG, "    📊 [PARSER] Record details: class=$qclass, TTL=${ttl}s, dataLength=$dataLength bytes")
                
                // Validate data length
                if (dataLength > 65535 || dataLength < 0) {
                    Log.w(TAG, "❌ [PARSER] Invalid data length: $dataLength for record type $type")
                    break
                }

                // Track minimum TTL
                if (minTtl == null || ttl < minTtl) {
                    minTtl = ttl
                }

                // Parse A record (type 1)
                if (type == 1 && qclass == 1 && dataLength == 4) {
                    Log.d(TAG, "    🔍 [PARSER] Parsing A record (IPv4) at position ${buffer.position()}")
                    if (buffer.remaining() >= 4) {
                        val ipBytes = ByteArray(4)
                        buffer.get(ipBytes)
                        val ipHex = ipBytes.joinToString(" ") { "%02x".format(it) }
                        Log.d(TAG, "    📥 [PARSER] A record data: $ipHex")
                        try {
                            val address = InetAddress.getByAddress(ipBytes)
                            addresses.add(address)
                            Log.d(TAG, "    ✅ [PARSER] Parsed A record: ${address.hostAddress} for $hostname")
                        } catch (e: Exception) {
                            Log.w(TAG, "    ❌ [PARSER] Invalid IP address in DNS response: ${e.message}")
                        }
                    } else {
                        Log.w(TAG, "    ❌ [PARSER] Not enough bytes for A record data: need 4, have ${buffer.remaining()}")
                    }
                } else if (type == 28 && qclass == 1) {
                    // AAAA record (IPv6) - skip for now
                    Log.d(TAG, "    ⏭️ [PARSER] Skipping AAAA record (IPv6, length=$dataLength)")
                    if (buffer.remaining() >= dataLength) {
                        buffer.position(buffer.position() + dataLength)
                        Log.d(TAG, "    ✓ [PARSER] AAAA record skipped")
                    } else {
                        Log.w(TAG, "    ❌ [PARSER] Not enough bytes to skip AAAA record data: need $dataLength, have ${buffer.remaining()}")
                        break
                    }
                } else {
                    // Skip data for other record types (CNAME, MX, etc.)
                    val typeName = when (type) {
                        2 -> "NS"
                        5 -> "CNAME"
                        15 -> "MX"
                        16 -> "TXT"
                        else -> "UNKNOWN($type)"
                    }
                    Log.d(TAG, "    ⏭️ [PARSER] Skipping record type $typeName (type=$type, class=$qclass, length=$dataLength)")
                    if (dataLength > 0) {
                        if (buffer.remaining() >= dataLength) {
                            buffer.position(buffer.position() + dataLength)
                            Log.d(TAG, "    ✓ [PARSER] Record type $typeName skipped")
                        } else {
                            Log.w(TAG, "    ❌ [PARSER] Not enough bytes to skip data for record type $type: need $dataLength, have ${buffer.remaining()}")
                            if (buffer.remaining() > 0) {
                                buffer.position(buffer.limit())
                            }
                            break
                        }
                    }
                }
            }

            Log.d(TAG, "✅ [PARSER] Parse complete for $hostname: ${addresses.size} addresses found, minTTL=${minTtl ?: "N/A"}s")
            DnsParseResult(addresses, minTtl)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse DNS response for $hostname: ${e.message}", e)
            DnsParseResult(emptyList())
        }
    }
    
    /**
     * Parse DNS response and extract IP addresses (simplified version)
     */
    fun parseResponse(responseData: ByteArray, hostname: String): List<InetAddress> {
        return parseResponseWithTtl(responseData, hostname).addresses
    }
    
    /**
     * Skip DNS name in packet (handles compression pointers)
     * Returns the final buffer position after skipping the name
     */
    private fun skipDnsName(buffer: ByteBuffer): Int {
        val startPos = buffer.position()
        var maxJumps = 10 // Prevent infinite loops
        var jumps = 0
        var encounteredCompression = false
        var compressionPointerPos = startPos
        var compressionEndPos = startPos + 2

        while (buffer.hasRemaining() && jumps < maxJumps) {
            if (buffer.remaining() < 1) {
                break
            }

            val currentPos = buffer.position()
            val length = buffer.get().toInt() and 0xFF

            if (length == 0) {
                // End of name
                if (encounteredCompression) {
                    buffer.position(compressionEndPos)
                    return compressionEndPos
                } else {
                    return buffer.position()
                }
            } else if ((length and 0xC0) == 0xC0) {
                // Compression pointer detected
                if (!encounteredCompression) {
                    compressionPointerPos = currentPos
                    if (buffer.remaining() < 1) {
                        Log.w(TAG, "⚠️ [PARSER] Compression pointer incomplete: missing second byte at position $compressionPointerPos")
                        return startPos
                    }
                    val lowByte = buffer.get().toInt() and 0xFF
                    compressionEndPos = buffer.position()
                    encounteredCompression = true
                    
                    val offset = ((length and 0x3F) shl 8) or lowByte
                    
                    if (offset < 12 || offset >= buffer.limit()) {
                        Log.w(TAG, "⚠️ [PARSER] Invalid compression pointer offset: $offset (limit: ${buffer.limit()}, start: 12) at position $compressionPointerPos")
                        buffer.position(compressionEndPos)
                        return compressionEndPos
                    }
                    
                    buffer.position(offset)
                    jumps++
                    continue
                } else {
                    // Nested compression pointer
                    if (buffer.remaining() < 1) {
                        Log.w(TAG, "⚠️ [PARSER] Nested compression pointer incomplete: missing second byte")
                        buffer.position(compressionEndPos)
                        return compressionEndPos
                    }
                    val lowByte = buffer.get().toInt() and 0xFF
                    val offset = ((length and 0x3F) shl 8) or lowByte
                    
                    if (offset < 12 || offset >= buffer.limit()) {
                        Log.w(TAG, "⚠️ [PARSER] Invalid nested compression pointer offset: $offset")
                        buffer.position(compressionEndPos)
                        return compressionEndPos
                    }
                    
                    buffer.position(offset)
                    jumps++
                    continue
                }
            } else if (length > 63) {
                // Invalid length
                Log.w(TAG, "⚠️ [PARSER] Invalid DNS label length: $length (max 63) at position $currentPos")
                if (encounteredCompression) {
                    buffer.position(compressionEndPos)
                    return compressionEndPos
                } else {
                    return buffer.position()
                }
            } else {
                // Normal label - skip label bytes
                if (buffer.remaining() < length) {
                    Log.w(TAG, "⚠️ [PARSER] Not enough bytes for DNS label: need $length, have ${buffer.remaining()} at position $currentPos")
                    if (encounteredCompression) {
                        buffer.position(compressionEndPos)
                        return compressionEndPos
                    } else {
                        break
                    }
                }
                buffer.position(buffer.position() + length)
            }
        }

        if (jumps >= maxJumps) {
            Log.w(TAG, "⚠️ [PARSER] Max compression jumps reached ($maxJumps), stopping name skip at position ${buffer.position()}")
        }

        if (encounteredCompression) {
            buffer.position(compressionEndPos)
            return compressionEndPos
        } else {
            return buffer.position()
        }
    }
}

/**
 * DNS packet compression utilities
 */
object DnsCompression {
    /**
     * Compress DNS query by optimizing domain name encoding
     * Uses DNS name compression to reduce packet size
     */
    fun compressQuery(queryData: ByteArray, hostname: String): ByteArray {
        // For now, return original query (DNS compression is complex)
        // Future optimization: implement DNS name compression
        // This would reduce packet size for repeated domain parts
        return queryData
    }
}

