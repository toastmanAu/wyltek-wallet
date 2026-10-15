package com.wyltek.wallet.prepaid

import com.wyltek.wallet.prepaid.db.*
import kotlinx.coroutines.CancellationException

/** Vouchers spec §5.1. A staff key is any genuine NXP NTAG21x tag, identified by (UID, originality signature).
 *  Nothing is written to the tag. The ECC check happens here, once; terminals get only key_id (GET /v1/prepaid/staff)
 *  and recompute it from (uid, READ_SIG) on tap. */
class StaffKeyService(
    private val store: PrepaidStore,
    private val vouchers: VoucherStore,
    private val protocol: Protocol,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newActionId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val verifySig: (ByteArray, ByteArray) -> Boolean = Secp128r1::verifyNtagOriginality,
) {
    companion object { const val NOT_GENUINE = "not a genuine NXP tag"; const val MAX_ACTIVE = 16 }

    private suspend fun audit(kind: String, uid: String, keyId: String, detail: String) = store.event(PrepaidEventEntity(atMs = clock(),
        actionId = newActionId(), kind = kind, phase = PHASE_DONE, reserveId = keyId, uid = uid, amountUnits = 0, balanceBefore = 0,
        balanceAfter = 0, counterBefore = 0, counterAfter = 0, txHash = null, detail = detail))

    suspend fun enrol(tag: TagPort, label: String): ActionOutcome {
        if (label.isBlank()) return ActionOutcome.Failed("enter a label")
        val sig = try { tag.readSignature() } catch (e: CancellationException) { throw e } catch (e: Exception) { return ActionOutcome.NeedsTag(tag.uidHex) }
        if (!verifySig(hexBytes(tag.uidHex), sig)) return ActionOutcome.Failed(NOT_GENUINE)
        val uid = tag.uidHex.lowercase()
        val keyId = protocol.staffKeyId(uid, bytesHex(sig))
        val existing = vouchers.staffKey(keyId)
        if (existing?.revokedAt == null && existing != null) return ActionOutcome.Failed("this tag is already a staff key (${existing.label})")
        if (vouchers.activeStaffKeys().size >= MAX_ACTIVE) return ActionOutcome.Failed("limit of $MAX_ACTIVE staff keys reached")
        val row = StaffKeyEntity(keyId, uid, bytesHex(sig), label.trim(), clock(), null)
        if (existing != null) vouchers.updateStaffKey(row) else vouchers.insertStaffKey(row)
        audit(KIND_STAFF_ENROL, row.uid, keyId, "enrolled \"${row.label}\"")
        return ActionOutcome.Done
    }

    suspend fun revoke(keyId: String): ActionOutcome {
        val k = vouchers.staffKey(keyId)?.takeIf { it.revokedAt == null } ?: return ActionOutcome.Failed("no active staff key $keyId")
        vouchers.updateStaffKey(k.copy(revokedAt = clock()))
        audit(KIND_STAFF_REVOKE, k.uid, keyId, "revoked \"${k.label}\"")
        return ActionOutcome.Done
    }

    suspend fun active(): List<StaffKeyEntity> = vouchers.activeStaffKeys()
}

/** Final review LOW-5: an enrol arm (label chosen, biometric passed) lasts [TTL_MS]; a tag presented later is not enrolled. */
data class EnrolArm(val label: String, val armedAtMs: Long) {
    fun liveAt(nowMs: Long): Boolean = nowMs - armedAtMs < TTL_MS
    companion object {
        const val TTL_MS = 60_000L
        const val EXPIRED = "Enrol timed out after 60 s; tap Enrol again"
    }
}
