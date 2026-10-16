package com.safebrowse.dns

import java.nio.ByteBuffer

/**
 * Minimal DNS query parser (RFC 1035).
 *
 * Header, 12 bytes:
 *   0-1 transaction ID | 2-3 flags | 4-5 QDCOUNT
 *   6-7 ANCOUNT | 8-9 NSCOUNT | 10-11 ARCOUNT
 *
 * Then the question section: QNAME as length-prefixed labels terminated by a
 * zero byte, followed by QTYPE and QCLASS (2 bytes each).
 */
object DnsParser {

    private const val DNS_HEADER_SIZE = 12
    private const val FLAG_QR_MASK = 0x8000      // set = this is a response
    private const val FLAG_OPCODE_MASK = 0x7800

    fun parse(data: ByteArray, length: Int): DnsQuery? {
        if (length < DNS_HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(data, 0, length)

        val transactionId = buf.short.toInt() and 0xFFFF
        val flags = buf.short.toInt() and 0xFFFF
        val qdCount = buf.short.toInt() and 0xFFFF

        // Standard queries only: responses and non-standard opcodes are ignored.
        if (flags and FLAG_QR_MASK != 0) return null
        if (flags and FLAG_OPCODE_MASK != 0) return null
        if (qdCount == 0) return null

        buf.short; buf.short; buf.short   // skip AN/NS/AR counts

        val domain = parseDomainName(buf) ?: return null
        if (buf.remaining() < 4) return null

        val qType = buf.short.toInt() and 0xFFFF
        val qClass = buf.short.toInt() and 0xFFFF

        return DnsQuery(transactionId, domain, qType, qClass, data.copyOf(length), buf.position())
    }

    /**
     * Decode \x03www\x07example\x03com\x00 into "www.example.com".
     * Returns null on anything malformed rather than throwing.
     */
    fun parseDomainName(buf: ByteBuffer): String? {
        val labels = StringBuilder()
        var labelCount = 0

        while (buf.hasRemaining()) {
            val length = buf.get().toInt() and 0xFF
            when {
                length == 0 ->
                    return if (labels.isEmpty()) null else labels.dropLast(1).toString()

                // Compression pointers should not appear in a question, but
                // guard anyway so a malformed packet cannot run us off the end.
                length and 0xC0 == 0xC0 -> {
                    if (!buf.hasRemaining()) return null
                    buf.get()
                    return if (labels.isEmpty()) null else labels.dropLast(1).toString()
                }

                length > 63 -> return null   // max label length per RFC 1035

                else -> {
                    if (buf.remaining() < length) return null
                    if (labelCount++ > 127) return null
                    val bytes = ByteArray(length)
                    buf.get(bytes)
                    labels.append(bytes.toString(Charsets.US_ASCII).lowercase()).append('.')
                }
            }
        }
        return if (labels.isEmpty()) null else labels.dropLast(1).toString()
    }
}

data class DnsQuery(
    val transactionId: Int,
    val domain: String,
    val qType: Int,
    val qClass: Int,
    val rawPacket: ByteArray,
    /** Offset just past the first question's QCLASS in [rawPacket]. */
    val questionEnd: Int
) {
    val qTypeName: String get() = when (qType) {
        1 -> "A"; 28 -> "AAAA"; 5 -> "CNAME"; 15 -> "MX"
        2 -> "NS"; 6 -> "SOA"; 16 -> "TXT"; 33 -> "SRV"; 255 -> "ANY"
        else -> "TYPE$qType"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DnsQuery) return false
        return transactionId == other.transactionId && domain == other.domain
    }
    override fun hashCode(): Int = 31 * transactionId + domain.hashCode()
}
