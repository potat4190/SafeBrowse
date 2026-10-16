package com.safebrowse

import com.safebrowse.gate.Romans6
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class Romans6Test {

    @Test fun `chapter has all twenty three verses`() =
        assertEquals(23, Romans6.verses.size)

    @Test fun `no verse is blank`() =
        assertTrue(Romans6.verses.all { it.isNotBlank() })

    @Test fun `every verse holds the button for at least the floor`() =
        assertTrue(Romans6.verses.all { Romans6.dwellFor(it) >= 3_200L })

    @Test fun `longer verses wait longer`() {
        val shortest = Romans6.verses.minByOrNull { it.length }!!
        val longest = Romans6.verses.maxByOrNull { it.length }!!
        assertTrue(Romans6.dwellFor(longest) > Romans6.dwellFor(shortest))
    }

    // The gate has to cost something. If this drops under two minutes the
    // friction is gone and the whole design stops working.
    @Test fun `whole chapter takes at least two minutes`() =
        assertTrue(Romans6.totalReadingSeconds() >= 120)

    @Test fun `word check always points at a real word in the verse`() {
        repeat(2_000) {
            val check = Romans6.makeWordCheck(Random(it))
            val actual = check.words[check.wordIndex].filter { c -> c.isLetter() }.lowercase()
            assertEquals(actual, check.answer)
            assertTrue(check.answer.length >= 5)
            assertEquals(Romans6.verses[check.verseIndex], check.words.joinToString(" "))
        }
    }
}
