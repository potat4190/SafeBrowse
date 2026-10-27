package com.safebrowse.vpn

import java.nio.ByteBuffer

/**
 * Parses IPv4/IPv6 packets coming off the TUN interface and builds the
 * replies we inject back into it.
 *
 * IPv4 header (>= 20 bytes): byte 0 holds version + IHL (header length in
 * 32-bit words), byte 9 is the protocol (17 = UDP), bytes 12-19 are the source
 * and destination addresses.
 *
 * IPv6 header (40 bytes, RFC 8200): bytes 4-5 are the payload length, byte 6
 * the next header, bytes 8-23 the source and 24-39 the destination. Extension
 * headers are not followed; nothing that sends DNS uses them.
 *
 * The UDP header that follows is 8 bytes: source port, destination port,
 * length, checksum.
 */
object PacketUtils {

    private const val PROTOCOL_ICMP = 1
    private const val PROTOCOL_TCP = 6
    private const val PROTOCOL_UDP = 17
    private const val PROTOCOL_ICMPV6 = 58
    private const val PORT_DNS = 53
    private const val MIN_IP_HEADER = 20
    private const val IPV6_HEADER = 40
    private const val UDP_HEADER_SIZE = 8
    private const val TCP_HEADER_SIZE = 20

    private const val TCP_FIN = 0x01
    private const val TCP_SYN = 0x02
    private const val TCP_RST = 0x04
    private const val TCP_ACK = 0x10

    /** The fields of an IP header we need, whichever version it is. */
    private class IpHeader(
        val version: Int,
        val headerLength: Int,
        val protocol: Int,
        /** Header plus payload, as the header claims, capped at what was read. */
        val totalLength: Int,
        val src: ByteArray,
        val dst: ByteArray
    )

    /**
     * Null for anything but the first (or only) fragment of an IPv4 or IPv6
     * packet: a later fragment does not start with a transport header.
     */
    private fun parseIp(buf: ByteBuffer, length: Int): IpHeader? {
        if (length < MIN_IP_HEADER) return null
        return when ((buf.get(0).toInt() and 0xFF) ushr 4) {
            4 -> {
                val ihl = (buf.get(0).toInt() and 0x0F) * 4
                if (ihl < MIN_IP_HEADER || ihl > length) return null
                if (buf.getShort(6).toInt() and 0x1FFF != 0) return null   // fragment offset
                val total = minOf(buf.getShort(2).toInt() and 0xFFFF, length)
                if (total < ihl) return null
                IpHeader(4, ihl, buf.get(9).toInt() and 0xFF, total,
                    bytes(buf, 12, 4), bytes(buf, 16, 4))
            }
            6 -> {
                if (length < IPV6_HEADER) return null
                val total = minOf(IPV6_HEADER + (buf.getShort(4).toInt() and 0xFFFF), length)
                IpHeader(6, IPV6_HEADER, buf.get(6).toInt() and 0xFF, total,
                    bytes(buf, 8, 16), bytes(buf, 24, 16))
            }
            else -> null
        }
    }

    private fun bytes(buf: ByteBuffer, at: Int, count: Int) =
        ByteArray(count).also { for (i in 0 until count) it[i] = buf.get(at + i) }

    /** Returns the DNS payload if this packet is a UDP query to port 53. */
    fun extractDnsPayload(buf: ByteBuffer, length: Int): DnsPayload? {
        val ip = parseIp(buf, length) ?: return null
        if (ip.protocol != PROTOCOL_UDP) return null
        // MF set too: the UDP header is here, but the payload is not all here.
        if (ip.version == 4 && buf.getShort(6).toInt() and 0x2000 != 0) return null
        val udp = ip.headerLength
        if (udp + UDP_HEADER_SIZE > length) return null

        val srcPort = buf.getShort(udp).toInt() and 0xFFFF
        val dstPort = buf.getShort(udp + 2).toInt() and 0xFFFF
        if (dstPort != PORT_DNS) return null

        val udpLength = buf.getShort(udp + 4).toInt() and 0xFFFF
        val payloadLength = udpLength - UDP_HEADER_SIZE
        if (payloadLength <= 0 || udp + udpLength > length) return null

        val dnsBytes = bytes(buf, udp + UDP_HEADER_SIZE, payloadLength)
        return DnsPayload(dnsBytes, ip.src, ip.dst, srcPort, ip.headerLength)
    }

    /**
     * Wrap a DNS response in a fresh IPv4 or IPv6 UDP packet (matching the
     * query) addressed back to whoever asked. Source and destination are
     * swapped relative to the query.
     */
    fun buildResponsePacket(dnsResponse: ByteArray, original: DnsPayload): ByteArray {
        val udp = ByteBuffer.allocate(UDP_HEADER_SIZE + dnsResponse.size)
        udp.putShort(PORT_DNS.toShort())
        udp.putShort(original.srcPort.toShort())
        udp.putShort(udp.capacity().toShort())
        udp.putShort(0)
        udp.put(dnsResponse)
        val segment = udp.array()

        // The UDP checksum is optional over IPv4, so it stays zero there.
        // IPv6 requires it (RFC 8200 §8.1).
        if (original.isIpv6) {
            var sum = transportChecksum(original.dstIp, original.srcIp, PROTOCOL_UDP, segment)
            if (sum == 0) sum = 0xFFFF   // zero means "no checksum"; send all-ones instead
            ByteBuffer.wrap(segment).putShort(6, sum.toShort())
        }
        return wrapIp(original.dstIp, original.srcIp, PROTOCOL_UDP, segment)
    }

    /**
     * A refusal for a packet we will not carry, or null if it needs none.
     *
     * The tunnel only answers UDP DNS. Everything else routed into it (DNS
     * over TCP or TLS to our address, DoH or QUIC to a public resolver) used
     * to be dropped without a word, so the app sat out its connect timeout,
     * often several seconds, before trying anything else. Refusing it makes
     * it give up at once: TCP gets a reset, UDP an ICMP port unreachable.
     * It still fails closed; nothing gets past the filter.
     */
    fun buildRejection(packet: ByteArray, length: Int): ByteArray? {
        val buf = ByteBuffer.wrap(packet, 0, length)
        val ip = parseIp(buf, length) ?: return null
        return when (ip.protocol) {
            PROTOCOL_TCP -> buildTcpReset(buf, ip)
            PROTOCOL_UDP -> buildPortUnreachable(packet, ip)
            else -> null   // ICMP and the rest: nothing waits on a reply to those
        }
    }

    private fun buildTcpReset(buf: ByteBuffer, ip: IpHeader): ByteArray? {
        val tcp = ip.headerLength
        if (tcp + TCP_HEADER_SIZE > ip.totalLength) return null
        val srcPort = buf.getShort(tcp).toInt() and 0xFFFF
        val dstPort = buf.getShort(tcp + 2).toInt() and 0xFFFF
        val seq = buf.getInt(tcp + 4).toLong() and 0xFFFFFFFFL
        val ack = buf.getInt(tcp + 8)
        val dataOffset = ((buf.get(tcp + 12).toInt() and 0xF0) ushr 4) * 4
        val flags = buf.get(tcp + 13).toInt() and 0xFF
        if (flags and TCP_RST != 0) return null   // never answer a reset

        // RFC 9293 §3.10.7.1: if the segment carried an ACK, the reset takes
        // its sequence number from it; otherwise the reset acknowledges the
        // whole segment (SYN and FIN each count as one).
        val reply = ByteBuffer.allocate(TCP_HEADER_SIZE)
        reply.putShort(dstPort.toShort())
        reply.putShort(srcPort.toShort())
        if (flags and TCP_ACK != 0) {
            reply.putInt(ack)
            reply.putInt(0)
            reply.put(((TCP_HEADER_SIZE / 4) shl 4).toByte())
            reply.put(TCP_RST.toByte())
        } else {
            val dataLength = (ip.totalLength - tcp - dataOffset).coerceAtLeast(0)
            var consumed = seq + dataLength
            if (flags and TCP_SYN != 0) consumed++
            if (flags and TCP_FIN != 0) consumed++
            reply.putInt(0)
            reply.putInt(consumed.toInt())
            reply.put(((TCP_HEADER_SIZE / 4) shl 4).toByte())
            reply.put((TCP_RST or TCP_ACK).toByte())
        }
        reply.putShort(0)   // window
        reply.putShort(0)   // checksum, filled in below
        reply.putShort(0)   // urgent pointer
        val segment = reply.array()
        ByteBuffer.wrap(segment).putShort(16,
            transportChecksum(ip.dst, ip.src, PROTOCOL_TCP, segment).toShort())
        return wrapIp(ip.dst, ip.src, PROTOCOL_TCP, segment)
    }

    /**
     * ICMP port unreachable (v4 type 3 code 3, v6 type 1 code 4). It quotes
     * the offending packet's header and the start of its payload, which is
     * how the kernel finds the socket to fail with ECONNREFUSED.
     */
    private fun buildPortUnreachable(packet: ByteArray, ip: IpHeader): ByteArray {
        // v4 quotes header + 8 bytes (RFC 792). v6 may quote more, but must
        // stay under the 1280-byte minimum MTU; the same short quote will do.
        val quoted = packet.copyOf(minOf(ip.totalLength, ip.headerLength + UDP_HEADER_SIZE))
        val message = ByteBuffer.allocate(8 + quoted.size)
        if (ip.version == 4) message.put(3).put(3) else message.put(1).put(4)
        message.putShort(0)   // checksum, filled in below
        message.putInt(0)     // unused
        message.put(quoted)
        val icmp = message.array()

        val protocol = if (ip.version == 4) PROTOCOL_ICMP else PROTOCOL_ICMPV6
        // ICMPv4 sums the message alone; ICMPv6 adds the pseudo-header.
        val sum = if (ip.version == 4) finish(sum(icmp, 0, icmp.size, 0))
                  else transportChecksum(ip.dst, ip.src, PROTOCOL_ICMPV6, icmp)
        ByteBuffer.wrap(icmp).putShort(2, sum.toShort())
        return wrapIp(ip.dst, ip.src, protocol, icmp)
    }

    /** Prefix [payload] with an IPv4 or IPv6 header, by the address length. */
    private fun wrapIp(src: ByteArray, dst: ByteArray, protocol: Int, payload: ByteArray): ByteArray =
        if (src.size == 16) {
            ByteBuffer.allocate(IPV6_HEADER + payload.size)
                .putInt(0x60000000)                         // version 6, no class or flow label
                .putShort(payload.size.toShort())
                .put(protocol.toByte())
                .put(64)                                    // hop limit
                .put(src).put(dst)
                .put(payload)
                .array()
        } else {
            val total = MIN_IP_HEADER + payload.size
            val buf = ByteBuffer.allocate(total)
            buf.put(0x45.toByte())                 // version 4, IHL 5 (20 bytes)
            buf.put(0)                             // DSCP + ECN
            buf.putShort(total.toShort())
            buf.putShort(0)                        // identification
            buf.putShort(0x4000.toShort())         // don't fragment
            buf.put(64)                            // TTL
            buf.put(protocol.toByte())
            buf.putShort(0)                        // checksum, filled in below
            buf.put(src)
            buf.put(dst)
            buf.putShort(10, finish(sum(buf.array(), 0, MIN_IP_HEADER, 0)).toShort())
            buf.put(payload)
            buf.array()
        }

    /** TCP/UDP/ICMPv6 checksum: the segment plus the pseudo-header (RFC 793, RFC 8200 §8.1). */
    private fun transportChecksum(src: ByteArray, dst: ByteArray, protocol: Int, segment: ByteArray): Int {
        var s = sum(src, 0, src.size, 0)
        s = sum(dst, 0, dst.size, s)
        s += protocol
        s += segment.size and 0xFFFF
        s += segment.size ushr 16
        return finish(sum(segment, 0, segment.size, s))
    }

    /** One's-complement running sum of 16-bit words, folded as it goes. */
    private fun sum(data: ByteArray, offset: Int, count: Int, initial: Int): Int {
        var s = initial
        var i = offset
        while (i < offset + count - 1) {
            s += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (count % 2 != 0) s += (data[offset + count - 1].toInt() and 0xFF) shl 8
        while (s ushr 16 != 0) s = (s and 0xFFFF) + (s ushr 16)
        return s
    }

    private fun finish(sum: Int): Int = sum.inv() and 0xFFFF
}

data class DnsPayload(
    val dnsBytes: ByteArray,
    /** 4 bytes for IPv4, 16 for IPv6. */
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val ipHeaderLength: Int
) {
    val isIpv6: Boolean get() = srcIp.size == 16

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DnsPayload) return false
        return srcPort == other.srcPort && dnsBytes.contentEquals(other.dnsBytes)
    }
    override fun hashCode(): Int = 31 * srcPort + dnsBytes.contentHashCode()
}
