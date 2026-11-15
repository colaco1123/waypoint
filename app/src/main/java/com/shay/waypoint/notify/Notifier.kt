package com.shay.waypoint.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shay.waypoint.R
import com.shay.waypoint.data.Task
import com.shay.waypoint.data.shortTitle
import com.shay.waypoint.ui.MainActivity

object Notifier {
    const val TASKS = "tasks"; const val ROUTINE = "routine"; const val MEALS = "meals"
    const val WATER = "water"; const val MONEY = "money"; const val SYNC = "sync"

    val channels = listOf(
        TASKS to "Tasks", ROUTINE to "Routine", MEALS to "Meals", WATER to "Water", MONEY to "Money", SYNC to "Sync",
    )

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        for ((id, name) in channels) {
            val imp = if (id == SYNC) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_HIGH
            nm.createNotificationChannel(NotificationChannel(id, name, imp).apply {
                enableVibration(id != SYNC)
                if (id != SYNC) vibrationPattern = longArrayOf(0, 250, 150, 250)
                enableLights(true)
                lightColor = Color.BLUE
            })
        }
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun action(ctx: Context, label: String, act: String, notifId: Int, extras: Map<String, String>): NotificationCompat.Action {
        val i = Intent(ctx, ActionReceiver::class.java).apply {
            action = "wp.$act.$notifId"
            putExtra("act", act)
            putExtra("notif_id", notifId)
            extras.forEach { (k, v) -> putExtra(k, v) }
        }
        val pi = PendingIntent.getBroadcast(ctx, (act + notifId).hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action.Builder(0, label, pi).build()
    }

    fun show(ctx: Context, channel: String, id: Int, title: String, text: String, actions: List<NotificationCompat.Action> = emptyList()) {
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF2F80FF.toInt())
            .setContentTitle(shortTitle(title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
        actions.forEach { b.addAction(it) }
        try {
            NotificationManagerCompat.from(ctx).notify(id, b.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted
        }
    }

    fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)

    fun chainUnlocked(ctx: Context, chain: String, next: Task) {
        show(ctx, TASKS, ("unlock:" + next.id).hashCode(), "Unlocked: ${next.title}", "Next step in $chain")
    }

    fun money(ctx: Context, title: String, text: String) {
        show(ctx, MONEY, title.hashCode(), title, text)
    }
}
