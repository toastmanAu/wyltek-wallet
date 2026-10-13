package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.chain.TxStatus
import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.OutPoint
import com.wyltek.wallet.core.model.Utxo
import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.prepaid.fakes.FakeChain
import com.wyltek.wallet.prepaid.fakes.FakeProtocol
import com.wyltek.wallet.prepaid.fakes.FakeTag
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FakesSelfTest {
    private val uid = "04958caa5c5e80"
    private val rec = CardRecordInfo(0u, "a0".repeat(32), 1000, 3u, "1122334455667788")

    @Test fun `fake protocol seal and select agree and bind the uid`() {
        val p = FakeProtocol()
        val raw = p.seal("k", uid, rec)
        val r = p.selectSlot("k", uid, raw + ByteArray(64))
        assertEquals(SlotPickFfi.A, r.pick); assertEquals(rec, r.record)
        val other = p.selectSlot("k", "04ebbb02cd2a81", raw + ByteArray(64))
        assertEquals(SlotPickFfi.NONE, other.pick); assertEquals(SlotReason.MAC_FAIL, other.reason); assertEquals(rec.merchantIdHex, other.merchantHintHex)
        val r2 = p.selectSlot("k", uid, raw + p.seal("k", uid, rec.copy(counter = 4u, balanceUnits = 900)))
        assertEquals(SlotPickFfi.B, r2.pick); assertEquals(900, r2.record!!.balanceUnits)
    }

    @Test fun `fake tag torn write keeps the other slot and goes absent`() = runTest {
        val p = FakeProtocol(); val t = FakeTag(uid)
        t.writeSlot(SlotPickFfi.A, p.seal("k", uid, rec))
        t.failAfterPages = 9
        try { t.writeSlot(SlotPickFfi.B, p.seal("k", uid, rec.copy(counter = 4u))); fail() } catch (e: TagLostException) {}
        assertFalse(t.isPresent()); t.present = true
        val r = p.selectSlot("k", uid, t.readSlots())
        assertEquals(SlotPickFfi.A, r.pick); assertEquals(3u, r.record!!.counter)
    }

    @Test fun `fake chain applies a confirmed broadcast`() = runTest {
        val lock = LockScript("0x" + "11".repeat(32), "type", "0xab")
        val c = FakeChain(); c.add(Utxo(OutPoint("0x" + "aa".repeat(32), 0u), 10_000_000_000uL, lock))
        val tx = CkbTxJson.build(c.cells.values.toList(), listOf(PrepaidTxPlanner.output(9_000_000_000uL, lock)), CellDepRef("0x" + "cc".repeat(32), 0u, "dep_group"), "0x")
        val h = "0x" + "dd".repeat(32)
        c.confirmAfterPolls = null
        assertTrue(c.broadcast(tx, h))
        assertEquals(TxStatus.PENDING, c.status(h))
        c.confirm(h); assertEquals(TxStatus.CONFIRMED, c.status(h))
        assertEquals(listOf("$h:0"), c.cells.keys.toList()); assertEquals(9_000_000_000uL, c.cellAt("$h:0")!!.capacity)
    }

    @Test fun `fake chain refuses unknown inputs and reports unknown hashes as explicit UNKNOWN`() = runTest {
        val lock = LockScript("0x" + "11".repeat(32), "type", "0xab")
        val c = FakeChain()
        assertEquals(TxStatus.UNKNOWN, c.status("0x" + "ee".repeat(32)))
        try { c.status("ee".repeat(32)); fail("unprefixed hash must be refused like the node does") } catch (e: java.io.IOException) {}
        val ghost = Utxo(OutPoint("0x" + "bb".repeat(32), 0u), 10_000_000_000uL, lock)
        val tx = CkbTxJson.build(listOf(ghost), listOf(PrepaidTxPlanner.output(9_000_000_000uL, lock)), CellDepRef("0x" + "cc".repeat(32), 0u, "dep_group"), "0x")
        assertFalse(c.broadcast(tx, "0x" + "dd".repeat(32)))
        assertTrue(c.broadcasts.isEmpty())
    }
}
