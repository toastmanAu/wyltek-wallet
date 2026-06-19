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
