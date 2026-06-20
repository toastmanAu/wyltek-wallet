package com.wyltek.wallet.agent.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val STATUS_PENDING = "pending"
const val STATUS_CONFIRMED = "confirmed"

@Entity(
    tableName = "spend_records",
    indices = [Index(value = ["tokenId", "nonce"], unique = true), Index(value = ["tokenId", "asset"])]
)
data class SpendRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tokenId: String,
    val asset: String,
    val amount: Long,
    val unix: Long,
    val nonce: String,
    val status: String = STATUS_PENDING,
    val txHash: String? = null
)

@Entity(tableName = "token_registry")
data class TokenRegistryEntity(
    @PrimaryKey val tokenId: String,
    val token: String,
    val account: String,
    val revoked: Boolean = false,
    val createdAt: Long
)

const val PENDING_APPROVAL = "pending_approval"
const val PENDING_SENT = "sent"
const val PENDING_DENIED = "denied"
const val PENDING_FAILED = "failed"

@Entity(tableName = "pending_intents")
data class PendingIntentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val token: String,
    val op: String,
    val asset: String,
    val to: String,
    val amount: Long,
    val nonce: String,
    val action: String? = null,
    val daoRef: String? = null,
    val sourceIp: String,
    val status: String = PENDING_APPROVAL,
    val createdAt: Long,
    val resultTxHash: String? = null,
    val resultError: String? = null
)
