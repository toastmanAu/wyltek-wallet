package com.wyltek.wallet.prepaid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockHashTypeTest {
    @Test fun only_type_and_data_round_trip_through_build_transaction() {
        assertTrue(isSupportedHashType("type")); assertTrue(isSupportedHashType("data"))
        assertFalse(isSupportedHashType("data1")); assertFalse(isSupportedHashType("data2")); assertFalse(isSupportedHashType(""))
    }
}
