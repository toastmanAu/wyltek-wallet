package com.wyltek.wallet.core.assets

import kotlinx.serialization.Serializable

@Serializable
data class CustomTokenDefinition(
    val codeHash: String,
    val hashType: String,
    val args: String,
    val symbol: String
)
