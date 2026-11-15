package com.shay.waypoint.capture

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.shay.waypoint.repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** New SMS -> parse bank debits. Message text is parsed in memory and never stored. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // Multi-part messages arrive as several PDUs from the same sender.
        val bySender = msgs.groupBy { it.originatingAddress ?: "" }
            .mapValues { (_, parts) -> parts.joinToString("") { it.messageBody ?: "" } to (parts.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()) }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = ctx.repo()
                if (repo.settingNow("sms_capture") == "TRUE") {
                    bySender.forEach { (sender, v) -> repo.capture.onSms(sender, v.first, v.second, notify = true) }
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}

/** Backup capture from Google Pay / PhonePe / Paytm / Samsung Wallet "Paid ₹X to Y" notifications. */
class PaymentNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in TxnParser.paymentApps) return
        val ex = sbn.notification?.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val time = sbn.postTime
        scope.launch {
            runCatching {
                val repo = applicationContext.repo()
                if (repo.settingNow("notif_capture") != "TRUE") return@launch
                val p = TxnParser.parseNotification(title, text) ?: return@launch
                repo.capture.ingest(p, time, "notification", notify = true)
            }
        }
    }
}
