package com.wyltek.wallet.prepaid.fakes

import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.prepaid.TagLostException
import com.wyltek.wallet.prepaid.TagPort

// 36 pages of 4 bytes (0..35); slots at pages 4..19 and 20..35; optional torn write.
class FakeTag(override val uidHex: String) : TagPort {
    val pages = Array(36) { ByteArray(4) }
    var present = true
    var failAfterPages: Int? = null       // throw TagLost after this many page writes in the next writeSlot
    var pageWrites = 0
    /** Tear (at page 0) the Nth writeSlot call from now (1-based); counts down on each call. */
    var tearOnWriteCall: Int? = null
    /** Throw TagLostException on the next readSlots only. */
    var failNextRead = false
    override fun isPresent() = present
    private fun first(slot: SlotPickFfi) = if (slot == SlotPickFfi.A) 4 else 20
    override suspend fun readSlots(): ByteArray { if (failNextRead) { failNextRead = false; throw TagLostException("read glitch") }; if (!present) throw TagLostException("gone"); return (4 until 36).fold(ByteArray(0)) { a, p -> a + pages[p] } }
    override suspend fun writeSlot(slot: SlotPickFfi, raw64: ByteArray) {
        require(slot == SlotPickFfi.A || slot == SlotPickFfi.B); require(raw64.size == 64)
        if (!present) throw TagLostException("gone")
        tearOnWriteCall?.let { n -> if (n <= 1) { tearOnWriteCall = null; present = false; throw TagLostException("torn") } else tearOnWriteCall = n - 1 }
        for (p in 0 until 16) {
            failAfterPages?.let { if (p >= it) { failAfterPages = null; present = false; throw TagLostException("torn at page $p") } }
            pages[first(slot) + p] = raw64.copyOfRange(p * 4, p * 4 + 4); pageWrites++
        }
    }
    override suspend fun zeroSlot(slot: SlotPickFfi) = writeSlot(slot, ByteArray(64))
    var signature = ByteArray(32) { 0x5A }
    override suspend fun readSignature(): ByteArray { if (!present) throw TagLostException("gone"); return signature.copyOf() }
    fun slot(slot: SlotPickFfi): ByteArray = (0 until 16).fold(ByteArray(0)) { a, p -> a + pages[first(slot) + p] }
}
