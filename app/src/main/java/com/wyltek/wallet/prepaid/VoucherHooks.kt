package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.BatchResultFfi
import com.wyltek.wallet.core.native.VoucherStateFfi

/** What PrepaidService needs to know about vouchers, and nothing more. VoucherService installs itself. */
interface VoucherHooks {
    suspend fun uidHoldsVoucher(uidHex: String): Boolean
    suspend fun voucherStates(): List<VoucherStateFfi>
    /** Applies accepted redeems, overrides and staff lockouts; returns [names] with this hook's own entries finalised. Runs inside the settle lock. */
    suspend fun apply(res: BatchResultFfi, names: List<String>, terminalLabel: String): List<String>
    /** After a settlement has returned, in a separate turn of the lock (PrepaidService.launchAfterSettle): close
     *  vouchers whose last use settled. Must not throw. */
    suspend fun afterSettle()
    /** True when [afterSettle] has anything to close; a settlement that leaves nothing closable launches no close. */
    suspend fun hasClosable(): Boolean
    /** Outpoints of every voucher cell that may still be live (anything not CLOSED or VOID): never a fee input. */
    suspend fun liveOutpoints(): Set<String>
}

/** Fail closed: with no voucher service installed nothing records redeems, overrides or lockouts, and the Vault must
 *  never answer `accepted` for an entry it has not recorded (spec 8.3), so those entries answer `retry`. */
object NoVoucherHooks : VoucherHooks {
    override suspend fun uidHoldsVoucher(uidHex: String) = false
    override suspend fun voucherStates() = emptyList<VoucherStateFfi>()
    override suspend fun apply(res: BatchResultFfi, names: List<String>, terminalLabel: String): List<String> {
        val unrecorded = (res.redeems.map { it.index } + res.overrides.map { it.index } + res.lockouts.map { it.index }).map { it.toInt() }.toSet()
        return names.mapIndexed { i, v -> if (v == "accepted" && i in unrecorded) "retry" else v }
    }
    override suspend fun afterSettle() {}
    override suspend fun hasClosable() = false
    override suspend fun liveOutpoints() = emptySet<String>()
}
