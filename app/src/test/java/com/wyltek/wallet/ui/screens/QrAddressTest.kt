package com.wyltek.wallet.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrAddressTest {
    private val testnet = "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqwgx292hnvmn68xf779vmzrshpmm6epn4c0cgwga"
    private val mainnet = "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqwgx292hnvmn68xf779vmzrshpmm6epn4c0cgwga"

    @Test fun `bare address passes through`() {
        assertEquals(testnet, QrAddress.extract(testnet))
        assertEquals(mainnet, QrAddress.extract(" $mainnet\n"))
    }
    @Test fun `ckb uri and links carrying an address are unwrapped`() {
        assertEquals(testnet, QrAddress.extract("ckb:$testnet"))
        assertEquals(testnet, QrAddress.extract("ckb:$testnet?amount=10"))
        assertEquals(testnet, QrAddress.extract("https://app.joy.id/receive?address=$testnet&x=1"))
        assertEquals(testnet, QrAddress.extract("Send to $testnet please"))
    }
    @Test fun `uppercase bech32 is lowercased`() {
        assertEquals(testnet, QrAddress.extract(testnet.uppercase()))
    }
    @Test fun `text without an address yields null`() {
        assertNull(QrAddress.extract(""))
        assertNull(QrAddress.extract("https://example.com"))
        assertNull(QrAddress.extract("ckt1"))
        assertNull(QrAddress.extract("ckt1qzda0cr08m85hc8jl"))   // too short to be an address
    }
}
