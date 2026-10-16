package com.safebrowse

import com.safebrowse.util.DomainMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DomainMatcherTest {

    private lateinit var matcher: DomainMatcher

    @Before fun setUp() {
        matcher = DomainMatcher()
        matcher.load(listOf("example.com", "blocked.org"))
    }

    @Test fun `exact domain is blocked`() = assertTrue(matcher.isBlocked("example.com"))

    @Test fun `uppercase is normalised`() = assertTrue(matcher.isBlocked("EXAMPLE.COM"))

    @Test fun `www subdomain is blocked`() = assertTrue(matcher.isBlocked("www.example.com"))

    @Test fun `deep subdomain is blocked`() = assertTrue(matcher.isBlocked("a.b.c.example.com"))

    @Test fun `trailing dot from the wire is handled`() =
        assertTrue(matcher.isBlocked("example.com."))

    @Test fun `unrelated domain is allowed`() = assertFalse(matcher.isBlocked("google.com"))

    // The important negative case: suffix matching must not become substring
    // matching, or "notexample.com" would be caught too.
    @Test fun `domain merely ending in the blocked string is allowed`() =
        assertFalse(matcher.isBlocked("notexample.com"))

    @Test fun `bare tld is never blocked`() = assertFalse(matcher.isBlocked("com"))

    @Test fun `empty string is allowed`() = assertFalse(matcher.isBlocked(""))

    @Test fun `load replaces rather than merges`() {
        matcher.load(listOf("other.com"))
        assertFalse(matcher.isBlocked("example.com"))
        assertTrue(matcher.isBlocked("other.com"))
    }

    @Test fun `concurrent reads and writes do not throw`() {
        val threads = (1..24).map { i ->
            Thread {
                repeat(200) {
                    if (i % 2 == 0) matcher.isBlocked("a.b.example.com")
                    else matcher.load(listOf("example.com", "x$i.com"))
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }
}
