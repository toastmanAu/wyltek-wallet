package com.wyltek.wallet.prepaid

import com.wyltek.wallet.agent.db.AgentDatabase
import com.wyltek.wallet.prepaid.db.*
import kotlinx.coroutines.flow.Flow

interface VoucherStore {
    suspend fun insertVoucher(v: VoucherEntity)
    suspend fun updateVoucher(v: VoucherEntity)
    suspend fun deleteIssuing(voucherId: String): Int
    suspend fun voucher(id: String): VoucherEntity?
    /** The newest voucher row for this tag (a reissued tag keeps its older, closed rows). */
    suspend fun voucherByUid(uid: String): VoucherEntity?
    suspend fun allVouchers(): List<VoucherEntity>
    suspend fun vouchersWithPending(): List<VoucherEntity>
    fun vouchersFlow(): Flow<List<VoucherEntity>>
    /** One transaction. */
    suspend fun applyRedeems(vouchers: List<VoucherEntity>, seen: List<JournalSeenEntity>)
    suspend fun seenForVoucher(id: String): List<JournalSeenEntity>
    suspend fun seen(hash: String): JournalSeenEntity?
    suspend fun insertSeen(e: JournalSeenEntity)
    suspend fun insertStaffKey(k: StaffKeyEntity)
    suspend fun updateStaffKey(k: StaffKeyEntity)
    suspend fun staffKey(id: String): StaffKeyEntity?
    suspend fun activeStaffKeys(): List<StaffKeyEntity>
    fun staffKeysFlow(): Flow<List<StaffKeyEntity>>
}

class RoomVoucherStore(db: AgentDatabase) : VoucherStore {
    private val dao = db.voucherDao()
    override suspend fun insertVoucher(v: VoucherEntity) = dao.insertVoucher(v)
    override suspend fun updateVoucher(v: VoucherEntity) = dao.updateVoucher(v)
    override suspend fun deleteIssuing(voucherId: String) = dao.deleteIssuing(voucherId)
    override suspend fun voucher(id: String) = dao.voucher(id)
    override suspend fun voucherByUid(uid: String) = dao.voucherByUid(uid)
    override suspend fun allVouchers() = dao.allVouchers()
    override suspend fun vouchersWithPending() = dao.vouchersWithPending()
    override fun vouchersFlow() = dao.vouchersFlow()
    override suspend fun applyRedeems(vouchers: List<VoucherEntity>, seen: List<JournalSeenEntity>) = dao.applyRedeems(vouchers, seen)
    override suspend fun seenForVoucher(id: String) = dao.seenForVoucher(id)
    override suspend fun seen(hash: String) = dao.seen(hash)
    override suspend fun insertSeen(e: JournalSeenEntity) = dao.insertSeen(e)
    override suspend fun insertStaffKey(k: StaffKeyEntity) = dao.insertStaffKey(k)
    override suspend fun updateStaffKey(k: StaffKeyEntity) = dao.updateStaffKey(k)
    override suspend fun staffKey(id: String) = dao.staffKey(id)
    override suspend fun activeStaffKeys() = dao.activeStaffKeys()
    override fun staffKeysFlow() = dao.staffKeysFlow()
}
