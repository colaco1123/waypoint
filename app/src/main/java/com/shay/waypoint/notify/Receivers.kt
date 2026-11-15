package com.shay.waypoint.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shay.waypoint.data.*
import com.shay.waypoint.repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try { block() } catch (_: Exception) { } finally { pending.finish() }
    }
}

private fun Intent.extrasMap(): Map<String, String> =
    extras?.keySet()?.mapNotNull { k -> extras?.get(k)?.let { if (it is String) k to it else null } }?.toMap() ?: emptyMap()

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = async {
        val repo = ctx.repo()
        val ex = intent.extrasMap()
        val key = ex["key"] ?: "x"
        val nid = (ex["orig_key"] ?: key).hashCode()
        val extras = ex + ("orig_key" to (ex["orig_key"] ?: key))
        suspend fun on(ch: String) = repo.settingNow("notif_$ch") != "FALSE"

        when (ex["kind"]) {
            "task" -> if (on(Notifier.TASKS)) {
                val t = repo.tasksNow().find { it.id == ex["id"] }
                if (t != null && !t.done && t.active) Notifier.show(
                    ctx, Notifier.TASKS, nid, t.title, ex["text"] ?: "",
                    listOf(Notifier.action(ctx, "Done", "done", nid, extras), Notifier.action(ctx, "Snooze 15 min", "snooze", nid, extras)),
                )
            }
            "briefing" -> if (on(Notifier.TASKS)) {
                val today = LocalDate.now()
                val all = repo.tasksNow()
                val due = all.filter { !it.done && it.active && it.dueDate != null && !it.dueDate.isAfter(today) && !repo.isLocked(it, all) }
                    .sortedBy { it.sortKey }
                val overdue = due.count { it.dueDate!!.isBefore(today) }
                val title = if (due.isEmpty()) "Good morning" else "${due.size} task${if (due.size == 1) "" else "s"} today"
                val text = if (due.isEmpty()) "Nothing due today" else "First: ${due.first().title}" + if (overdue > 0) " · $overdue overdue" else ""
                Notifier.show(ctx, Notifier.TASKS, nid, title, text)
            }
            "workout" -> if (on(Notifier.ROUTINE)) {
                val h = repo.habitOfType("workout")
                val done = h != null && repo.habitValue(h.id, LocalDate.now()) >= h.target
                if (!done) Notifier.show(
                    ctx, Notifier.ROUTINE, nid, "Workout · ${ex["text"]}", "Keep the streak going",
                    listOf(Notifier.action(ctx, "Done", "done", nid, extras), Notifier.action(ctx, "Snooze 15 min", "snooze", nid, extras)),
                )
            }
            "habit" -> if (on(Notifier.ROUTINE)) {
                val h = repo.habitsNow().find { it.id == ex["id"] }
                val done = when {
                    h == null -> true
                    h.type == "workout" -> repo.habitValue(h.id, LocalDate.now()) >= h.target
                    else -> repo.isTickedToday(key.removePrefix("snooze:"))
                }
                if (!done) Notifier.show(
                    ctx, Notifier.ROUTINE, nid, ex["title"] ?: "Habit", ex["text"] ?: "",
                    listOf(
                        Notifier.action(ctx, if (ex["type"] == "water") "+1 glass" else "Done", "done", nid, extras),
                        Notifier.action(ctx, "Snooze 15 min", "snooze", nid, extras),
                    ),
                )
            }
            "routine", "bedtime" -> if (on(Notifier.ROUTINE) && !repo.isTickedToday(ex["id"] ?: "")) Notifier.show(
                ctx, Notifier.ROUTINE, nid, ex["title"] ?: "Routine", ex["text"] ?: "",
                listOf(Notifier.action(ctx, "Done", "done", nid, extras), Notifier.action(ctx, "Snooze 15 min", "snooze", nid, extras)),
            )
            // skip the meal reminder if it is already ticked or food is logged for that slot today
            "meal" -> if (on(Notifier.MEALS) && !repo.isTickedToday(ex["id"] ?: "") &&
                repo.snap("FoodLog").none { it.s("date") == LocalDate.now().toString() && "meal:" + it.s("slot") == ex["id"] }
            ) Notifier.show(
                ctx, Notifier.MEALS, nid, ex["title"] ?: "Meal time", ex["text"] ?: "",
                listOf(Notifier.action(ctx, "Done", "done", nid, extras), Notifier.action(ctx, "Snooze 15 min", "snooze", nid, extras)),
            )
            "water" -> if (on(Notifier.WATER)) {
                val h = repo.habitOfType("water")
                val goal = repo.settingNow("water_goal").toDoubleOrNull() ?: 8.0
                val have = h?.let { repo.habitValue(it.id, LocalDate.now()) } ?: 0.0
                if (have < goal) Notifier.show(
                    ctx, Notifier.WATER, nid, "Drink water", "${fmtNum(have)} of ${fmtNum(goal)} glasses today",
                    listOf(Notifier.action(ctx, "+1 glass", "water", nid, extras)),
                )
            }
        }
        if (!key.startsWith("snooze:")) Scheduler.rescheduleAll(ctx, repo)
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = async {
        val repo = ctx.repo()
        val ex = intent.extrasMap()
        val nid = intent.getIntExtra("notif_id", 0)
        Notifier.cancel(ctx, nid)
        when (intent.getStringExtra("act")) {
            "done" -> when (ex["kind"]) {
                "task" -> ex["id"]?.let { repo.completeTask(it) }
                "workout" -> repo.setWorkoutDone(LocalDate.now(), true)
                "habit" -> { val id = ex["id"]; val slot = ex["slot"]; if (id != null && slot != null) repo.tickHabitSlot(id, slot, true) }
                else -> ex["id"]?.let { repo.setTick(it, true) }
            }
            "snooze" -> Scheduler.snooze(ctx, ex - "act" - "notif_id")
            "water" -> repo.addWater(1)
        }
        // Do these now: the process may be stopped before the repo's debounced refresh runs.
        Scheduler.rescheduleAll(ctx, repo)
        com.shay.waypoint.widget.Widgets.updateAll(ctx)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = async {
        Scheduler.rescheduleAll(ctx, ctx.repo())
    }
}
