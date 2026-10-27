package com.wyltek.wallet.prepaid.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val VOUCHER_ISSUING = "issuing"; const val VOUCHER_ACTIVE = "active"; const val VOUCHER_USED = "used"
const val VOUCHER_CLOSED = "closed"; const val VOUCHER_VOID = "void"
/** States whose cell is still live and whose tag the Vault still stands behind. */
val VOUCHER_LIVE = setOf(VOUCHER_ISSUING, VOUCHER_ACTIVE, VOUCHER_USED)

/** Vouchers spec §3, §8. `voucherId` = 32 hex (16 bytes); `outpoint` = the voucher cell. `countLeft` counts down
 *  per accepted redeem (the Vault's own count; a terminal's `balance_after` is not trusted). */
@Entity(tableName = "prepaid_vouchers", indices = [Index(value = ["uid"])])
data class VoucherEntity(
    @PrimaryKey val voucherId: String,
    val uid: String,
    val productCode: String,
    val productName: String,
    val productHash: String,
    val issuePriceMinor: Long,
    val currency: String,
    val countIssued: Int,
    val countLeft: Int,
    val outpoint: String,
    val state: String,
    val tagCounter: Long = 0,
    val pendingKind: String? = null,
    val pendingTxHash: String? = null,
    val pendingActionId: String? = null,
    val pendingCommitted: Boolean = false,
    val pendingSince: Long? = null,
    val issuedAt: Long,
    val updatedAt: Long,
)

/** Spec §5.1. Nothing is written to the tag; `sigHex` is its NXP originality signature. */
@Entity(tableName = "prepaid_staff_keys")
data class StaffKeyEntity(
    @PrimaryKey val keyId: String,
    val uid: String,
    val sigHex: String,
    val label: String,
    val enrolledAt: Long,
    val revokedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val pinVer: Int = 0,
    val pinCheckHex: String? = null,
    val pinSetAt: Long? = null,
)

/** Every settled voucher-redeem, price-override and staff-lockout entry, by blake2b of its 96 bytes: the settled set the
 *  verifier needs for `duplicate` vs `double_redeem`, and the dedupe for override and lockout audit rows. */
@Entity(tableName = "prepaid_journal_seen", indices = [Index(value = ["voucherId"])])
data class JournalSeenEntity(
    @PrimaryKey val entryHash: String,
    val kind: String,
    val voucherId: String?,
    val counter: Long?,
    val terminalId: Int,
    val seenAt: Long,
)
