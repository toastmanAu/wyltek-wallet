package com.wyltek.wallet.data

import com.wyltek.wallet.core.model.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ML-DSA-65 targets ~192-bit security; a 12-word (128-bit) phrase would cap a
 * post-quantum account well below that. PQ and Hybrid need 24 words.
 */
class PqEntropyRuleTest {

    @Test fun classic_accepts_every_standard_length() {
        for (n in listOf(12, 18, 24)) assertTrue(PqEntropyRule.allows(AccountType.CLASSIC, n))
    }

    @Test fun post_quantum_needs_24_words() {
        assertFalse(PqEntropyRule.allows(AccountType.POST_QUANTUM, 12))
        assertFalse(PqEntropyRule.allows(AccountType.POST_QUANTUM, 18))
        assertTrue(PqEntropyRule.allows(AccountType.POST_QUANTUM, 24))
    }

    @Test fun hybrid_needs_24_words() {
        assertFalse(PqEntropyRule.allows(AccountType.HYBRID, 12))
        assertFalse(PqEntropyRule.allows(AccountType.HYBRID, 18))
        assertTrue(PqEntropyRule.allows(AccountType.HYBRID, 24))
    }

    @Test fun word_count_splits_on_any_whitespace_after_trim() {
        assertEquals(12, PqEntropyRule.wordCount("  a b\tc\nd e  f g h i j k l "))
        assertEquals(0, PqEntropyRule.wordCount("   "))
    }

    @Test fun check_returns_the_user_facing_message_or_null() {
        val twelve = List(12) { "abandon" }.joinToString(" ")
        val twentyFour = List(24) { "abandon" }.joinToString(" ")
        assertEquals(
            "Post-quantum accounts need a 24-word phrase: 12 words give only 128-bit security",
            PqEntropyRule.check(AccountType.HYBRID, twelve)
        )
        assertNull(PqEntropyRule.check(AccountType.POST_QUANTUM, twentyFour))
        assertNull(PqEntropyRule.check(AccountType.CLASSIC, twelve))
    }
}
