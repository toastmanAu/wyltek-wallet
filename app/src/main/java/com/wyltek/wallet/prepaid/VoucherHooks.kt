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

object NoVoucherHooks : VoucherHooks {
    override suspend fun uidHoldsVoucher(uidHex: String) = false
    override suspend fun voucherStates() = emptyList<VoucherStateFfi>()
    override suspend fun apply(res: BatchResultFfi, names: List<String>, terminalLabel: String) = names
    override suspend fun afterSettle() {}
    override suspend fun hasClosable() = false
    override suspend fun liveOutpoints() = emptySet<String>()
}
