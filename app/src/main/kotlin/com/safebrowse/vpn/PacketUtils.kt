package com.safebrowse.vpn

import java.nio.ByteBuffer

/**
 * Parses IPv4/UDP packets coming off the TUN interface and builds the replies
 * we inject back into it.
 *
 * IPv4 header (>= 20 bytes): byte 0 holds version + IHL (header length in
 * 32-bit words), byte 9 is the protocol (17 = UDP), bytes 12-19 are the source
 * and destination addresses. The UDP header that follows is 8 bytes: source
 * port, destination port, length, checksum.
 */
object PacketUtils {

    private const val PROTOCOL_UDP: Byte = 17
    private const val PORT_DNS = 53
    private const val MIN_IP_HEADER = 20
    private const val UDP_HEADER_SIZE = 8

    /** Returns the DNS payload if this packet is a UDP query to port 53. */
    fun extractDnsPayload(buf: ByteBuffer, length: Int): DnsPayload? {
        if (length < MIN_IP_HEADER + UDP_HEADER_SIZE) return null
        buf.position(0)

        val versionIhl = buf.get().toInt() and 0xFF
        if (versionIhl ushr 4 != 4) return null            // IPv4 only for now

        val ihl = (versionIhl and 0x0F) * 4
        if (ihl < MIN_IP_HEADER || ihl + UDP_HEADER_SIZE > length) return null

        // A fragment (MF set or non-zero offset) does not start with a UDP
        // header at [ihl], so reading one as a query would parse garbage.
        buf.position(6)
        if (buf.short.toInt() and 0x3FFF != 0) return null

        buf.position(9)
        if (buf.get() != PROTOCOL_UDP) return null

        buf.position(buf.position() + 2)                   // skip header checksum
        val srcIp = ByteArray(4).also { buf.get(it) }
        val dstIp = ByteArray(4).also { buf.get(it) }

        buf.position(ihl)
        val srcPort = buf.short.toInt() and 0xFFFF
        val dstPort = buf.short.toInt() and 0xFFFF
        if (dstPort != PORT_DNS) return null

        val udpLength = buf.short.toInt() and 0xFFFF
        buf.short                                          // skip UDP checksum

        val payloadLength = udpLength - UDP_HEADER_SIZE
        if (payloadLength <= 0 || buf.remaining() < payloadLength) return null

        val dnsBytes = ByteArray(payloadLength).also { buf.get(it) }
        return DnsPayload(dnsBytes, srcIp, dstIp, srcPort, ihl)
    }

    /**
     * Wrap a DNS response in a fresh IPv4/UDP packet addressed back to whoever
     * asked. Source and destination are swapped relative to the query.
     */
    fun buildResponsePacket(dnsResponse: ByteArray, original: DnsPayload): ByteArray {
        val udpLength = UDP_HEADER_SIZE + dnsResponse.size
        val ipLength = MIN_IP_HEADER + udpLength
        val buf = ByteBuffer.allocate(ipLength)

        // IPv4 header
        buf.put(0x45.toByte())                 // version 4, IHL 5 (20 bytes)
        buf.put(0)                             // DSCP + ECN
        buf.putShort(ipLength.toShort())
        buf.putShort(0)                        // identification
        buf.putShort(0x4000.toShort())         // don't fragment
        buf.put(64)                            // TTL
        buf.put(PROTOCOL_UDP)
        buf.putShort(0)                        // checksum, filled in below
        buf.put(original.dstIp)                // from the resolver...
        buf.put(original.srcIp)                // ...back to the app

        buf.putShort(10, checksum(buf.array(), 0, MIN_IP_HEADER).toShort())

        // UDP header. The checksum is optional over IPv4, so we leave it zero.
        buf.putShort(PORT_DNS.toShort())
        buf.putShort(original.srcPort.toShort())
        buf.putShort(udpLength.toShort())
        buf.putShort(0)

        buf.put(dnsResponse)
        return buf.array()
    }

    /** Standard one's-complement Internet checksum. */
    private fun checksum(data: ByteArray, offset: Int, count: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + count - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (count % 2 != 0) sum += (data[offset + count - 1].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }
}

data class DnsPayload(
    val dnsBytes: ByteArray,
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val ipHeaderLength: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DnsPayload) return false
        return srcPort == other.srcPort && dnsBytes.contentEquals(other.dnsBytes)
    }
    override fun hashCode(): Int = 31 * srcPort + dnsBytes.contentHashCode()
}
