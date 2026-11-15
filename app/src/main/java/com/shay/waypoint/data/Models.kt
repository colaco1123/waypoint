package com.shay.waypoint.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

private fun d(s: String): LocalDate? = s.takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
private fun tm(s: String): LocalTime? = s.takeIf { it.isNotEmpty() }?.let(LocalTime::parse)
private fun n(s: String): Double? = s.toDoubleOrNull()

class Bucket(val m: Map<String, String>) {
    val id = m.s("id"); val name = m.s("name"); val colour = m.s("colour_hex").ifEmpty { "#2F80FF" }
    val order = n(m.s("order")) ?: 0.0
}

class Task(val m: Map<String, String>) {
    val id = m.s("id"); val title = m.s("title"); val bucket = m.s("bucket"); val project = m.s("project")
    val notes = m.s("notes"); val dueDate = d(m.s("due_date")); val dueTime = tm(m.s("due_time"))
    val priority = m.s("priority").ifEmpty { "med" }; val done = m.s("status") == "done"
    val remind: List<Int> = m.s("remind_minutes_before").split(",").mapNotNull { it.trim().toIntOrNull() }
    val chainId = m.s("chain_id"); val chainOrder = n(m.s("chain_order")) ?: 0.0
    val watch = m.s("watch_for_date") == "TRUE"; val recurrence = m.s("recurrence").ifEmpty { "none" }
    val active = m.s("active") != "FALSE"
    val sortKey: LocalDateTime get() = (dueDate ?: LocalDate.MAX.minusDays(1)).atTime(dueTime ?: LocalTime.of(23, 59))
}

class Habit(val m: Map<String, String>) {
    val id = m.s("id"); val name = m.s("name"); val type = m.s("type"); val target = n(m.s("daily_target")) ?: 1.0
    val unit = m.s("unit")
    val times: List<LocalTime> = m.s("reminder_times").split(",").mapNotNull { runCatching { LocalTime.parse(it.trim()) }.getOrNull() }
}

class HabitEntry(val m: Map<String, String>) {
    val id = m.s("id"); val habitId = m.s("habit_id"); val date = LocalDate.parse(m.s("date")); val value = n(m.s("value")) ?: 0.0
}

class Meal(val m: Map<String, String>) {
    val id = m.s("id"); val name = m.s("slot_name"); val time: LocalTime = LocalTime.parse(m.s("time"))
    val reminder = m.s("reminder_on") != "FALSE"
}

class FoodEntry(val m: Map<String, String>) {
    val id = m.s("id"); val date = LocalDate.parse(m.s("date")); val slot = m.s("slot"); val food = m.s("food")
    val kcal = n(m.s("calories")) ?: 0.0; val protein = n(m.s("protein_g")) ?: 0.0
}

class BudgetLine(val m: Map<String, String>) {
    val id = m.s("id"); val month: YearMonth = YearMonth.parse(m.s("month")); val category = m.s("category")
    val limit = n(m.s("limit_inr"))
}

class Expense(val m: Map<String, String>) {
    val id = m.s("id"); val date = LocalDate.parse(m.s("date")); val amount = n(m.s("amount_inr")) ?: 0.0
    val category = m.s("category"); val note = m.s("note")
}

class MerchantRule(val m: Map<String, String>) {
    val id = m.s("id"); val match = m.s("match_text").lowercase(); val category = m.s("category")
}

/** A captured payment waiting for review (local only, never synced; raw message text is never kept). */
class Capture(val m: Map<String, String>) {
    val id = m.s("id"); val amount = n(m.s("amount")) ?: 0.0; val merchant = m.s("merchant")
    val time = m.s("time").toLongOrNull() ?: 0L; val account4 = m.s("account4"); val upiRef = m.s("upi_ref")
    val source = m.s("source"); val status = m.s("status"); val suggested = m.s("category")
}

class WeightEntry(val m: Map<String, String>) {
    val id = m.s("id"); val date = LocalDate.parse(m.s("date")); val kg = n(m.s("kg")) ?: 0.0
}

fun habitSlotKey(habitId: String, time: LocalTime) = "habit:$habitId:${fmtTime(time)}"

object Defaults {
    val settings = linkedMapOf(
        "wake_time" to "07:00",
        "bedtime" to "23:00",
        "leave_time" to "08:30",
        "winddown_minutes" to "30",
        "water_interval_hours" to "2",
        "water_goal" to "8",
        "calorie_target" to "",
        "protein_target" to "",
        "accent_hex" to "#2F80FF",
        "briefing_on" to "TRUE",
        "notif_tasks" to "TRUE",
        "notif_routine" to "TRUE",
        "notif_meals" to "TRUE",
        "notif_water" to "TRUE",
        "notif_money" to "TRUE",
        "notif_sync" to "TRUE",
        "sms_capture" to "FALSE",
        "notif_capture" to "FALSE",
        "auto_confirm_known" to "FALSE",
        "sms_senders" to com.shay.waypoint.capture.TxnParser.defaultSenders.joinToString(","),
    )
    val bucketColours = listOf("#2F80FF", "#A78BFA", "#3DD68C", "#FFB547", "#FF5A5A", "#00C2FF", "#FF3D8B", "#FF6B3D")
    val accentPresets = listOf("#2F80FF", "#00C2FF", "#7C5CFF", "#3DD68C", "#FF6B3D", "#FF3D8B", "#FFB547", "#FFFFFF")
    val budgetCategories = listOf("Food", "Rent and Bills", "Transport", "Projects and Hobbies", "Misc")
}
