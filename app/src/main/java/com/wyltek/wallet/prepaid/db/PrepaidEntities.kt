package com.wyltek.wallet.prepaid.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val CARD_ISSUING = "issuing"
const val CARD_ACTIVE = "active"
const val CARD_REVOKED = "revoked"
const val CARD_CLOSED = "closed"

const val KIND_ISSUE = "issue"; const val KIND_LOAD = "load"; const val KIND_ADOPT = "adopt"; const val KIND_REPAIR = "repair"
const val KIND_REVOKE = "revoke"; const val KIND_REINSTATE = "reinstate"; const val KIND_VOID = "void"
const val KIND_CASH_OUT = "cash_out"; const val KIND_SETTLE = "settle"; const val KIND_KEY_GENERATED = "key_generated"

const val PHASE_REQUESTED = "requested"; const val PHASE_BROADCAST = "broadcast"; const val PHASE_COMMITTED = "committed"
const val PHASE_WRITTEN = "written"; const val PHASE_DONE = "done"; const val PHASE_FAILED = "failed"

/** The card index (D3 spec §3.1). `pendingTxHash` non-null = a transaction of ours is unconfirmed, or
 *  confirmed with `pendingUnits` still unwritten to the tag; `pendingActionId` groups its audit rows. */
@Entity(tableName = "prepaid_cards", indices = [Index(value = ["uid"], unique = true)])
data class PrepaidCardEntity(
    @PrimaryKey val reserveId: String,
    val uid: String,
    val label: String? = null,
    val firstOutpoint: String,
    val currentOutpoint: String,
    val balanceUnits: Int,
    val counter: Long,
    val settledCounter: Long,
    val state: String,
    val pendingUnits: Int? = null,
    val pendingTxHash: String? = null,
    val pendingActionId: String? = null,
    val pendingCommitted: Boolean = false,
    val issuedAt: Long,
    val updatedAt: Long,
)

/** Append-only audit log (D3 spec §3.2). One row per phase of one merchant action. */
@Entity(tableName = "prepaid_events", indices = [Index(value = ["actionId"]), Index(value = ["reserveId"])])
data class PrepaidEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMs: Long,
    val actionId: String,
    val kind: String,
    val phase: String,
    val reserveId: String?,
    val uid: String,
    val amountUnits: Long,
    val balanceBefore: Int,
    val balanceAfter: Int,
    val counterBefore: Long,
    val counterAfter: Long,
    val txHash: String?,
    val detail: String,
)
