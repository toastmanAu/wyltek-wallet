package com.wyltek.wallet.ui.security

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Outcome of a biometric auth attempt. Callers care only about success vs.
 * "didn't happen" — error and cancel are separated so UI can distinguish a
 * deliberate dismissal (silent) from a real failure (toast / log).
 */
sealed class BiometricResult {
    data object Success : BiometricResult()
    data object Cancelled : BiometricResult()
    data class Error(val code: Int, val message: String) : BiometricResult()
    data object Unavailable : BiometricResult()
}

/**
 * Plain-Kotlin wrapper around BiometricPrompt. No Compose dependency so
 * non-UI code (background sync, signing services) can reuse it.
 */
class BiometricAuth(private val activity: FragmentActivity) {

    private val allowedAuthenticators: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // STRONG can only combine with DEVICE_CREDENTIAL on API 30+.
        Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
    } else {
        // Older devices: WEAK + DEVICE_CREDENTIAL keeps the PIN/password fallback.
        Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
    }

    /** Whether the device can authenticate the user at all (any enrolled factor). */
    fun isAvailable(): Boolean {
        val manager = BiometricManager.from(activity)
        return manager.canAuthenticate(allowedAuthenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticate(
        title: String,
        subtitle: String,
        description: String? = null,
        onResult: (BiometricResult) -> Unit,
    ) {
        if (!isAvailable()) {
            onResult(BiometricResult.Unavailable)
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(BiometricResult.Success)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                onResult(
                    if (cancelled) BiometricResult.Cancelled
                    else BiometricResult.Error(errorCode, errString.toString())
                )
            }
            // onAuthenticationFailed = a bad fingerprint/face attempt — system
            // prompt keeps trying until cancel or hard error, so don't fire here.
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .apply { description?.let { setDescription(it) } }
            .setAllowedAuthenticators(allowedAuthenticators)
            // setNegativeButtonText is illegal when DEVICE_CREDENTIAL is allowed —
            // the system supplies a "Use PIN" button instead.
            .build()

        BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
    }
}

/**
 * Composable convenience. Resolves the hosting FragmentActivity from the
 * Compose context and remembers a single BiometricAuth across recompositions.
 * Throws if the activity isn't a FragmentActivity — that's a wiring bug, not
 * a runtime condition.
 */
@Composable
fun rememberBiometricAuth(): BiometricAuth {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
        ?: error("MainActivity must extend FragmentActivity for BiometricPrompt")
    return remember(activity) { BiometricAuth(activity) }
}
