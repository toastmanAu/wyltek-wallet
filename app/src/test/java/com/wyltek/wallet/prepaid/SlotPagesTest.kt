package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.prepaid.nfc.SlotPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotPagesTest {
    @Test fun `slot pages and read offsets cover exactly pages 4 to 35`() {
        assertEquals(4, SlotPages.firstPage(SlotPickFfi.A)); assertEquals(20, SlotPages.firstPage(SlotPickFfi.B))
        assertEquals(listOf(4, 8, 12, 16, 20, 24, 28, 32), SlotPages.readOffsets())
        assertEquals((0..15).toList(), SlotPages.writeOrder())
        assertEquals(listOf(14, 15), SlotPages.writeOrder().takeLast(2))
    }
    @Test fun `only 7 byte uid type a with sak 0 and ultralight tech is an ntag`() {
        assertTrue(SlotPages.isNtag21x(7, 0, true))
        assertFalse(SlotPages.isNtag21x(4, 0x08, false))   // MIFARE Classic
        assertFalse(SlotPages.isNtag21x(7, 0x20, false))   // DESFire
        assertFalse(SlotPages.isNtag21x(7, 0, false))
    }
    @Test fun `uid hex is lowercase without prefix`() { assertEquals("04958caa5c5e80", SlotPages.uidHexOf(byteArrayOf(0x04, 0x95.toByte(), 0x8c.toByte(), 0xaa.toByte(), 0x5c, 0x5e, 0x80.toByte()))) }
}
