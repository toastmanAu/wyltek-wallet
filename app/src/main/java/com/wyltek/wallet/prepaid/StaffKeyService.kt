package com.wyltek.wallet.prepaid

import com.wyltek.wallet.prepaid.db.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Vouchers spec §5.1. A staff key is any genuine NXP NTAG21x tag, identified by (UID, originality signature).
 *  Nothing is written to the tag. The ECC check happens here, once; terminals get key_id, pin_ver and pin_check
 *  (GET /v1/prepaid/staff), recompute key_id from (uid, READ_SIG) on tap and check the PIN against pin_check. */
class StaffKeyService(
    private val store: PrepaidStore,
    private val vouchers: VoucherStore,
    private val protocol: Protocol,
    private val kHex: () -> String,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newActionId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val verifySig: (ByteArray, ByteArray) -> Boolean = Secp128r1::verifyNtagOriginality,
) {
    companion object {
        const val NOT_GENUINE = "not a genuine NXP tag"; const val MAX_ACTIVE = 16
        const val PIN_RULE = "PIN must be 4 to 8 digits"
        const val PIN_VER_EXHAUSTED = "this tag's PIN version is used up; enrol a different tag"
        private const val PIN_VER_MAX = 65535
        /** null when [pin] is 4 to 8 ASCII digits. ASCII only: isDigit() would accept Unicode digits. */
        fun pinError(pin: String): String? = if (pin.length in 4..8 && pin.all { it in '0'..'9' }) null else PIN_RULE
        /** enrol, setPin and revoke read a row and write the whole row back; serialise them so one cannot overwrite
         *  another's write (a setPin racing a revoke would write revokedAt = null back). Process-wide because AgentGateway
         *  builds a new StaffKeyService per access (security review F2). */
        private val lock = Mutex()
    }

    private suspend fun audit(kind: String, uid: String, keyId: String, detail: String) = store.event(PrepaidEventEntity(atMs = clock(),
        actionId = newActionId(), kind = kind, phase = PHASE_DONE, reserveId = keyId, uid = uid, amountUnits = 0, balanceBefore = 0,
        balanceAfter = 0, counterBefore = 0, counterAfter = 0, txHash = null, detail = detail))

    suspend fun enrol(tag: TagPort, label: String, pin: String): ActionOutcome = lock.withLock {
        if (label.isBlank()) return ActionOutcome.Failed("enter a label")
        if (pinError(pin) != null) return ActionOutcome.Failed(PIN_RULE)
        val sig = try { tag.readSignature() } catch (e: CancellationException) { throw e } catch (e: Exception) { return ActionOutcome.NeedsTag(tag.uidHex) }
        if (!verifySig(hexBytes(tag.uidHex), sig)) return ActionOutcome.Failed(NOT_GENUINE)
        val uid = tag.uidHex.lowercase()
        val keyId = protocol.staffKeyId(uid, bytesHex(sig))
        val existing = vouchers.staffKey(keyId)
        if (existing?.revokedAt == null && existing != null) return ActionOutcome.Failed("this tag is already a staff key (${existing.label})")
        if (vouchers.activeStaffKeys().size >= MAX_ACTIVE) return ActionOutcome.Failed("limit of $MAX_ACTIVE staff keys reached")
        val pinVer = (existing?.pinVer ?: 0) + 1
        if (pinVer > PIN_VER_MAX) return ActionOutcome.Failed(PIN_VER_EXHAUSTED)
        val row = StaffKeyEntity(keyId, uid, bytesHex(sig), label.trim(), clock(), null).withPin(pinVer, pin)
        if (existing != null) vouchers.updateStaffKey(row) else vouchers.insertStaffKey(row)
        audit(KIND_STAFF_ENROL, row.uid, keyId, "enrolled \"${row.label}\"")
        audit(KIND_STAFF_PIN_SET, row.uid, keyId, "pin_ver $pinVer")
        ActionOutcome.Done
    }

    suspend fun setPin(keyId: String, pin: String): ActionOutcome = lock.withLock {
        val k = vouchers.staffKey(keyId)?.takeIf { it.revokedAt == null } ?: return ActionOutcome.Failed("no active staff key $keyId")
        if (pinError(pin) != null) return ActionOutcome.Failed(PIN_RULE)
        val pinVer = k.pinVer + 1
        if (pinVer > PIN_VER_MAX) return ActionOutcome.Failed(PIN_VER_EXHAUSTED)
        vouchers.updateStaffKey(k.withPin(pinVer, pin))
        audit(KIND_STAFF_PIN_SET, k.uid, keyId, "pin_ver $pinVer")
        ActionOutcome.Done
    }

    /** The only place [pin] is used: the argument to the derivation. It is never stored, logged or put in a message. */
    private fun StaffKeyEntity.withPin(pinVer: Int, pin: String) =
        copy(pinVer = pinVer, pinCheckHex = protocol.staffPinCheck(kHex(), keyId, pinVer, pin), pinSetAt = clock())

    suspend fun revoke(keyId: String): ActionOutcome = lock.withLock {
        val k = vouchers.staffKey(keyId)?.takeIf { it.revokedAt == null } ?: return ActionOutcome.Failed("no active staff key $keyId")
        vouchers.updateStaffKey(k.copy(revokedAt = clock()))
        audit(KIND_STAFF_REVOKE, k.uid, keyId, "revoked \"${k.label}\"")
        ActionOutcome.Done
    }

    suspend fun active(): List<StaffKeyEntity> = vouchers.activeStaffKeys()
}

/** Final review LOW-5: an enrol arm (label chosen, biometric passed) lasts [TTL_MS]; a tag presented later is not enrolled. */
data class EnrolArm(val label: String, val pin: String, val armedAtMs: Long) {
    // The arm is the only holder of the PIN and lives 60 s. copy()/componentN() expose it by design; toString must not.
    override fun toString() = "EnrolArm(label=\"$label\", pin=****, armedAtMs=$armedAtMs)"
    fun liveAt(nowMs: Long): Boolean = nowMs - armedAtMs < TTL_MS
    companion object {
        const val TTL_MS = 60_000L
        const val EXPIRED = "Enrol timed out after 60 s; tap Enrol again"
    }
}
