package com.shay.waypoint.data

/** Sheet tabs, exact header rows and row validation/normalisation. */
object Schema {
    val tables: LinkedHashMap<String, List<String>> = linkedMapOf(
        "Buckets" to listOf("id", "name", "colour_hex", "order", "deleted", "updated_at"),
        "Tasks" to listOf(
            "id", "title", "bucket", "project", "notes", "due_date", "due_time", "priority", "status",
            "remind_minutes_before", "chain_id", "chain_order", "watch_for_date", "recurrence", "active",
            "deleted", "updated_at",
        ),
        "Habits" to listOf("id", "name", "type", "daily_target", "unit", "reminder_times", "deleted", "updated_at"),
        "HabitLog" to listOf("id", "habit_id", "date", "value", "updated_at"),
        "Meals" to listOf("id", "slot_name", "time", "reminder_on", "deleted", "updated_at"),
        "FoodLog" to listOf("id", "date", "slot", "food", "calories", "protein_g", "updated_at"),
        "Budget" to listOf("id", "month", "category", "limit_inr", "deleted", "updated_at"),
        "Expenses" to listOf("id", "date", "amount_inr", "category", "note", "source", "deleted", "updated_at"),
        "Settings" to listOf("key", "value", "updated_at"),
        "Weight" to listOf("id", "date", "kg", "updated_at"),
        "MerchantRules" to listOf("id", "match_text", "category", "deleted", "updated_at"),
    )

    private val prefix = mapOf(
        "Buckets" to "b", "Tasks" to "t", "Habits" to "h", "HabitLog" to "hl", "Meals" to "m",
        "FoodLog" to "f", "Budget" to "bg", "Expenses" to "e", "Weight" to "w", "MerchantRules" to "mr",
    )

    fun idCol(t: String) = if (t == "Settings") "key" else "id"
    fun hasDeleted(t: String) = tables[t]?.contains("deleted") == true

    fun canonical(name: String): String? = tables.keys.firstOrNull { it.equals(name.trim(), ignoreCase = true) }

    fun nextId(t: String, taken: Collection<String>): String {
        val p = prefix[t] ?: "x"
        val max = taken.mapNotNull { if (it.startsWith("$p-")) it.removePrefix("$p-").toIntOrNull() else null }.maxOrNull() ?: 0
        return "%s-%04d".format(p, max + 1)
    }

    class Bad(msg: String) : IllegalArgumentException(msg)

    private fun bad(msg: String): Nothing = throw Bad(msg)

    /** Returns a clean row with exactly the schema columns, or throws [Bad]. */
    fun normalise(t: String, raw: Map<String, String>): Map<String, String> {
        val cols = tables[t] ?: bad("unknown table $t")
        val r = LinkedHashMap<String, String>()
        for (c in cols) r[c] = (raw[c] ?: "").trim()

        fun req(vararg cs: String) { for (c in cs) if (r[c].isNullOrBlank()) bad("$c is blank") }
        fun date(c: String, required: Boolean = false) {
            val s = r[c]!!
            if (s.isEmpty()) { if (required) bad("$c is blank"); return }
            r[c] = parseDateLenient(s)?.toString() ?: bad("$c '$s' is not a date (use YYYY-MM-DD)")
        }
        fun time(c: String, required: Boolean = false) {
            val s = r[c]!!
            if (s.isEmpty()) { if (required) bad("$c is blank"); return }
            r[c] = parseTimeLenient(s)?.let { fmtTime(it) } ?: bad("$c '$s' is not a time (use HH:MM)")
        }
        fun num(c: String, required: Boolean = false, def: String = "") {
            val s = r[c]!!
            if (s.isEmpty()) { if (required) bad("$c is blank"); r[c] = def; return }
            r[c] = parseNum(s)?.let { fmtNum(it) } ?: bad("$c '$s' is not a number")
        }
        fun bool(c: String, def: Boolean) {
            val s = r[c]!!
            r[c] = when (parseBool(s, def)) { true -> "TRUE"; false -> "FALSE"; null -> bad("$c '$s' is not TRUE/FALSE") }
        }
        fun times(c: String) {
            val s = r[c]!!
            if (s.isEmpty()) return
            r[c] = s.split(",").filter { it.isNotBlank() }.joinToString(",") { p ->
                parseTimeLenient(p)?.let { fmtTime(it) } ?: bad("$c '$p' is not a time")
            }
        }

        if ("deleted" in cols) bool("deleted", false)
        r["updated_at"]?.let { s ->
            if (s.isNotEmpty()) r["updated_at"] = parseInstantLenient(s)?.toString() ?: bad("updated_at '$s' unreadable")
        }

        when (t) {
            "Buckets" -> {
                req("name")
                r["colour_hex"] = normColour(r["colour_hex"]!!) ?: bad("colour_hex '${r["colour_hex"]}' is not #RRGGBB")
                num("order", def = "0")
            }
            "Tasks" -> {
                req("title")
                date("due_date"); time("due_time")
                r["priority"] = when (r["priority"]!!.lowercase()) {
                    "", "med", "medium", "normal", "m", "2" -> "med"
                    "low", "l", "1" -> "low"
                    "high", "h", "3", "urgent" -> "high"
                    else -> bad("priority '${r["priority"]}' must be low/med/high")
                }
                r["status"] = when (r["status"]!!.lowercase()) {
                    "", "todo", "to do", "open", "pending" -> "todo"
                    "done", "complete", "completed", "x", "true" -> "done"
                    else -> bad("status '${r["status"]}' must be todo/done")
                }
                r["remind_minutes_before"] = r["remind_minutes_before"]!!.split(",").filter { it.isNotBlank() }
                    .joinToString(",") { p -> p.trim().toDoubleOrNull()?.toLong()?.toString() ?: bad("remind_minutes_before '$p' is not minutes") }
                num("chain_order", def = if (r["chain_id"]!!.isEmpty()) "" else "1")
                bool("watch_for_date", false)
                r["recurrence"] = Recurrence.normalise(r["recurrence"]!!) ?: bad("recurrence '${r["recurrence"]}' unreadable")
                bool("active", true)
            }
            "Habits" -> {
                req("name")
                r["type"] = r["type"]!!.lowercase().let { if (it in listOf("workout", "water", "sleep", "custom")) it else if (it.isEmpty()) "custom" else bad("type '$it' must be workout/water/sleep/custom") }
                num("daily_target", def = "1")
                times("reminder_times")
            }
            "HabitLog" -> { req("habit_id"); date("date", true); num("value", def = "1") }
            "Meals" -> { req("slot_name"); time("time", true); bool("reminder_on", true) }
            "FoodLog" -> { date("date", true); req("food"); num("calories", def = "0"); num("protein_g", def = "0") }
            "Budget" -> {
                req("month", "category")
                val m = r["month"]!!
                r["month"] = Regex("(\\d{4})[-/](\\d{1,2})").find(m)?.let { "%s-%02d".format(it.groupValues[1], it.groupValues[2].toInt()) }
                    ?: parseDateLenient("1 $m")?.let { "%d-%02d".format(it.year, it.monthValue) }
                    ?: parseDateLenient(m)?.let { "%d-%02d".format(it.year, it.monthValue) }
                    ?: bad("month '$m' must be YYYY-MM")
                num("limit_inr")
            }
            "Expenses" -> {
                date("date", true); num("amount_inr", true); req("category")
                r["source"] = when (r["source"]!!.lowercase()) {
                    "", "manual" -> "manual"
                    "sms" -> "sms"
                    "notification", "notif" -> "notification"
                    else -> bad("source '${r["source"]}' must be sms/notification/manual")
                }
            }
            "MerchantRules" -> { req("match_text", "category"); r["match_text"] = r["match_text"]!!.lowercase() }
            "Settings" -> req("key")
            "Weight" -> { date("date", true); num("kg", true) }
        }
        return r
    }

    fun normColour(s: String): String? {
        val t = s.trim().removePrefix("#")
        if (t.isEmpty()) return "#2F80FF"
        if (!Regex("[0-9a-fA-F]{6}").matches(t)) return null
        return "#" + t.uppercase()
    }

    /** Text written to the README tab so any Claude chat knows how to edit the sheet. */
    val readme: List<List<String>> = listOf(
        listOf("Waypoint Data", "", "This sheet syncs two-way with the Waypoint Android app."),
        listOf("RULES", "", ""),
        listOf("", "Row 1", "Headers. Never rename, reorder or delete header cells."),
        listOf("", "id", "Stable text id (t-0012, b-0003 ...). Leave blank on a NEW row; the app assigns one and writes it back. Never change an existing id."),
        listOf("", "updated_at", "ISO time, e.g. 2026-11-15T10:30:00Z. ALWAYS set it to the current time when you add or edit a row. The newer updated_at wins when the app and the sheet disagree. A blank value counts as 'just edited'."),
        listOf("", "deleted", "Set to TRUE to delete a row (do not remove the row). The app removes it locally."),
        listOf("", "Booleans", "TRUE or FALSE."),
        listOf("", "Dates / times", "Dates YYYY-MM-DD, times HH:MM (24 h), months YYYY-MM. Amounts are plain numbers in INR."),
        listOf("", "Bad rows", "Malformed rows are skipped (never deleted) and listed in the app's Settings > Data > Sync log."),
        listOf("TAB", "COLUMN", "MEANING"),
        listOf("Buckets", "name / colour_hex / order", "Task groups shown as cards. colour_hex like #2F80FF. order = sort number."),
        listOf("Tasks", "title", "Task text (required)."),
        listOf("Tasks", "bucket", "Bucket NAME, e.g. Education, Work, Life, Health."),
        listOf("Tasks", "project", "Optional group inside a bucket, e.g. Moving house."),
        listOf("Tasks", "notes", "Free text."),
        listOf("Tasks", "due_date / due_time", "YYYY-MM-DD and HH:MM. Both optional."),
        listOf("Tasks", "priority", "low / med / high"),
        listOf("Tasks", "status", "todo / done"),
        listOf("Tasks", "remind_minutes_before", "Comma list of minutes before due, e.g. 60,1440. Blank = remind at due_time."),
        listOf("Tasks", "chain_id / chain_order", "Ordered multi-step tasks share a chain_id (the chain's name). Only the lowest unfinished chain_order is active."),
        listOf("Tasks", "watch_for_date", "TRUE when the date is not known yet."),
        listOf("Tasks", "recurrence", "none / daily / weekly / weekly:MO,WE / monthly / monthly:17 / every:3:days (days, weeks, months). Completing a recurring task moves due_date to the next occurrence."),
        listOf("Tasks", "active", "FALSE hides the task until triggered (used for dormant projects)."),
        listOf("Habits", "type", "workout / water / sleep / custom. daily_target + unit (e.g. 10 min, 8 glasses). reminder_times = comma list HH:MM."),
        listOf("HabitLog", "habit_id / date / value", "One row per habit per day. value = minutes, glasses, hours..."),
        listOf("Meals", "slot_name / time / reminder_on", "Meal slots with a reminder at time."),
        listOf("FoodLog", "date / slot / food / calories / protein_g", "What was eaten."),
        listOf("Budget", "month / category / limit_inr", "Budget limit per category per month (YYYY-MM)."),
        listOf("Expenses", "date / amount_inr / category / note", "Spending. category should match a Budget category."),
        listOf("Expenses", "source", "sms / notification / manual: how the expense was captured. Blank = manual."),
        listOf("MerchantRules", "match_text / category", "Auto-categorising for captured payments: if the merchant contains match_text (lowercase), suggest category. Learned when an expense is confirmed in the app; edit freely."),
        listOf("Settings", "key / value", "wake_time, bedtime, leave_time, water_interval_hours, water_goal, calorie_target, protein_target, accent_hex, winddown_minutes."),
        listOf("Weight", "date / kg", "Body weight entries."),
    )
}
