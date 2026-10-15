package com.wyltek.wallet.prepaid

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class Secp128r1Test {
    private val v = Json.parseToJsonElement(javaClass.getResource("/secp128r1/vectors.json")!!.readText()).jsonObject
    private val pub = Secp128r1.pointOf(hexBytes(v["pub"]!!.jsonPrimitive.content))!!
    private val cases = v["cases"]!!.jsonArray.map { it.jsonObject["uid"]!!.jsonPrimitive.content to it.jsonObject["sig"]!!.jsonPrimitive.content }

    @Test fun python_ecdsa_signatures_verify() {
        for ((uid, sig) in cases) assertTrue(uid, Secp128r1.verify(pub, hexBytes(uid), hexBytes(sig)))
    }
    @Test fun any_flipped_byte_or_wrong_uid_fails() {
        val (uid, sig) = cases[0]
        for (i in 0 until 32) { val s = hexBytes(sig); s[i] = (s[i].toInt() xor 1).toByte(); assertFalse("sig byte $i", Secp128r1.verify(pub, hexBytes(uid), s)) }
        assertFalse(Secp128r1.verify(pub, hexBytes(cases[1].first), hexBytes(sig)))
        assertFalse(Secp128r1.verify(pub, hexBytes(uid), ByteArray(32)))
        assertFalse(Secp128r1.verify(pub, hexBytes(uid), ByteArray(31)))
    }
    @Test fun nxp_key_is_on_the_curve_and_rejects_these_test_signatures() {
        assertNotNull(Secp128r1.pointOf(hexBytes(Secp128r1.NXP_NTAG21X_PUBKEY)))
        for ((uid, sig) in cases) assertFalse(Secp128r1.verifyNtagOriginality(hexBytes(uid), hexBytes(sig)))
        assertFalse(Secp128r1.verifyNtagOriginality(ByteArray(4), ByteArray(32)))
    }
    @Test fun points_off_the_curve_are_refused() {
        val bad = hexBytes(Secp128r1.NXP_NTAG21X_PUBKEY); bad[32] = (bad[32].toInt() xor 1).toByte()
        assertNull(Secp128r1.pointOf(bad))
        assertNull(Secp128r1.pointOf(ByteArray(33)))
    }
}
