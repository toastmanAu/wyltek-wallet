package com.wyltek.wallet.prepaid.fakes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FakeProtocolTest {
    private val p = FakeProtocol()
    private val k = "101112131415161718191a1b1c1d1e1f"
    private val kid = "c0ffee0102030405"

    @Test fun fake_pin_check_is_deterministic_and_changes_with_pin_ver_and_pin() {
        val a = p.staffPinCheck(k, kid, 1, "0042")
        assertEquals(a, p.staffPinCheck(k, kid, 1, "0042"))
        assertEquals(32, a.length)
        assertNotEquals(a, p.staffPinCheck(k, kid, 2, "0042"))
        assertNotEquals(a, p.staffPinCheck(k, kid, 1, "1042"))
    }
}
