package com.wyltek.wallet.prepaid

import java.math.BigInteger

/**
 * Verify-only ECDSA on secp128r1 (SEC 2), for NXP NTAG21x originality signatures (AN11350, `READ_SIG`).
 * The signed message is the 7-byte UID, unhashed. Inputs are public, so constant time is not a goal.
 * The wallet ships no library with this curve (BouncyCastle is not a dependency).
 */
object Secp128r1 {
    private val P = BigInteger("FFFFFFFDFFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val A = BigInteger("FFFFFFFDFFFFFFFFFFFFFFFFFFFFFFFC", 16)
    private val B = BigInteger("E87579C11079F43DD824993C2CEE5ED3", 16)
    private val N = BigInteger("FFFFFFFE0000000075A30D1B9038A115", 16)
    private val G = Pt(BigInteger("161FF7528B899B2D0C28607CA52C5B86", 16), BigInteger("CF5AC8395BAFEB13C02DA292DDED7A83", 16))

    /** NXP NTAG21x originality key (AN11350), 04 ‖ X ‖ Y. */
    const val NXP_NTAG21X_PUBKEY = "04494E1A386D3D3CFE3DC10E5DE68A499B1C202DB5B132393E89ED19FE5BE8BC61"

    data class Pt(val x: BigInteger, val y: BigInteger)

    private fun onCurve(p: Pt) = p.y.pow(2).subtract(p.x.pow(3).add(A.multiply(p.x)).add(B)).mod(P).signum() == 0

    /** Null = point at infinity. */
    private fun add(p: Pt?, q: Pt?): Pt? {
        if (p == null) return q
        if (q == null) return p
        val l = if (p.x == q.x) {
            if (p.y.add(q.y).mod(P).signum() == 0) return null
            p.x.pow(2).multiply(BigInteger.valueOf(3)).add(A).multiply(p.y.shiftLeft(1).modInverse(P)).mod(P)
        } else q.y.subtract(p.y).multiply(q.x.subtract(p.x).mod(P).modInverse(P)).mod(P)
        val x = l.pow(2).subtract(p.x).subtract(q.x).mod(P)
        return Pt(x, l.multiply(p.x.subtract(x)).subtract(p.y).mod(P))
    }

    private fun mul(k: BigInteger, p: Pt): Pt? {
        var r: Pt? = null; var a: Pt? = p
        for (i in 0 until k.bitLength()) { if (k.testBit(i)) r = add(r, a); a = add(a, a) }
        return r
    }

    fun pointOf(uncompressed: ByteArray): Pt? {
        if (uncompressed.size != 33 || uncompressed[0] != 0x04.toByte()) return null
        val p = Pt(BigInteger(1, uncompressed.copyOfRange(1, 17)), BigInteger(1, uncompressed.copyOfRange(17, 33)))
        return p.takeIf { it.x < P && it.y < P && onCurve(it) }
    }

    /** [sig] = r(16) ‖ s(16). [msg] is used whole when shorter than the order (a 7-byte UID always is). */
    fun verify(pub: Pt, msg: ByteArray, sig: ByteArray): Boolean {
        if (sig.size != 32 || !onCurve(pub)) return false
        val r = BigInteger(1, sig.copyOfRange(0, 16)); val s = BigInteger(1, sig.copyOfRange(16, 32))
        if (r.signum() == 0 || s.signum() == 0 || r >= N || s >= N) return false
        val excess = msg.size * 8 - N.bitLength()
        val e = BigInteger(1, msg).let { if (excess > 0) it.shiftRight(excess) else it }
        val w = s.modInverse(N)
        val q = add(mul(e.multiply(w).mod(N), G), mul(r.multiply(w).mod(N), pub)) ?: return false
        return q.x.mod(N) == r
    }

    private val nxp: Pt by lazy { pointOf(hexBytes(NXP_NTAG21X_PUBKEY))!! }

    fun verifyNtagOriginality(uid: ByteArray, sig: ByteArray): Boolean = uid.size == 7 && verify(nxp, uid, sig)
}
