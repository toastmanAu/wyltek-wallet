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

/** Tag UIDs are lowercase hex everywhere in the app; Room compares case-sensitively, so normalise at the boundary. */
internal fun VoucherEntity.norm() = copy(uid = uid.lowercase())
internal fun StaffKeyEntity.norm() = copy(uid = uid.lowercase())

class RoomVoucherStore(db: AgentDatabase) : VoucherStore {
    private val dao = db.voucherDao()
    override suspend fun insertVoucher(v: VoucherEntity) = dao.insertVoucher(v.norm())
    override suspend fun updateVoucher(v: VoucherEntity) { check(dao.updateVoucher(v.norm()) == 1) { "voucher row missing" } }
    override suspend fun deleteIssuing(voucherId: String) = dao.deleteIssuing(voucherId)
    override suspend fun voucher(id: String) = dao.voucher(id)
    override suspend fun voucherByUid(uid: String) = dao.voucherByUid(uid.lowercase())
    override suspend fun allVouchers() = dao.allVouchers()
    override suspend fun vouchersWithPending() = dao.vouchersWithPending()
    override fun vouchersFlow() = dao.vouchersFlow()
    override suspend fun applyRedeems(vouchers: List<VoucherEntity>, seen: List<JournalSeenEntity>) = dao.applyRedeems(vouchers.map { it.norm() }, seen)
    override suspend fun seenForVoucher(id: String) = dao.seenForVoucher(id)
    override suspend fun seen(hash: String) = dao.seen(hash)
    override suspend fun insertSeen(e: JournalSeenEntity) = dao.insertSeen(e)
    override suspend fun insertStaffKey(k: StaffKeyEntity) = dao.insertStaffKey(k.norm())
    override suspend fun updateStaffKey(k: StaffKeyEntity) { check(dao.updateStaffKey(k.norm()) == 1) { "staff key row missing" } }
    override suspend fun staffKey(id: String) = dao.staffKey(id)
    override suspend fun activeStaffKeys() = dao.activeStaffKeys()
    override fun staffKeysFlow() = dao.staffKeysFlow()
}
