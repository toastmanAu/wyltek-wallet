package com.wyltek.wallet.prepaid.fakes

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.Utxo
import com.wyltek.wallet.core.native.BuiltTransaction
import com.wyltek.wallet.core.native.TxOutput
import com.wyltek.wallet.prepaid.CellDepRef
import com.wyltek.wallet.prepaid.PrepaidTxPlanner
import com.wyltek.wallet.prepaid.SignerPort

/** Mirrors `SecpSigner` over rust-core EXCEPT the 0x normalisation, so the service's own boundary is exercised. */
class FakeSigner(override val lock: LockScript) : SignerPort {
    override val cellDep = CellDepRef("0x" + "cc".repeat(32), 0u, "dep_group")
    var builds = 0
    override fun build(inputs: List<Utxo>, outputs: List<TxOutput>): BuiltTransaction {
        builds++
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        inputs.forEach { digest.update(PrepaidTxPlanner.outpointKey(it).toByteArray()) }
        outputs.forEach { digest.update(it.capacity.toString().toByteArray()); digest.update(it.lockArgs.toByteArray()) }
        digest.update(builds.toString().toByteArray())
        // Node-faithful shape: rust-core's build_transaction returns the hash WITHOUT `0x` (C1).
        val h = digest.digest().joinToString("") { "%02x".format(it) }
        return BuiltTransaction(rawTransactionHex = "", txHashHex = h, estimatedFee = 0uL,
            totalInputCapacity = inputs.fold(0uL) { a, u -> a + u.capacity }, totalOutputCapacity = outputs.fold(0uL) { a, o -> a + o.capacity })
    }
    /** Throw from the next witness0 only: a failure AFTER the pending marker is written, before broadcast. */
    var throwWitnessOnce = false
    override fun witness0(txHashHex: String, numInputs: Int): String {
        if (throwWitnessOnce) { throwWitnessOnce = false; throw IllegalStateException("signer failed") }
        return "0x" + "77".repeat(85)
    }
}
