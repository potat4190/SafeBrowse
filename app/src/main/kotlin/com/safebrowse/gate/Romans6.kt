package com.safebrowse.gate

import kotlin.math.max
import kotlin.random.Random

/**
 * Romans 6, King James Version (public domain).
 *
 * Kept byte-identical to src/shared/romans6.js in the Windows app so the two
 * platforms present the same chapter and the same gate.
 */
object Romans6 {

    val verses: List<String> = listOf(
        "What shall we say then? Shall we continue in sin, that grace may abound?",
        "God forbid. How shall we, that are dead to sin, live any longer therein?",
        "Know ye not, that so many of us as were baptized into Jesus Christ were baptized into his death?",
        "Therefore we are buried with him by baptism into death: that like as Christ was raised up from the dead by the glory of the Father, even so we also should walk in newness of life.",
        "For if we have been planted together in the likeness of his death, we shall be also in the likeness of his resurrection:",
        "Knowing this, that our old man is crucified with him, that the body of sin might be destroyed, that henceforth we should not serve sin.",
        "For he that is dead is freed from sin.",
        "Now if we be dead with Christ, we believe that we shall also live with him:",
        "Knowing that Christ being raised from the dead dieth no more; death hath no more dominion over him.",
        "For in that he died, he died unto sin once: but in that he liveth, he liveth unto God.",
        "Likewise reckon ye also yourselves to be dead indeed unto sin, but alive unto God through Jesus Christ our Lord.",
        "Let not sin therefore reign in your mortal body, that ye should obey it in the lusts thereof.",
        "Neither yield ye your members as instruments of unrighteousness unto sin: but yield yourselves unto God, as those that are alive from the dead, and your members as instruments of righteousness unto God.",
        "For sin shall not have dominion over you: for ye are not under the law, but under grace.",
        "What then? shall we sin, because we are not under the law, but under grace? God forbid.",
        "Know ye not, that to whom ye yield yourselves servants to obey, his servants ye are to whom ye obey; whether of sin unto death, or of obedience unto righteousness?",
        "But God be thanked, that ye were the servants of sin, but ye have obeyed from the heart that form of doctrine which was delivered you.",
        "Being then made free from sin, ye became the servants of righteousness.",
        "I speak after the manner of men because of the infirmity of your flesh: for as ye have yielded your members servants to uncleanness and to iniquity unto iniquity; even so now yield your members servants to righteousness unto holiness.",
        "For when ye were the servants of sin, ye were free from righteousness.",
        "What fruit had ye then in those things whereof ye are now ashamed? for the end of those things is death.",
        "But now being made free from sin, and become servants to God, ye have your fruit unto holiness, and the end everlasting life.",
        "For the wages of sin is death; but the gift of God is eternal life through Jesus Christ our Lord."
    )

    /** Assumed reading pace used to hold the Continue button shut. */
    private const val WORDS_PER_MINUTE = 200

    /** No verse clears in less than this, however short it is. */
    private const val MIN_DWELL_MS = 3_200L

    /** How long the button stays disabled for a given verse. */
    fun dwellFor(text: String): Long {
        val words = text.trim().split(Regex("\\s+")).size
        return max(MIN_DWELL_MS, ((words.toDouble() / WORDS_PER_MINUTE) * 60_000).toLong())
    }

    /** Total time the gate takes at the assumed pace. */
    fun totalReadingSeconds(): Int =
        (verses.sumOf { dwellFor(it) } / 1000).toInt()

    /** [totalReadingSeconds], rounded up to whole minutes for display. */
    fun totalReadingMinutes(): Int = (totalReadingSeconds() + 59) / 60

    /**
     * A closing comprehension check: one verse with a substantial word removed.
     * Answering wrong sends the reader back to verse 1.
     */
    data class WordCheck(
        val verseIndex: Int,
        val wordIndex: Int,
        val answer: String,
        val words: List<String>
    )

    fun makeWordCheck(random: Random = Random.Default): WordCheck {
        repeat(40) {
            val vi = random.nextInt(verses.size)
            val words = verses[vi].split(" ")
            val candidates = words.mapIndexed { idx, w ->
                idx to w.filter { it.isLetter() }
            }.filter { it.second.length >= 5 }

            if (candidates.isNotEmpty()) {
                val (idx, bare) = candidates[random.nextInt(candidates.size)]
                return WordCheck(vi, idx, bare.lowercase(), words)
            }
        }
        val fallback = verses[22].split(" ")
        return WordCheck(22, 2, "wages", fallback)   // "For the wages of sin..."
    }
}

/**
 * What the reader is trying to unlock. The gate is identical either way; only
 * the closing button label changes.
 */
sealed class GateIntent {
    /** Remove a single domain from the blocklist. */
    data class UnblockDomain(val domainId: Long, val domain: String) : GateIntent()

    /** Turn off filtering entirely — otherwise the dashboard toggle is a one-tap bypass. */
    data object StopProtection : GateIntent()

    val label: String
        get() = when (this) {
            is UnblockDomain -> "Unblock $domain"
            is StopProtection -> "Stop protection"
        }
}
