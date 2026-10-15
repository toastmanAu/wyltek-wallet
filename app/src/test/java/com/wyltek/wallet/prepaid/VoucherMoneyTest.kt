package com.wyltek.wallet.prepaid

import org.junit.Assert.*
import org.junit.Test

class VoucherMoneyTest {
    @Test fun two_decimal_currencies() {
        assertEquals(450L, VoucherMoney.parse("4.50", "AUD")); assertEquals(400L, VoucherMoney.parse("4", "AUD")); assertEquals(5L, VoucherMoney.parse("0.05", "USD"))
        assertNull(VoucherMoney.parse("4.555", "AUD")); assertNull(VoucherMoney.parse("-1", "AUD")); assertNull(VoucherMoney.parse("abc", "AUD")); assertNull(VoucherMoney.parse("", "AUD"))
        assertNull("above u32 minor units", VoucherMoney.parse("42949672.96", "AUD"))
        assertEquals("4.50 AUD", VoucherMoney.format(450, "AUD"))
    }
    @Test fun zero_decimal_currencies() {
        assertEquals(500L, VoucherMoney.parse("500", "JPY")); assertNull(VoucherMoney.parse("500.5", "JPY"))
        assertEquals("500 JPY", VoucherMoney.format(500, "JPY"))
    }
}
