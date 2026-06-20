package com.wyltek.wallet.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.wyltek.wallet.MainActivity
import com.wyltek.wallet.R

object AgentNotifications {
    const val CHANNEL_SERVER = "agent_server"
    const val CHANNEL_APPROVAL = "agent_approval"
    const val EXTRA_APPROVAL_PENDING_ID = "agent_approval_pending_id"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val serverChannel = NotificationChannel(
            CHANNEL_SERVER,
            "Agent Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification while the agent gateway is running"
        }

        val approvalChannel = NotificationChannel(
            CHANNEL_APPROVAL,
            "Agent Approvals",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Prompts to approve or deny agent-initiated transactions"
        }

        nm.createNotificationChannel(serverChannel)
        nm.createNotificationChannel(approvalChannel)
    }

    fun serverNotification(context: Context, bindAddr: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVER)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Agent Gateway")
            .setContentText("Listening on $bindAddr")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    fun approvalNotification(context: Context, pendingId: Long, summary: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            pendingId.toInt(),
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_APPROVAL_PENDING_ID, pendingId)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_APPROVAL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Agent Approval Required")
            .setContentText(summary)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
    }
}
