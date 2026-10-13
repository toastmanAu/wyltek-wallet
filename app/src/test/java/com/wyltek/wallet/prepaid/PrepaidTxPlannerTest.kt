package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.OutPoint
import com.wyltek.wallet.core.model.Utxo
import org.junit.Assert.*
import org.junit.Test

class PrepaidTxPlannerTest {
    private val lock = LockScript("0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8", "type", "0x" + "ab".repeat(20))
    private val ckb = 100_000_000uL
    private fun utxo(tx: String, idx: Int, capCkb: ULong, type: LockScript? = null, data: String? = null) =
        Utxo(OutPoint("0x$tx", idx.toUInt()), capCkb * ckb, lock, type, data)
    private val floor = 61uL * ckb

    @Test fun `reserve that covers fee and change funds a void alone`() {
        val reserve = utxo("r", 0, 200uL)
        val p = PrepaidTxPlanner.plan(listOf(reserve), listOf(utxo("w", 0, 500uL)), emptySet(), emptyList(), lock)
        assertEquals(listOf(reserve), p.inputs)
        assertEquals(1, p.outputs.size)
        assertEquals(200uL * ckb - p.feeShannons, p.outputs[0].capacity)
        assertEquals(0, p.changeIndex)
    }

    @Test fun `small reserve pulls the smallest sufficient wallet cells`() {
        val reserve = utxo("r", 0, 61uL)
        val wallet = listOf(utxo("w", 0, 900uL), utxo("w", 1, 90uL), utxo("w", 2, 30uL))
        val p = PrepaidTxPlanner.plan(listOf(reserve), wallet, emptySet(), emptyList(), lock)
        assertEquals(listOf("0xr:0", "0xw:2"), p.inputs.map { PrepaidTxPlanner.outpointKey(it) })
        assertTrue(p.outputs[0].capacity >= PrepaidTxPlanner.MIN_CHANGE_SHANNONS)
    }

    @Test fun `issue output plus change, change goes last`() {
        val fixed = listOf(PrepaidTxPlanner.output(floor + 250_000uL * 100_000uL, lock))
        val p = PrepaidTxPlanner.plan(emptyList(), listOf(utxo("w", 0, 1000uL)), emptySet(), fixed, lock)
        assertEquals(2, p.outputs.size); assertEquals(1, p.changeIndex)
        assertEquals(fixed[0], p.outputs[0])
        assertEquals(1000uL * ckb - fixed[0].capacity - p.feeShannons, p.outputs[1].capacity)
    }

    @Test fun `index reserves and non pure cells are never wallet inputs`() {
        val wallet = listOf(utxo("other", 0, 300uL), utxo("sudt", 0, 300uL, type = lock), utxo("data", 0, 300uL, data = "0x01"), utxo("w", 0, 300uL))
        val p = PrepaidTxPlanner.plan(emptyList(), wallet, setOf("0xother:0"), emptyList(), lock)
        assertEquals(listOf("0xw:0"), p.inputs.map { PrepaidTxPlanner.outpointKey(it) })
    }

    @Test fun `insufficient reports the shortfall`() {
        try {
            PrepaidTxPlanner.plan(emptyList(), listOf(utxo("w", 0, 50uL)), emptySet(), listOf(PrepaidTxPlanner.output(floor, lock)), lock)
            fail()
        } catch (e: InsufficientFunds) {
            assertEquals(floor + PrepaidTxPlanner.fee(1, 2) + PrepaidTxPlanner.MIN_CHANGE_SHANNONS - 50uL * ckb, e.shortfallShannons)
        }
    }

    @Test fun `fee grows with inputs and outputs`() {
        assertTrue(PrepaidTxPlanner.fee(1, 1) < PrepaidTxPlanner.fee(3, 1))
        assertEquals(1_000uL + 300uL * 4uL, PrepaidTxPlanner.fee(2, 2))
    }
}
