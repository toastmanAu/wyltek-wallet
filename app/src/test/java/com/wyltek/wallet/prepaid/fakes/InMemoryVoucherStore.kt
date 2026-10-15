package com.wyltek.wallet.prepaid.fakes

import com.wyltek.wallet.prepaid.VoucherStore
import com.wyltek.wallet.prepaid.norm
import com.wyltek.wallet.prepaid.db.*
import kotlinx.coroutines.flow.MutableStateFlow

class InMemoryVoucherStore : VoucherStore {
    val vouchers = linkedMapOf<String, VoucherEntity>(); val seen = linkedMapOf<String, JournalSeenEntity>(); val keys = linkedMapOf<String, StaffKeyEntity>()
    private val vf = MutableStateFlow<List<VoucherEntity>>(emptyList()); private val kf = MutableStateFlow<List<StaffKeyEntity>>(emptyList())
    private fun emit() { vf.value = vouchers.values.sortedByDescending { it.updatedAt }; kf.value = keys.values.sortedByDescending { it.enrolledAt } }
    override suspend fun insertVoucher(v: VoucherEntity) { check(v.voucherId !in vouchers); vouchers[v.voucherId] = v.norm(); emit() }
    override suspend fun updateVoucher(v: VoucherEntity) { check(v.voucherId in vouchers) { "voucher row missing" }; vouchers[v.voucherId] = v.norm(); emit() }
    override suspend fun deleteIssuing(voucherId: String) = if (vouchers[voucherId]?.state == VOUCHER_ISSUING) { vouchers.remove(voucherId); emit(); 1 } else 0
    override suspend fun voucher(id: String) = vouchers[id]
    override suspend fun voucherByUid(uid: String) = vouchers.values.filter { it.uid == uid.lowercase() }.maxByOrNull { it.issuedAt }
    override suspend fun allVouchers() = vouchers.values.sortedByDescending { it.updatedAt }
    override suspend fun vouchersWithPending() = vouchers.values.filter { it.pendingTxHash != null }
    override fun vouchersFlow() = vf
    override suspend fun applyRedeems(vouchers: List<VoucherEntity>, seen: List<JournalSeenEntity>) {
        check(vouchers.all { it.voucherId in this.vouchers }) { "voucher row missing" }; check(seen.none { it.entryHash in this.seen })
        vouchers.forEach { this.vouchers[it.voucherId] = it.norm() }; seen.forEach { this.seen[it.entryHash] = it }; emit()
    }
    override suspend fun seenForVoucher(id: String) = seen.values.filter { it.voucherId == id }
    override suspend fun seen(hash: String) = seen[hash]
    override suspend fun insertSeen(e: JournalSeenEntity) { check(e.entryHash !in seen); seen[e.entryHash] = e }
    override suspend fun insertStaffKey(k: StaffKeyEntity) { check(k.keyId !in keys); keys[k.keyId] = k.norm(); emit() }
    override suspend fun updateStaffKey(k: StaffKeyEntity) { check(k.keyId in keys) { "staff key row missing" }; keys[k.keyId] = k.norm(); emit() }
    override suspend fun staffKey(id: String) = keys[id]
    override suspend fun activeStaffKeys() = keys.values.filter { it.revokedAt == null }.sortedBy { it.enrolledAt }
    override fun staffKeysFlow() = kf
}
