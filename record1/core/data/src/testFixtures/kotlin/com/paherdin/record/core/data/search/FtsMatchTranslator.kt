package com.paherdin.record.core.data.search

/**
 * Tech PoC P4 (plan Task 5) — the frozen FTS `MATCH` translation: normalize the
 * query; split on one-or-more frozen Unicode White_Space code points; reject an
 * empty token list; double every embedded `"`; append `*` to every token; join
 * tokens with one ASCII space; wrap the complete expression in double quotes.
 *
 * Examples: `exp` → `"exp*"`, `project notes` → `"project* notes*"`,
 * `ＡＢＣ` → `"abc*"`, `50%` → `"50%*"`. This deliberately provides FTS
 * phrase/prefix semantics, not a substring fallback.
 */
object FtsMatchTranslator {

    private val WHITE_SPACE_REGEX = Regex("[\\u0009\\u000A\\u000B\\u000C\\u000D\\u0020\\u0085\\u00A0\\u1680" +
        "\\u2000\\u2001\\u2002\\u2003\\u2004\\u2005\\u2006\\u2007\\u2008\\u2009\\u200A\\u2028\\u2029" +
        "\\u202F\\u205F\\u3000]+")

    /** @throws IllegalArgumentException when normalization yields an empty token list. */
    fun translate(rawQuery: String): String {
        val normalized = SearchNormalizer.normalize(rawQuery)
        val tokens = normalized.split(WHITE_SPACE_REGEX).filter { it.isNotEmpty() }
        require(tokens.isNotEmpty()) { "MATCH translation rejected an empty normalized query: '$rawQuery'" }
        val expression = tokens.joinToString(" ") { token ->
            token.replace("\"", "\"\"") + "*"
        }
        return "\"$expression\""
    }
}
