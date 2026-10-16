package com.safebrowse

import com.safebrowse.util.DomainNormalizer.normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainNormalizerTest {

    @Test fun `bare domain passes through`() = assertEquals("example.com", normalize("example.com"))

    @Test fun `uppercase is lowered`() = assertEquals("example.com", normalize("EXAMPLE.COM"))

    @Test fun `full url is reduced to the host`() =
        assertEquals("example.com", normalize("https://www.Example.COM/path?q=1#x"))

    @Test fun `www prefix is stripped so it is one entry`() =
        assertEquals("example.com", normalize("www.example.com"))

    @Test fun `other subdomains are preserved`() =
        assertEquals("sub.example.com", normalize("sub.example.com"))

    @Test fun `surrounding whitespace is trimmed`() =
        assertEquals("example.com", normalize("  example.com  "))

    @Test fun `multi part tld is valid`() =
        assertEquals("example.co.uk", normalize("example.co.uk"))

    @Test fun `port is dropped`() = assertEquals("example.com", normalize("example.com:8080"))

    @Test fun `blank input is rejected`() = assertNull(normalize("   "))

    @Test fun `single label is rejected`() = assertNull(normalize("localhost"))

    @Test fun `ip address is rejected`() = assertNull(normalize("192.168.1.1"))

    @Test fun `garbage is rejected`() = assertNull(normalize("not a domain!!"))

    @Test fun `leading hyphen is rejected`() = assertNull(normalize("-example.com"))
}
