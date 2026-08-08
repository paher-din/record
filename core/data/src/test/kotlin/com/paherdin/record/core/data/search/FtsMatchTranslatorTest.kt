package com.paherdin.record.core.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tech PoC P4 (plan Task 5): the frozen FTS `MATCH` translation — the four
 * pinned examples plus quote/whitespace handling.
 */
class FtsMatchTranslatorTest {

    @Test
    fun frozenExamplesTranslateExactly() {
        assertEquals("\"exp*\"", FtsMatchTranslator.translate("exp"))
        assertEquals("\"project* notes*\"", FtsMatchTranslator.translate("project notes"))
        assertEquals("\"abc*\"", FtsMatchTranslator.translate("ＡＢＣ"))
        assertEquals("\"50%*\"", FtsMatchTranslator.translate("50%"))
    }

    @Test
    fun embeddedDoubleQuotesAreDoubled() {
        assertEquals("\"say\"\"hi*\"", FtsMatchTranslator.translate("say\"hi"))
    }

    @Test
    fun unicodeWhitespaceVariantsSplitTokens() {
        // Full-width space and NBSP both split tokens.
        assertEquals("\"咖啡* coffee*\"", FtsMatchTranslator.translate("咖啡　coffee"))
        assertEquals("\"a* b*\"", FtsMatchTranslator.translate("a b"))
    }

    @Test
    fun caseFoldAppliesBeforeTranslation() {
        assertEquals("\"expense*\"", FtsMatchTranslator.translate("EXPENSE"))
    }

    @Test
    fun emptyNormalizedQueryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FtsMatchTranslator.translate("    ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FtsMatchTranslator.translate("　 　")
        }
    }
}
