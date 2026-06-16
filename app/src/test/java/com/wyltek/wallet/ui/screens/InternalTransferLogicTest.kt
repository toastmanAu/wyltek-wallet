package com.wyltek.wallet.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class InternalTransferLogicTest {

    @Test fun tokens_enabled_from_classic_source() {
        assertTrue(InternalTransferLogic.tokensEnabled(sourceIsPq = false))
    }

    @Test fun tokens_disabled_from_pq_source() {
        assertFalse(InternalTransferLogic.tokensEnabled(sourceIsPq = true))
    }

    @Test fun parses_whole_ckb() {
        assertEquals(100_000_000uL, InternalTransferLogic.parseCkbToShannons("1"))
    }

    @Test fun parses_fractional_ckb_to_shannons() {
        assertEquals(150_000_000uL, InternalTransferLogic.parseCkbToShannons("1.5"))
        assertEquals(1uL, InternalTransferLogic.parseCkbToShannons("0.00000001"))
    }

    @Test fun rejects_more_than_8_decimals() {
        assertNull(InternalTransferLogic.parseCkbToShannons("0.000000001"))
    }

    @Test fun rejects_zero_negative_and_garbage_ckb() {
        assertNull(InternalTransferLogic.parseCkbToShannons("0"))
        assertNull(InternalTransferLogic.parseCkbToShannons("-5"))
        assertNull(InternalTransferLogic.parseCkbToShannons(""))
        assertNull(InternalTransferLogic.parseCkbToShannons("abc"))
    }

    @Test fun parses_token_units() {
        assertEquals(BigInteger("30000"), InternalTransferLogic.parseTokenUnits("30000"))
    }

    @Test fun rejects_zero_negative_and_garbage_tokens() {
        assertNull(InternalTransferLogic.parseTokenUnits("0"))
        assertNull(InternalTransferLogic.parseTokenUnits("-1"))
        assertNull(InternalTransferLogic.parseTokenUnits("1.5"))
        assertNull(InternalTransferLogic.parseTokenUnits(""))
    }

    @Test fun ckb_within_balance() {
        assertTrue(InternalTransferLogic.ckbAmountWithinBalance(100uL, 100uL))
        assertFalse(InternalTransferLogic.ckbAmountWithinBalance(101uL, 100uL))
    }

    @Test fun token_within_balance() {
        assertTrue(InternalTransferLogic.tokenAmountWithinBalance(BigInteger("5"), BigInteger("5")))
        assertFalse(InternalTransferLogic.tokenAmountWithinBalance(BigInteger("6"), BigInteger("5")))
    }
}
