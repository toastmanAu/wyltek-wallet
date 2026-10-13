package com.wyltek.wallet.prepaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrepaidAmountTest {
    @Test fun parseCkbToUnits_accepts_up_to_three_decimals() {
        assertEquals(250_000, PrepaidAmount.parseCkbToUnits("250"))
        assertEquals(250_500, PrepaidAmount.parseCkbToUnits("250.5"))
        assertEquals(1, PrepaidAmount.parseCkbToUnits("0.001"))
        assertEquals(1_000_000, PrepaidAmount.parseCkbToUnits(" 1000 "))
    }
    @Test fun parseCkbToUnits_rejects_bad_input() {
        listOf("", " ", "abc", "-5", "0", "0.0004", "1.2345", "1,000", "1e3", "2147483648", "+5", ".5", "5.").forEach {
            assertNull("should reject '$it'", PrepaidAmount.parseCkbToUnits(it))
        }
    }
    @Test fun formatUnits_is_ckb_with_three_decimals() {
        assertEquals("250.000 CKB", PrepaidAmount.formatUnits(250_000))
        assertEquals("-0.001 CKB", PrepaidAmount.formatUnits(-1))
    }
}
