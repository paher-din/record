package com.paherdin.record.core.data.search

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tech PoC P4 (plan Task 2): normalization v1 — Unicode trim → NFKC → bounded
 * case fold, plus the `LIKE`-escaping helper. Written test-first: expected to
 * fail until [SearchNormalizer] implements `normalize` and `escapeLike`.
 */
class SearchNormalizerTest {

    private val n = SearchNormalizer

    /** The frozen Unicode White_Space property list (v1), by code point. */
    private fun cp(vararg points: Int): String =
        points.joinToString("") { p -> String(Character.toChars(p)) }

    // ---- trim: the frozen Unicode White_Space set ----

    @Test
    fun trimsFrozenUnicodeWhiteSpaceSet() {
        // TAB..CR, SPACE, NEL, NBSP, OGHAM SPACE, EN QUAD..HAIR SPACE,
        // LINE/PARAGRAPH SEPARATOR, NNBSP, MMSP, IDEOGRAPHIC SPACE.
        val ws = cp(
            0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0x85, 0xA0, 0x1680,
            0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006,
            0x2007, 0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F,
            0x205F, 0x3000,
        )
        assertEquals("abc", n.normalize("$ws abc $ws"))
    }

    @Test
    fun middleNbspIsNotTrimmedButNfkcConvertsItToSpace() {
        // NBSP in the middle is not a trim candidate; NFKC maps it to a regular space.
        assertEquals("a b", n.normalize("a b"))
    }

    // ---- NFKC: full-width ASCII and CJK compatibility forms ----

    @Test
    fun fullWidthAsciiFoldsToAsciiAndThenLowercases() {
        assertEquals("abc123", n.normalize("ＡＢＣ１２３"))
    }

    @Test
    fun cjkCompatibilityFormsMapToCasedNeutralCjk() {
        // U+FA3C maps to U+5C6E under NFKC (verified on the pinned JDK);
        // result stays cased-neutral CJK, no case fold.
        val input = String(Character.toChars(0xFA3C))
        val expected = String(Character.toChars(0x5C6E))
        assertEquals(expected, n.normalize(input))
    }

    @Test
    fun halfWidthKatakanaCompatibilityIsAppliedButCasedNeutral() {
        assertEquals("アイ", n.normalize("ｱｲ"))
    }

    // ---- supplementary CJK ----

    @Test
    fun supplementaryCjkIsPreserved() {
        // U+20000 (CJK Extension B) is cased-neutral and survives normalization.
        assertEquals("𠀀", n.normalize("𠀀"))
    }

    // ---- ASCII fold exhaustiveness ----

    @Test
    fun everyAsciiLetterFoldsToLowercase() {
        val upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val lower = "abcdefghijklmnopqrstuvwxyz"
        assertEquals(lower, n.normalize(upper))
        assertEquals(lower, n.normalize(lower))
    }

    @Test
    fun nonAsciiCasedLettersOutsideBoundaryAreNotFoldExpandedByV1() {
        // ß is outside the frozen CJK+ASCII boundary: v1 performs no fold
        // expansion (ß → ss), pinning the boundary rather than claiming
        // full-Unicode case folding. A single-code-point lowercase mapping
        // (e.g. ẞ → ß) is not an expansion and is not asserted here.
        assertEquals("ß", n.normalize("ß"))
    }

    @Test
    fun normalizationIsLocaleIndependentAndDeterministic() {
        assertEquals(n.normalize("Coffee 咖啡"), n.normalize("COFFEE 咖啡"))
        assertEquals(n.normalize("Coffee 咖啡"), n.normalize("coffee 咖啡"))
    }

    // ---- LIKE escaping ----

    @Test
    fun escapeLikeEscapesBackslashPercentAndUnderscoreInOrder() {
        assertEquals("a\\\\b", n.escapeLike("a\\b"))
        assertEquals("100\\%", n.escapeLike("100%"))
        assertEquals("under\\_score", n.escapeLike("under_score"))
        assertEquals("a\\\\b\\%c\\_d", n.escapeLike("a\\b%c_d"))
    }

    @Test
    fun escapeLikeLeavesOrdinaryTextUntouched() {
        assertEquals("咖啡 coffee", n.escapeLike("咖啡 coffee"))
    }
}
