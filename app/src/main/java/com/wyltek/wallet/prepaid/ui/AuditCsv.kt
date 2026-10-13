package com.wyltek.wallet.prepaid.ui

import com.wyltek.wallet.prepaid.db.PrepaidEventEntity

object AuditCsv {
    const val HEADER = "id,at_ms,action_id,kind,phase,reserve_id,uid,amount_units,balance_before,balance_after,counter_before,counter_after,tx_hash,detail"
    private fun q(s: String?): String = when {
        s == null -> ""
        s.any { it == ',' || it == '"' || it == '\n' || it == '\r' } -> "\"" + s.replace("\"", "\"\"") + "\""
        else -> s
    }
    fun render(events: List<PrepaidEventEntity>): String = buildString {
        appendLine(HEADER)
        for (e in events) appendLine(listOf(e.id.toString(), e.atMs.toString(), e.actionId, e.kind, e.phase, e.reserveId, e.uid, e.amountUnits.toString(),
            e.balanceBefore.toString(), e.balanceAfter.toString(), e.counterBefore.toString(), e.counterAfter.toString(), e.txHash, e.detail).joinToString(",") { q(it) })
    }
}
