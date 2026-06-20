package com.wyltek.wallet

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.wyltek.wallet.agent.service.AgentNotifications
import com.wyltek.wallet.ui.navigation.AppNavigation
import com.wyltek.wallet.ui.theme.BlackboxVaultTheme

// FragmentActivity (which extends androidx.activity.ComponentActivity) is
// required by androidx.biometric.BiometricPrompt for fragment-lifecycle binding.
class MainActivity : FragmentActivity() {

    // Holds a pending approval ID that should trigger navigation to AgentApproval.
    // -1L means "no pending deep-link". Using a Compose state so recomposition fires
    // when onNewIntent delivers a new intent while the activity is already running.
    private val pendingApprovalId = mutableLongStateOf(-1L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleApprovalIntent(intent)
        setContent {
            BlackboxVaultTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(
                        approvalDeepLinkId = pendingApprovalId.longValue,
                        onApprovalDeepLinkConsumed = { pendingApprovalId.longValue = -1L }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleApprovalIntent(intent)
    }

    private fun handleApprovalIntent(intent: Intent) {
        val id = intent.getLongExtra(AgentNotifications.EXTRA_APPROVAL_PENDING_ID, -1L)
        if (id != -1L) {
            pendingApprovalId.longValue = id
        }
    }
}
