package com.shay.waypoint.data

/** Starter data from the spec (section 13). Today = 15 Nov 2026. */
object Seed {
    private fun task(
        title: String, bucket: String, due: String = "", time: String = "", priority: String = "med",
        notes: String = "", remind: String = "", chain: String = "", order: Int = 0, watch: Boolean = false,
        rec: String = "none", project: String = "", active: Boolean = true,
    ) = mapOf(
        "title" to title, "bucket" to bucket, "project" to project, "notes" to notes, "due_date" to due,
        "due_time" to time, "priority" to priority, "status" to "todo", "remind_minutes_before" to remind,
        "chain_id" to chain, "chain_order" to if (chain.isEmpty()) "" else order.toString(),
        "watch_for_date" to if (watch) "TRUE" else "FALSE", "recurrence" to rec,
        "active" to if (active) "TRUE" else "FALSE", "deleted" to "FALSE",
    )

    fun rows(): List<Pair<String, Map<String, String>>> {
        val out = mutableListOf<Pair<String, Map<String, String>>>()
        listOf("Education" to "#2F80FF", "Work" to "#A78BFA", "Life" to "#3DD68C", "Health" to "#FFB547")
            .forEachIndexed { i, (n, c) -> out += "Buckets" to mapOf("name" to n, "colour_hex" to c, "order" to "${i + 1}") }

        // Generic example data showing each feature: dated/timed tasks, reminders, recurrence,
        // "watch for date", a chain of ordered steps, and a dormant project. Dates are relative to install day.
        val ed = "Education"
        val d0 = java.time.LocalDate.now()
        fun d(n: Long) = d0.plusDays(n).toString()
        val rentDay = 1
        val nextRent = java.time.YearMonth.from(d0).plusMonths(1).atDay(rentDay).toString()
        val tasks = listOf(
            task("Finish weekly assignment", ed, d(0), priority = "high"),
            task("Review lecture notes", ed, d(1)),
            task("Register for the exam", ed, d(6), "10:00", "high", remind = "1440,0"),
            task("Exam revision", ed, d(14), priority = "high", notes = "Make a revision timetable"),
            task("Language practice", ed, d(0), rec = "daily", notes = "15 minutes a day"),
            task("Entrance test", ed, watch = true, notes = "Date not announced yet"),
            task("Request transcripts", ed, chain = "University application", order = 1, priority = "high"),
            task("Write statement of purpose", ed, chain = "University application", order = 2, priority = "high"),
            task("Submit application", ed, chain = "University application", order = 3, priority = "high"),
            task("Research scholarships", ed, project = "Next year", active = false),

            task("Reply to recruiter email", "Work", d(0), priority = "high"),
            task("Update CV", "Work", d(3)),
            task("Prepare for interview", "Work", watch = true, priority = "high"),

            task("Pay rent", "Life", nextRent, "09:00", "high", remind = "1440,0", rec = "monthly:$rentDay"),
            task("Phone bill", "Life", nextRent, "09:00", remind = "1440,0", rec = "monthly:$rentDay"),
            task("Clean room", "Life", d(0), rec = "weekly:SU"),
            task("Laundry", "Life", d(6), rec = "weekly:SA"),
            task("Clear phone storage", "Life", priority = "low"),
            task("Book movers", "Life", project = "Moving house", active = false, notes = "Activate when the move date is fixed"),
            task("Pack books", "Life", project = "Moving house", active = false),
        )
        tasks.forEach { out += "Tasks" to it }

        out += "Habits" to mapOf("name" to "Workout", "type" to "workout", "daily_target" to "10", "unit" to "min", "reminder_times" to "07:10")
        out += "Habits" to mapOf("name" to "Water", "type" to "water", "daily_target" to "8", "unit" to "glasses", "reminder_times" to "")
        out += "Habits" to mapOf("name" to "Sleep", "type" to "sleep", "daily_target" to "8", "unit" to "hours", "reminder_times" to "")

        listOf("Breakfast" to "07:45", "Lunch" to "13:00", "Snack" to "17:30", "Dinner" to "20:00").forEach { (n, t) ->
            out += "Meals" to mapOf("slot_name" to n, "time" to t, "reminder_on" to "TRUE")
        }
        Defaults.budgetCategories.forEach { c ->
            out += "Budget" to mapOf("month" to java.time.YearMonth.now().toString(),"category" to c, "limit_inr" to "")
        }
        Defaults.settings.forEach { (k, v) -> out += "Settings" to mapOf("key" to k, "value" to v) }
        return out
    }
}
