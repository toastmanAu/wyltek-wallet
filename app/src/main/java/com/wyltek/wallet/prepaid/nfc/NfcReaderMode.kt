package com.wyltek.wallet.prepaid.nfc

import android.app.Activity
import android.hardware.camera2.CameraManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/** After a camera frees up, wait this long before re-asserting reader mode (the system's NFC hold is released after it). */
private const val CAMERA_RELEASE_SETTLE_MS = 500L

/**
 * Which screen currently owns the activity's reader mode. Reader mode belongs to the activity, and navigation disposes
 * the old screen AFTER the new one has enabled (device checkpoint 2026-10-16: Prepaid → Staff keys left the reader off,
 * so Android's own tag screen took the tap). Only the latest claimant may disable or re-enable. Main thread only.
 */
class ReaderModeOwner {
    private var holder: Any? = null
    fun claim(who: Any) { holder = who }
    fun holds(who: Any): Boolean = holder === who
    /** True when [who] held it and should now disable reader mode; false when another screen has since claimed it. */
    fun release(who: Any): Boolean = (holder === who).also { if (it) holder = null }
}

private val readerModeOwner = ReaderModeOwner()

/**
 * Reader mode while [enabled] (D3 spec §5): NfcA only, NDEF check skipped, no platform sound. Disabled on dispose
 * unless a newer screen has claimed it ([ReaderModeOwner]).
 *
 * The system suspends NFC polling while a camera is open. Coming back from the QR scanner, our enable can land while
 * the camera is still closing ("active polling is forced to disable now"), and the system's release then leaves the
 * reader dead. So reader mode is re-asserted each time a camera becomes available again.
 */
@Composable
fun NfcReaderMode(enabled: Boolean, onTag: (Tag) -> Unit) {
    val activity = LocalContext.current as? Activity ?: return
    val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
    val latest by rememberUpdatedState(onTag)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val me = Any()
        readerModeOwner.claim(me)
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
        val enable = Runnable {
            if (!readerModeOwner.holds(me)) return@Runnable   // a newer screen owns the reader; don't install our handler
            try { adapter.enableReaderMode(activity, { tag -> latest(tag) }, flags, extras) }
            catch (e: IllegalStateException) { Log.w("NfcReaderMode", "reader mode not enabled: ${e.message}") }   // not in the foreground
        }
        enable.run()
        val handler = Handler(Looper.getMainLooper())
        val cameras = activity.getSystemService(CameraManager::class.java)
        val onCameraFree = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                handler.removeCallbacks(enable); handler.postDelayed(enable, CAMERA_RELEASE_SETTLE_MS)
            }
        }
        cameras?.registerAvailabilityCallback(onCameraFree, handler)
        onDispose {
            cameras?.unregisterAvailabilityCallback(onCameraFree)
            handler.removeCallbacks(enable)
            if (readerModeOwner.release(me)) adapter.disableReaderMode(activity)
        }
    }
}

fun hasNfc(activity: Activity): Boolean = NfcAdapter.getDefaultAdapter(activity) != null
