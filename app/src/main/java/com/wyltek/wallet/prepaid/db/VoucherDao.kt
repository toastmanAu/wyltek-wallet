package com.wyltek.wallet.prepaid.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class VoucherDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertVoucher(v: VoucherEntity)
    @Update abstract suspend fun updateVoucher(v: VoucherEntity)
    @Update abstract suspend fun updateVouchers(v: List<VoucherEntity>)
    @Query("DELETE FROM prepaid_vouchers WHERE voucherId = :id AND state = 'issuing'") abstract suspend fun deleteIssuing(id: String): Int
    @Query("SELECT * FROM prepaid_vouchers WHERE voucherId = :id LIMIT 1") abstract suspend fun voucher(id: String): VoucherEntity?
    @Query("SELECT * FROM prepaid_vouchers WHERE uid = :uid ORDER BY issuedAt DESC LIMIT 1") abstract suspend fun voucherByUid(uid: String): VoucherEntity?
    @Query("SELECT * FROM prepaid_vouchers ORDER BY updatedAt DESC") abstract suspend fun allVouchers(): List<VoucherEntity>
    @Query("SELECT * FROM prepaid_vouchers WHERE pendingTxHash IS NOT NULL") abstract suspend fun vouchersWithPending(): List<VoucherEntity>
    @Query("SELECT * FROM prepaid_vouchers ORDER BY updatedAt DESC") abstract fun vouchersFlow(): Flow<List<VoucherEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertSeen(e: JournalSeenEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertSeenAll(e: List<JournalSeenEntity>)
    @Query("SELECT * FROM prepaid_journal_seen WHERE voucherId = :id") abstract suspend fun seenForVoucher(id: String): List<JournalSeenEntity>
    @Query("SELECT * FROM prepaid_journal_seen WHERE entryHash = :hash LIMIT 1") abstract suspend fun seen(hash: String): JournalSeenEntity?

    /** Counts and settled entries land together or not at all. */
    @Transaction open suspend fun applyRedeems(vouchers: List<VoucherEntity>, seen: List<JournalSeenEntity>) { updateVouchers(vouchers); insertSeenAll(seen) }

    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertStaffKey(k: StaffKeyEntity)
    @Update abstract suspend fun updateStaffKey(k: StaffKeyEntity)
    @Query("SELECT * FROM prepaid_staff_keys WHERE keyId = :id LIMIT 1") abstract suspend fun staffKey(id: String): StaffKeyEntity?
    @Query("SELECT * FROM prepaid_staff_keys WHERE revokedAt IS NULL ORDER BY enrolledAt ASC") abstract suspend fun activeStaffKeys(): List<StaffKeyEntity>
    @Query("SELECT * FROM prepaid_staff_keys ORDER BY enrolledAt DESC") abstract fun staffKeysFlow(): Flow<List<StaffKeyEntity>>
}
