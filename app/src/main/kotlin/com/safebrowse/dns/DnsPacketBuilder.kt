package com.safebrowse.dns

import java.nio.ByteBuffer

/** Builds the DNS responses we synthesize ourselves. */
object DnsPacketBuilder {

    private const val DNS_HEADER_LENGTH = 12

    // QR=1, RD=1, RA=1, plus an RCODE in the low four bits.
    private const val FLAGS_RESPONSE = 0x8180
    private const val RCODE_FORMERR = 1
    private const val RCODE_SERVFAIL = 2
    private const val RCODE_NXDOMAIN = 3

    /**
     * NXDOMAIN response. The browser shows "server not found" rather than
     * hanging on a connection to a dead address, so blocking feels instant.
     *
     * The original question section is echoed back, as RFC 1035 requires.
     */
    fun buildNxDomain(query: DnsQuery): ByteArray = buildError(query, RCODE_NXDOMAIN)

    /**
     * SERVFAIL, for an allowed query no resolver answered. Without a reply the
     * asking app waits out its whole timeout-and-retry cycle, which looks
     * like a page that loads forever; SERVFAIL ends the wait at once.
     */
    fun buildServFail(query: DnsQuery): ByteArray = buildError(query, RCODE_SERVFAIL)

    /**
     * FORMERR for a query we could not parse, so it is answered rather than
     * dropped. It is not forwarded either: a name we can't read is a name we
     * can't check against the blocklist. Header only, since there is no
     * question we trust to echo.
     */
    fun buildFormErr(rawQuery: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(DNS_HEADER_LENGTH)
        buf.put(rawQuery[0]).put(rawQuery[1])   // transaction ID
        buf.putShort((FLAGS_RESPONSE or RCODE_FORMERR).toShort())
        return buf.array()                      // all counts zero
    }

    private fun buildError(query: DnsQuery, rcode: Int): ByteArray {
        val buf = ByteBuffer.allocate(DNS_HEADER_LENGTH)
        buf.putShort(query.transactionId.toShort())
        buf.putShort((FLAGS_RESPONSE or rcode).toShort())
        buf.putShort(1)   // QDCOUNT — the echoed question
        buf.putShort(0)   // ANCOUNT
        buf.putShort(0)   // NSCOUNT
        buf.putShort(0)   // ARCOUNT
        return buf.array() + questionSection(query)
    }

    // Only the question: the query's EDNS OPT record sits after it, and
    // echoing that with ARCOUNT=0 leaves trailing bytes strict parsers reject.
    private fun questionSection(query: DnsQuery): ByteArray =
        if (query.questionEnd <= DNS_HEADER_LENGTH) ByteArray(0)
        else query.rawPacket.copyOfRange(DNS_HEADER_LENGTH, query.questionEnd)
}
