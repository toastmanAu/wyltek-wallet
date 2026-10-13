package com.wyltek.wallet.prepaid.nfc

import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.prepaid.TagLostException
import com.wyltek.wallet.prepaid.TagPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** [TagPort] over Android's MifareUltralight. One connection per call; every I/O error is a [TagLostException]. */
class MifareTagPort(private val tag: Tag) : TagPort {
    override val uidHex: String = SlotPages.uidHexOf(tag.id)

    override fun isPresent(): Boolean = try {
        val mu = MifareUltralight.get(tag) ?: return false
        mu.connect(); val ok = mu.isConnected; mu.close(); ok
    } catch (e: Exception) { false }

    private suspend fun <T> session(block: (MifareUltralight) -> T): T = withContext(Dispatchers.IO) {
        val mu = MifareUltralight.get(tag) ?: throw TagLostException("not an Ultralight tag")
        try { mu.connect(); block(mu) } catch (e: IOException) { throw TagLostException(e.message ?: "tag lost") }
        finally { try { mu.close() } catch (_: IOException) {} }
    }

    override suspend fun readSlots(): ByteArray = session { mu ->
        SlotPages.readOffsets().fold(ByteArray(0)) { acc, off -> acc + mu.readPages(off) }
    }

    override suspend fun writeSlot(slot: SlotPickFfi, raw64: ByteArray) {
        require(raw64.size == 64)
        session { mu ->
            val first = SlotPages.firstPage(slot)
            for (p in SlotPages.writeOrder()) mu.writePage(first + p, raw64.copyOfRange(p * 4, p * 4 + 4))
        }
    }

    override suspend fun zeroSlot(slot: SlotPickFfi) = writeSlot(slot, ByteArray(64))

    companion object {
        fun accepts(tag: Tag): Boolean {
            val a = NfcA.get(tag) ?: return false
            return SlotPages.isNtag21x(tag.id.size, a.sak, tag.techList.contains(MifareUltralight::class.java.name))
        }
    }
}
