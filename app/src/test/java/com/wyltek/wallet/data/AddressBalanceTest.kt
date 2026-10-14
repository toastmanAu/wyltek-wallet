package com.wyltek.wallet.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressBalanceTest {
    @Test fun `formats shannons as CKB without trailing zeros`() {
        assertEquals("0 CKB", AddressBalance.formatCkb(0uL))
        assertEquals("1 CKB", AddressBalance.formatCkb(100_000_000uL))
        assertEquals("1.5 CKB", AddressBalance.formatCkb(150_000_000uL))
        assertEquals("100000.00000001 CKB", AddressBalance.formatCkb(10_000_000_000_001uL))
    }

    @Test fun `total sums every address`() {
        val list = listOf(AddressBalance("Classic", 300_000_000uL), AddressBalance("PQ", 50_000_000uL))
        assertEquals(350_000_000uL, AddressBalance.total(list))
        assertEquals(0uL, AddressBalance.total(emptyList()))
    }

    @Test fun `breakdown line names each address`() {
        val list = listOf(AddressBalance("Classic", 300_000_000uL), AddressBalance("PQ", 50_000_000uL))
        assertEquals("Classic 3 CKB · PQ 0.5 CKB", AddressBalance.breakdown(list))
    }
}
