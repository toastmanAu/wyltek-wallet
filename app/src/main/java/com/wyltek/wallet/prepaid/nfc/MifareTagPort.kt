package com.wyltek.wallet.prepaid.nfc

import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.prepaid.TagLostException
import com.wyltek.wallet.prepaid.TagPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * [TagPort] over Android's MifareUltralight. One connection per call. Every tag I/O failure
 * (IOException, stale-tag SecurityException, closed-tech IllegalStateException) surfaces as a [TagLostException].
 * Sessions and probes share [lock] so a probe never closes a connection mid-write.
 */
class MifareTagPort(private val tag: Tag) : TagPort {
    override val uidHex: String = SlotPages.uidHexOf(tag.id)

    private val lock = Mutex()

    /** Non-suspending. While a session holds the lock the tag is in the field, so report present. */
    override fun isPresent(): Boolean {
        if (!lock.tryLock()) return true
        var mu: MifareUltralight? = null
        return try {
            val tech = MifareUltralight.get(tag) ?: return false
            mu = tech
            tech.connect()
            tech.isConnected
        } catch (e: Exception) {
            false
        } finally {
            try { mu?.close() } catch (_: Exception) {}
            lock.unlock()
        }
    }

    private suspend fun <T> session(block: (MifareUltralight) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val mu = MifareUltralight.get(tag) ?: throw TagLostException("not an Ultralight tag")
            try {
                mu.connect()
                block(mu)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw TagLostException(e.message ?: "tag lost", e)
            } catch (e: SecurityException) {
                throw TagLostException(e.message ?: "tag out of date", e)
            } catch (e: IllegalStateException) {
                throw TagLostException(e.message ?: "tech not connected", e)
            } finally {
                // A failed close after a completed operation is not a failure of the operation.
                try { mu.close() } catch (_: Exception) {}
            }
        }
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

    override suspend fun readSignature(): ByteArray = session { mu ->
        val sig = mu.transceive(byteArrayOf(0x3C, 0x00))
        if (sig.size != 32) throw IOException("READ_SIG returned ${sig.size} bytes")
        sig
    }

    companion object {
        fun accepts(tag: Tag): Boolean {
            val a = NfcA.get(tag) ?: return false
            return SlotPages.isNtag21x(tag.id.size, a.sak, tag.techList.contains(MifareUltralight::class.java.name))
        }
    }
}
