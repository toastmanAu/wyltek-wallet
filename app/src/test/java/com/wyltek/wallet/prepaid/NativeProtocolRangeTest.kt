package com.wyltek.wallet.prepaid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeProtocolRangeTest {
    @Test fun pin_ver_must_fit_the_u16_wire_field_and_never_be_zero() {
        assertTrue(pinVerInRange(1)); assertTrue(pinVerInRange(65535))
        assertFalse(pinVerInRange(0)); assertFalse(pinVerInRange(65536)); assertFalse(pinVerInRange(-1))
    }
}
