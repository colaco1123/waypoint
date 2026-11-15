package com.shay.waypoint.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.shay.waypoint.data.*
import java.time.*

/** Computes every upcoming reminder and sets exact alarms. Called after any change, sync, alarm and boot. */
object Scheduler {
    data class Alarm(val key: String, val at: LocalDateTime, val extras: Map<String, String>)

    private fun intent(ctx: Context, key: String, extras: Map<String, String>) =
        Intent(ctx, AlarmReceiver::class.java).apply {
            action = "wp.alarm"
            data = Uri.parse("wp://alarm/" + Uri.encode(key))
            putExtra("key", key)
            extras.forEach { (k, v) -> putExtra(k, v) }
        }

    private fun pi(ctx: Context, key: String, extras: Map<String, String>): PendingIntent =
        PendingIntent.getBroadcast(ctx, key.hashCode(), intent(ctx, key, extras), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    fun setAlarm(ctx: Context, a: Alarm) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val ms = a.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val p = pi(ctx, a.key, a.extras)
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
        }
    }

    fun snooze(ctx: Context, extras: Map<String, String>, minutes: Long = 15) {
        val key = "snooze:" + (extras["orig_key"] ?: extras["id"] ?: "x")
        setAlarm(ctx, Alarm(key, LocalDateTime.now().plusMinutes(minutes), extras - "key"))
    }

    suspend fun rescheduleAll(ctx: Context, repo: Repo) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val prefs = repo.prefs
        prefs.getStringSet("alarm_keys", emptySet())!!.forEach { am.cancel(pi(ctx, it, emptyMap())) }

        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val list = mutableListOf<Alarm>()
        fun next(t: LocalTime): LocalDateTime = today.atTime(t).let { if (it.isAfter(now)) it else it.plusDays(1) }
        suspend fun time(k: String, def: String) = parseTimeLenient(repo.settingNow(k)) ?: LocalTime.parse(def)

        // Tasks: reminders within the next 30 days
        val tasks = repo.tasksNow()
        for (t in tasks) {
            if (t.done || !t.active || t.dueDate == null || repo.isLocked(t, tasks)) continue
            val base = t.dueDate.atTime(t.dueTime ?: LocalTime.of(9, 0))
            val offsets = t.remind.ifEmpty { if (t.dueTime != null) listOf(0) else emptyList() }
            for (o in offsets.distinct()) {
                val at = base.minusMinutes(o.toLong())
                if (at.isAfter(now) && at.isBefore(now.plusDays(30))) {
                    val text = buildString {
                        append(if (o == 0) "Due now" else "Due " + fmtRelDay(t.dueDate, at.toLocalDate()).lowercase())
                        t.dueTime?.let { append(" at ${fmtTime(it)}") }
                        if (t.bucket.isNotEmpty()) append(" · ${t.bucket}")
                    }
                    list += Alarm("task:${t.id}:$o", at, mapOf("kind" to "task", "id" to t.id, "title" to t.title, "text" to text))
                }
            }
        }

        val wake = time("wake_time", "07:00")
        val bed = time("bedtime", "23:00")
        if (repo.settingNow("briefing_on") != "FALSE")
            list += Alarm("briefing", next(wake), mapOf("kind" to "briefing"))

        val habits = repo.habitsNow()
        // One alarm per reminder_time of every habit row; key matches the Today route slot.
        habits.forEach { h ->
            h.times.distinct().forEach { tm ->
                val slot = fmtTime(tm)
                list += Alarm(
                    repo.slotKey(h.id, tm), next(tm),
                    mapOf("kind" to "habit", "id" to h.id, "slot" to slot, "type" to h.type,
                        "title" to com.shay.waypoint.ui.habitStopLabel(h),
                        "text" to "$slot · ${fmtNum(h.target)} ${h.unit}/day".trim()),
                )
            }
        }

        repo.settingNow("leave_time").let { parseTimeLenient(it) }?.let {
            list += Alarm("leave", next(it), mapOf("kind" to "routine", "id" to "leave", "title" to "Time to leave", "text" to "Leave by ${fmtTime(it)}"))
        }

        repo.mealsNow().filter { it.reminder }.forEach { m ->
            list += Alarm("meal:${m.id}", next(m.time), mapOf("kind" to "meal", "id" to "meal:${m.name}", "title" to "${m.name} time", "text" to "Tap to log what you eat"))
        }

        // Water every N hours between wake and bedtime
        val interval = repo.settingNow("water_interval_hours").toDoubleOrNull()?.takeIf { it > 0 }
        if (interval != null) {
            var t = wake.plusMinutes((interval * 60).toLong())
            var guard = 0
            // a bedtime after midnight (e.g. 01:00) counts as end of day here
            val end = if (bed.isAfter(wake)) bed else LocalTime.of(23, 59)
            while (t.isBefore(end) && t.isAfter(wake) && guard++ < 24) {
                list += Alarm("water:${fmtTime(t)}", next(t), mapOf("kind" to "water"))
                t = t.plusMinutes((interval * 60).toLong())
            }
        }

        val wind = repo.settingNow("winddown_minutes").toLongOrNull() ?: 30
        if (wind > 0) list += Alarm("winddown", next(bed.minusMinutes(wind)), mapOf("kind" to "routine", "id" to "winddown", "title" to "Wind down", "text" to "Bedtime at ${fmtTime(bed)}. Screens off soon"))
        list += Alarm("bedtime", next(bed), mapOf("kind" to "routine", "id" to "bedtime", "title" to "Bedtime", "text" to "Time to sleep"))

        list.forEach { setAlarm(ctx, it) }
        prefs.edit().putStringSet("alarm_keys", list.map { it.key }.toSet()).apply()
    }
}
