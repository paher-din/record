package com.paherdin.record.core.data.search

import java.text.Normalizer
import java.util.Locale

/**
 * Tech PoC P4 (plan Task 1) fixture-visibility marker: proves the module's
 * JVM unit-test and instrumented-test source sets both resolve the shared
 * `testFixtures` classes.
 */
object P4FixtureMarker {
    const val token = "p4-fixture"
}

/**
 * Tech PoC P4 — search normalization v1: Unicode trim → NFKC → bounded case
 * fold, per spec Revision 1/Revision 2 and plan Task 2.
 *
 * The v1 corpus boundary is CJK plus ASCII English plus the explicit full-width
 * ASCII compatibility cases. After NFKC maps the admitted full-width/compatibility
 * forms, the only remaining cased characters are ASCII A–Z/a–z, so
 * `lowercase(Locale.ROOT)` is the complete and expansion-free fold for every
 * admitted input. Non-ASCII fold expansions (e.g. `ß`) are outside v1.
 */
object SearchNormalizer {

    const val normalizationVersion = 1

    /** The frozen Unicode White_Space property list for v1 trimming. */
    private val WHITE_SPACE = intArrayOf(
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0x85, 0xA0, 0x1680,
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006,
        0x2007, 0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F,
        0x205F, 0x3000,
    ).toSet()

    /** Applied identically to projected text and to the query before matching. */
    fun normalize(value: String): String {
        val trimmed = trimWhiteSpace(value)
        val nfkc = Normalizer.normalize(trimmed, Normalizer.Form.NFKC)
        return nfkc.lowercase(Locale.ROOT)
    }

    /**
     * `LIKE` escaping for the paged-LIKE path: `\` → `\\`, `%` → `\%`, `_` → `\_`
     * in that order; the DAO uses `ESCAPE '\'`.
     */
    fun escapeLike(value: String): String = buildString(value.length + 8) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '%' -> append("\\%")
                '_' -> append("\\_")
                else -> append(ch)
            }
        }
    }

    private fun trimWhiteSpace(value: String): String {
        var start = 0
        var end = value.length
        while (start < end && isWhiteSpaceAt(value, start)) start = value.offsetByCodePoints(start, 1)
        while (end > start && isWhiteSpaceAt(value, end - 1)) end = value.offsetByCodePoints(end - 1, -1)
        return value.substring(start, end)
    }

    private fun isWhiteSpaceAt(value: String, index: Int): Boolean =
        value.codePointAt(index) in WHITE_SPACE
}
