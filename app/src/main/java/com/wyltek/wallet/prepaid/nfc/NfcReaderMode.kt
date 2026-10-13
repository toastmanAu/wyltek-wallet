package com.wyltek.wallet.prepaid.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/** Reader mode while [enabled] (D3 spec §5): NfcA only, NDEF check skipped, no platform sound. Disabled on dispose. */
@Composable
fun NfcReaderMode(enabled: Boolean, onTag: (Tag) -> Unit) {
    val activity = LocalContext.current as? Activity ?: return
    val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
    val latest by rememberUpdatedState(onTag)
    DisposableEffect(enabled) {
        if (enabled) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
            val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
            adapter.enableReaderMode(activity, { tag -> latest(tag) }, flags, extras)
        }
        onDispose { adapter.disableReaderMode(activity) }
    }
}

fun hasNfc(activity: Activity): Boolean = NfcAdapter.getDefaultAdapter(activity) != null
