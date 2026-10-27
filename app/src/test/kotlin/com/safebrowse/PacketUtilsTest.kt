package com.safebrowse

import com.safebrowse.dns.DnsPacketBuilder
import com.safebrowse.dns.DnsParser
import com.safebrowse.vpn.DnsPayload
import com.safebrowse.vpn.PacketUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The packet loop cannot be exercised without a device, but its byte-level
 * parts can: IPv4 offsets (RFC 791), UDP offsets (RFC 768), the header
 * checksum, and the NXDOMAIN we write back.
 */
class PacketUtilsTest {

    private val appIp = byteArrayOf(10, 0, 0, 2)
    private val dnsIp = byteArrayOf(10, 0, 0, 1)

    /** "www.example.com" A IN, RD set, optionally with an EDNS0 OPT record. */
    private fun dnsQuery(id: Int = 0x1234, edns: Boolean = false): ByteArray {
        val qname = byteArrayOf(3, 'w'.code.toByte(), 'w'.code.toByte(), 'w'.code.toByte(),
            7) + "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0)
        val opt = byteArrayOf(0, 0, 41, 0x10, 0, 0, 0, 0, 0, 0, 0)   // root, OPT, 4096, no rdata
        val buf = ByteBuffer.allocate(12 + qname.size + 4 + if (edns) opt.size else 0)
        buf.putShort(id.toShort()).putShort(0x0100).putShort(1).putShort(0).putShort(0)
            .putShort(if (edns) 1 else 0)
        buf.put(qname).putShort(1).putShort(1)
        if (edns) buf.put(opt)
        return buf.array()
    }

    private fun ipv4Udp(
        payload: ByteArray,
        dstPort: Int = 53,
        protocol: Int = 17,
        flagsFrag: Int = 0x4000,
        version: Int = 4
    ): ByteArray {
        val buf = ByteBuffer.allocate(28 + payload.size)
        buf.put(((version shl 4) or 5).toByte()).put(0)
        buf.putShort((28 + payload.size).toShort()).putShort(0x0abc)
        buf.putShort(flagsFrag.toShort()).put(64).put(protocol.toByte()).putShort(0)
        buf.put(appIp).put(dnsIp)
        buf.putShort(40000.toShort()).putShort(dstPort.toShort())
        buf.putShort((8 + payload.size).toShort()).putShort(0)
        buf.put(payload)
        return buf.array()
    }

    private fun extract(packet: ByteArray): DnsPayload? =
        PacketUtils.extractDnsPayload(ByteBuffer.wrap(packet), packet.size)

    /** One's-complement sum of the 20-byte header; a valid header sums to 0xFFFF. */
    private fun headerSum(p: ByteArray): Int {
        var sum = 0
        for (i in 0 until 20 step 2) sum += ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum
    }

    private fun u16(p: ByteArray, at: Int) = ((p[at].toInt() and 0xFF) shl 8) or (p[at + 1].toInt() and 0xFF)

    @Test fun `udp query to port 53 is extracted with the right offsets`() {
        val query = dnsQuery()
        val payload = extract(ipv4Udp(query))
        assertNotNull(payload)
        payload!!
        assertArrayEquals(query, payload.dnsBytes)
        assertArrayEquals(appIp, payload.srcIp)
        assertArrayEquals(dnsIp, payload.dstIp)
        assertEquals(40000, payload.srcPort)
        assertEquals(20, payload.ipHeaderLength)
    }

    @Test fun `non dns traffic is ignored`() {
        assertNull(extract(ipv4Udp(dnsQuery(), dstPort = 443)))
        assertNull(extract(ipv4Udp(dnsQuery(), protocol = 6)))    // TCP
        assertNull(extract(ipv4Udp(dnsQuery(), version = 6)))
        assertNull(extract(ByteArray(10)))
    }

    @Test fun `ip fragments are ignored`() {
        assertNull(extract(ipv4Udp(dnsQuery(), flagsFrag = 0x2000)))   // MF
        assertNull(extract(ipv4Udp(dnsQuery(), flagsFrag = 0x0010)))   // offset 16
    }

    @Test fun `response header checksum matches a known vector`() {
        // The textbook header 4500 0073 0000 4000 4011 xxxx c0a8 0001 c0a8 00c7
        // has checksum 0xB861. 0x73 total length = 20 IP + 8 UDP + 87 payload.
        val original = DnsPayload(
            ByteArray(0),
            srcIp = byteArrayOf(192.toByte(), 168.toByte(), 0, 199.toByte()),
            dstIp = byteArrayOf(192.toByte(), 168.toByte(), 0, 1),
            srcPort = 40000, ipHeaderLength = 20
        )
        val packet = PacketUtils.buildResponsePacket(ByteArray(87), original)
        assertEquals(0xB861, u16(packet, 10))
        assertEquals(0xFFFF, headerSum(packet))
    }

    @Test fun `response is addressed back to the asker`() {
        val payload = extract(ipv4Udp(dnsQuery()))!!
        val answer = ByteArray(45) { it.toByte() }
        val p = PacketUtils.buildResponsePacket(answer, payload)

        assertEquals(0x45, p[0].toInt() and 0xFF)
        assertEquals(p.size, u16(p, 2))                   // total length
        assertEquals(17, p[9].toInt())                    // UDP
        assertArrayEquals(dnsIp, p.copyOfRange(12, 16))   // from the resolver address
        assertArrayEquals(appIp, p.copyOfRange(16, 20))   // back to the app
        assertEquals(0xFFFF, headerSum(p))
        assertEquals(53, u16(p, 20))
        assertEquals(40000, u16(p, 22))
        assertEquals(8 + answer.size, u16(p, 24))         // UDP length
        assertEquals(0, u16(p, 26))                       // no UDP checksum: allowed over IPv4
        assertArrayEquals(answer, p.copyOfRange(28, p.size))
    }

    @Test fun `nxdomain echoes only the question even when the query carries EDNS`() {
        val raw = dnsQuery(id = 0xBEEF, edns = true)
        val query = DnsParser.parse(raw, raw.size)!!
        assertEquals("www.example.com", query.domain)

        val nx = DnsPacketBuilder.buildNxDomain(query)
        val questionLength = raw.size - 12 - 11           // minus header, minus OPT
        assertEquals(12 + questionLength, nx.size)
        assertEquals(0xBEEF, u16(nx, 0))
        assertEquals(0x8183, u16(nx, 2))
        assertEquals(1, u16(nx, 4))                       // QDCOUNT
        assertEquals(0, u16(nx, 10))                      // ARCOUNT
        assertArrayEquals(raw.copyOfRange(12, 12 + questionLength), nx.copyOfRange(12, nx.size))
    }

    // ── IPv6 and refusals ────────────────────────────────────────────────

    private val appIp6 = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 2 }
    private val dnsIp6 = byteArrayOf(0x20, 0x01, 0x48, 0x60, 0x48, 0x60, 0, 0, 0, 0, 0, 0, 0, 0, 0x88.toByte(), 0x88.toByte())

    private fun ipv6(nextHeader: Int, transport: ByteArray): ByteArray =
        ByteBuffer.allocate(40 + transport.size)
            .putInt(0x60000000).putShort(transport.size.toShort()).put(nextHeader.toByte()).put(64)
            .put(appIp6).put(dnsIp6).put(transport).array()

    private fun udp(payload: ByteArray, dstPort: Int = 53) =
        ByteBuffer.allocate(8 + payload.size).putShort(40000.toShort()).putShort(dstPort.toShort())
            .putShort((8 + payload.size).toShort()).putShort(0).put(payload).array()

    private fun tcp(flags: Int, seq: Int = 1000, ack: Int = 0, data: Int = 0) =
        ByteBuffer.allocate(20 + data).putShort(40000.toShort()).putShort(443.toShort())
            .putInt(seq).putInt(ack).put(0x50).put(flags.toByte()).putShort(65535.toShort())
            .putShort(0).putShort(0).array()

    private fun ipv4(protocol: Int, transport: ByteArray): ByteArray {
        val p = ipv4Udp(ByteArray(transport.size - 8), protocol = protocol)
        transport.copyInto(p, 20)
        return p
    }

    /** One's-complement sum of the transport segment plus pseudo-header; valid sums to 0xFFFF. */
    private fun transportSum(p: ByteArray): Int {
        val v6 = (p[0].toInt() and 0xF0) == 0x60
        val hdr = if (v6) 40 else 20
        val addrAt = if (v6) 8 else 12
        val addrLen = if (v6) 32 else 8
        val proto = p[if (v6) 6 else 9].toInt() and 0xFF
        val seg = p.size - hdr
        var sum = proto + seg
        for (i in addrAt until addrAt + addrLen step 2) sum += u16(p, i)
        var i = hdr
        while (i < p.size - 1) { sum += u16(p, i); i += 2 }
        if ((p.size - hdr) % 2 != 0) sum += (p[p.size - 1].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum
    }

    @Test fun `ipv6 dns query is extracted and answered over ipv6 with a valid checksum`() {
        val query = dnsQuery()
        val payload = extract(ipv6(17, udp(query)))!!
        assertArrayEquals(query, payload.dnsBytes)
        assertArrayEquals(appIp6, payload.srcIp)
        assertEquals(40, payload.ipHeaderLength)

        val answer = ByteArray(33) { it.toByte() }
        val p = PacketUtils.buildResponsePacket(answer, payload)
        assertEquals(6, (p[0].toInt() and 0xFF) ushr 4)
        assertEquals(8 + answer.size, u16(p, 4))          // payload length
        assertEquals(17, p[6].toInt())
        assertArrayEquals(dnsIp6, p.copyOfRange(8, 24))
        assertArrayEquals(appIp6, p.copyOfRange(24, 40))
        assertEquals(53, u16(p, 40))
        assertEquals(40000, u16(p, 42))
        assertEquals(0xFFFF, transportSum(p))
    }

    @Test fun `ipv6 non dns traffic is not mistaken for a query`() {
        assertNull(extract(ipv6(17, udp(dnsQuery(), dstPort = 443))))
        assertNull(extract(ipv6(6, tcp(0x02))))
    }

    @Test fun `tcp syn is refused with a reset that acknowledges it`() {
        for (packet in listOf(ipv4(6, tcp(0x02, seq = 1000)), ipv6(6, tcp(0x02, seq = 1000)))) {
            val r = PacketUtils.buildRejection(packet, packet.size)!!
            val t = if ((r[0].toInt() and 0xF0) == 0x60) 40 else 20
            assertEquals(443, u16(r, t))
            assertEquals(40000, u16(r, t + 2))
            assertEquals(0, ByteBuffer.wrap(r).getInt(t + 4))
            assertEquals(1001, ByteBuffer.wrap(r).getInt(t + 8))    // SYN counts as one
            assertEquals(0x14, r[t + 13].toInt())                   // RST|ACK
            assertEquals(0xFFFF, transportSum(r))
            if (t == 20) assertEquals(0xFFFF, headerSum(r))
        }
    }

    @Test fun `tcp segment with ack is reset from its ack number`() {
        val packet = ipv4(6, tcp(0x18, seq = 5, ack = 777, data = 10))
        val r = PacketUtils.buildRejection(packet, packet.size)!!
        assertEquals(777, ByteBuffer.wrap(r).getInt(24))
        assertEquals(0x04, r[33].toInt())
        assertEquals(0xFFFF, transportSum(r))
    }

    @Test fun `a reset is never answered`() {
        val packet = ipv4(6, tcp(0x04))
        assertNull(PacketUtils.buildRejection(packet, packet.size))
    }

    @Test fun `udp that is not dns gets icmp port unreachable quoting the packet`() {
        val v4 = ipv4Udp(ByteArray(30), dstPort = 443)
        val r4 = PacketUtils.buildRejection(v4, v4.size)!!
        assertEquals(1, r4[9].toInt())                               // ICMP
        assertArrayEquals(appIp, r4.copyOfRange(16, 20))
        assertEquals(3, r4[20].toInt()); assertEquals(3, r4[21].toInt())
        assertArrayEquals(v4.copyOf(28), r4.copyOfRange(28, r4.size))
        assertEquals(0xFFFF, headerSum(r4))
        var sum = 0
        for (i in 20 until r4.size step 2) sum += u16(r4, i)
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        assertEquals(0xFFFF, sum)

        val v6 = ipv6(17, udp(ByteArray(30), dstPort = 443))
        val r6 = PacketUtils.buildRejection(v6, v6.size)!!
        assertEquals(58, r6[6].toInt())                              // ICMPv6
        assertEquals(1, r6[40].toInt()); assertEquals(4, r6[41].toInt())
        assertEquals(0xFFFF, transportSum(r6))
    }
}
