package com.wyltek.wallet.data

/**
 * Cell-selection predicates shared by every spend path.
 *
 * The ML-DSA-65 (v2-rust) lock signs a CighashAll digest that streams each
 * input's full `CellOutput` *and* its output data (mirrors `streamer.rs`
 * on-chain). The Kotlin builders reconstruct that digest from an
 * `MldsaInputCell` list. Any path that hands the signer a placeholder
 * "type-less, empty-data" input while the real on-chain cell carries a type
 * script or data produces a digest that diverges from the lock's → verify
 * fails with lock error 46 (`SignatureVerifyFailed`) and the pool rejects the
 * tx. Classic secp is immune (its sighash commits only the tx hash +
 * witnesses, never resolved input-cell data).
 *
 * Plain CKB / profile / message sends therefore restrict capacity inputs to
 * *pure-CKB* cells — no type script, no output data — so the placeholder
 * `MldsaInputCell` they emit is byte-for-byte what the lock reconstructs. This
 * also stops a plain send from silently consuming (burning) an sUDT cell or
 * destroying a CEMP notification cell as fee capacity.
 */
object CellSelection {

    /**
     * True when a cell can fund capacity for a pure-CKB spend: it carries no
     * type script and no output data.
     *
     * Live empty cells report `data` as the string `"0x"` (not `null`/`""`),
     * so the prefix is stripped before the emptiness check — otherwise every
     * genuine pure cell would be rejected and the send would fail with a
     * spurious "insufficient CKB".
     *
     * @param hasType whether the cell has a type script (`utxo.type_ != null`).
     * @param data    the cell's output data hex (`utxo.data`), may be null.
     */
    fun isPureCkb(hasType: Boolean, data: String?): Boolean =
        !hasType && (data?.removePrefix("0x")?.isEmpty() ?: true)
}
