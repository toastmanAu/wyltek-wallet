package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.chain.NetworkConfig
import com.wyltek.wallet.core.model.CkbAddress
import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.NetworkType
import com.wyltek.wallet.core.model.Utxo
import com.wyltek.wallet.core.native.*

/** The classic secp256k1_blake160 path `sendCkb` uses, for one address. */
class SecpSigner(seedHex: String, address: CkbAddress, network: NetworkType) : SignerPort {
    private val keyPair = generateSecp256k1Keypair(seedHex, "m/44'/302'/0'/0/0")
    private val cfg = NetworkConfig.forNetwork(network)
    override val lock: LockScript = address.lockScript
    override val cellDep = CellDepRef(cfg.secp256k1DepGroupTxHash, cfg.secp256k1DepGroupIndex, "dep_group")
    override fun build(inputs: List<Utxo>, outputs: List<TxOutput>): BuiltTransaction = buildTransaction(TransactionRequest(
        inputs = inputs.map { TxInput(it.outPoint.txHash, it.outPoint.index, 0uL, it.capacity) },
        outputs = outputs,
        cellDeps = listOf(TxCellDep(cfg.secp256k1DepGroupTxHash, cfg.secp256k1DepGroupIndex, 1.toUByte())),
        feeRate = 1000uL))
    override fun witness0(txHashHex: String, numInputs: Int): String = "0x" + signCkbSecp256k1Witness(txHashHex, numInputs.toUInt(), keyPair.privateKeyHex)
}
