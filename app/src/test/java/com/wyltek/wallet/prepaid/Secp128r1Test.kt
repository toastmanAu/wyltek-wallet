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

    private val n = java.math.BigInteger("FFFFFFFE0000000075A30D1B9038A115", 16)
    private fun b16(x: java.math.BigInteger): ByteArray {
        val raw = x.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        require(raw.size <= 16); return ByteArray(16 - raw.size) + raw
    }
    private fun check(r: ByteArray, s: ByteArray) = Secp128r1.verify(pub, hexBytes(cases[0].first), r + s)

    @Test fun scalar_range_guard_each_disjunct() {
        val sig = hexBytes(cases[0].second); val r = sig.copyOfRange(0, 16); val s = sig.copyOfRange(16, 32)
        assertTrue(check(r, s))
        assertFalse("s=0", check(r, ByteArray(16)))
        assertFalse("r=0", check(ByteArray(16), s))
        assertFalse("r=N", check(b16(n), s))
        assertFalse("s=N", check(r, b16(n)))
        assertFalse("r=N+1", check(b16(n.add(java.math.BigInteger.ONE)), s))
        assertFalse("s=N+1", check(r, b16(n.add(java.math.BigInteger.ONE))))
        assertFalse("r=2^128-1", check(ByteArray(16) { -1 }, s))
        assertFalse("s=2^128-1", check(r, ByteArray(16) { -1 }))
        // malleability: s+N is the same scalar mod N; only encodable in 16 bytes when s < 2^128 - N
        val sBig = java.math.BigInteger(1, s)
        if (sBig.add(n).bitLength() <= 128) assertFalse("s+N", check(r, b16(sBig.add(n))))
    }
    @Test fun off_curve_public_point_is_refused() {
        val (uid, sig) = cases[0]
        assertFalse(Secp128r1.verify(Secp128r1.Pt(pub.x, pub.y.add(java.math.BigInteger.ONE)), hexBytes(uid), hexBytes(sig)))
    }
    @Test fun hex_helpers() {
        val b = byteArrayOf(0, 1, -1, 0x7f, -128)
        assertEquals("0001ff7f80", bytesHex(b))
        assertArrayEquals(b, hexBytes("0001ff7f80"))
        assertArrayEquals(b, hexBytes("0x0001FF7F80"))
        assertArrayEquals(b, hexBytes("0X0001ff7f80"))
        assertEquals(0, hexBytes("").size)
        assertThrows(IllegalArgumentException::class.java) { hexBytes("abc") }
        assertThrows(IllegalArgumentException::class.java) { hexBytes("zz") }
        assertThrows(IllegalArgumentException::class.java) { hexBytes("0x0g") }
    }
}
