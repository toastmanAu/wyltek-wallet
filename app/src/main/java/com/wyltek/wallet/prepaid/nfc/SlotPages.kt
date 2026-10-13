package com.wyltek.wallet.prepaid.nfc

import com.wyltek.wallet.core.native.SlotPickFfi

/** NTAG213/215/216 user memory layout for the two 64-byte record slots (demo-tier spec §4.1). */
object SlotPages {
    const val FIRST_USER_PAGE = 4
    const val PAGES_PER_SLOT = 16
    fun firstPage(slot: SlotPickFfi): Int = when (slot) { SlotPickFfi.A -> 4; SlotPickFfi.B -> 20; else -> throw IllegalArgumentException("slot $slot") }
    /** Page index within a slot, in write order. The MAC occupies bytes 56..63 = pages 14 and 15, written last. */
    fun writeOrder(): List<Int> = (0 until PAGES_PER_SLOT).toList()
    /** `readPages(offset)` returns 4 pages; 8 reads cover 4..35. */
    fun readOffsets(): List<Int> = (0 until 8).map { FIRST_USER_PAGE + it * 4 }
    fun uidHexOf(id: ByteArray): String = id.joinToString("") { "%02x".format(it) }
    /** Demo-tier §2.4: 7-byte UID, ISO 14443-3A, SAK 0x00, Ultralight command set. */
    fun isNtag21x(idLen: Int, sak: Short, hasUltralight: Boolean): Boolean = idLen == 7 && sak.toInt() == 0 && hasUltralight
}
