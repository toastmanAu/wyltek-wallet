package com.wyltek.wallet.prepaid

import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.ui.AuditCsv
import org.junit.Assert.assertEquals
import org.junit.Test

class AuditCsvTest {
    @Test fun `csv has the spec columns in order and escapes quotes`() {
        val e = PrepaidEventEntity(id = 7, atMs = 1_700_000_000_000, actionId = "a", kind = KIND_CASH_OUT, phase = PHASE_DONE, reserveId = "r", uid = "u",
            amountUnits = -100, balanceBefore = 100, balanceAfter = 0, counterBefore = 2, counterAfter = 2, txHash = "0xab", detail = "to \"ckt1x\"; tag wiped")
        val out = AuditCsv.render(listOf(e))
        assertEquals("id,at_ms,action_id,kind,phase,reserve_id,uid,amount_units,balance_before,balance_after,counter_before,counter_after,tx_hash,detail", out.lines()[0])
        assertEquals("""7,1700000000000,a,cash_out,done,r,u,-100,100,0,2,2,0xab,"to ""ckt1x""; tag wiped"""", out.lines()[1])
        assertEquals(2, out.trimEnd().lines().size)
    }
    @Test fun `null fields are empty`() {
        val e = PrepaidEventEntity(atMs = 1, actionId = "a", kind = KIND_ISSUE, phase = PHASE_FAILED, reserveId = null, uid = "u", amountUnits = 0,
            balanceBefore = 0, balanceAfter = 0, counterBefore = 0, counterAfter = 0, txHash = null, detail = "")
        assertEquals("0,1,a,issue,failed,,u,0,0,0,0,0,,", AuditCsv.render(listOf(e)).lines()[1])
    }
}
