package com.shay.waypoint.data

import android.content.Context
import android.content.SharedPreferences
import com.shay.waypoint.notify.Notifier
import com.shay.waypoint.notify.Scheduler
import com.shay.waypoint.sync.SyncEngine
import com.shay.waypoint.sync.SyncWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

/** Local-only row table for captured payments; not in Schema.tables, so it is never synced or exported. */
const val CAPTURES = "_Captures"

class Repo(val ctx: Context, db: WDb) {
    val dao = db.dao()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val prefs: SharedPreferences = ctx.getSharedPreferences("waypoint", Context.MODE_PRIVATE)
    val lock = Mutex()
    val sync = SyncEngine(this)
    val capture by lazy { com.shay.waypoint.capture.CaptureEngine(this) }

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    fun toast(s: String) { messages.tryEmit(s) }

    val today = MutableStateFlow(LocalDate.now())
    fun refreshToday() { if (today.value != LocalDate.now()) { today.value = LocalDate.now(); ticks.value = loadTicks() } }

    val syncing = MutableStateFlow(false)
    val lastSync = MutableStateFlow(prefs.getLong("last_sync", 0L))
    val lastSyncError = MutableStateFlow(prefs.getString("last_sync_error", null))

    var sheetId: String?
        get() = prefs.getString("sheet_id", null)
        set(v) { prefs.edit().putString("sheet_id", v).apply(); linked.value = v != null }
    val linked = MutableStateFlow(prefs.getString("sheet_id", null) != null)

    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    fun changed() { changes.tryEmit(Unit) }

    // ---------- reactive views ----------
    private fun rawFlow(t: String) = dao.observe(t).map { l -> l.map { it.map() }.filter { it["deleted"] != "TRUE" } }

    private fun <T> live(t: String, f: (Map<String, String>) -> T, sort: (List<T>) -> List<T> = { it }): StateFlow<List<T>> =
        rawFlow(t).map { l -> sort(l.mapNotNull { runCatching { f(it) }.getOrNull() }) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val buckets = live("Buckets", ::Bucket) { l -> l.sortedWith(compareBy<Bucket>({ it.order }, { it.name })) }
    val tasks = live("Tasks", ::Task) { l -> l.sortedWith(compareBy<Task>({ it.sortKey }, { it.chainOrder }, { it.title })) }
    val habits = live("Habits", ::Habit)
    val habitLog = live("HabitLog", ::HabitEntry)
    val meals = live("Meals", ::Meal) { l -> l.sortedBy { it.time } }
    val foodLog = live("FoodLog", ::FoodEntry)
    val budget = live("Budget", ::BudgetLine)
    val expenses = live("Expenses", ::Expense) { l -> l.sortedByDescending { it.date } }
    val weights = live("Weight", ::WeightEntry) { l -> l.sortedBy { it.date } }
    val merchantRules = live("MerchantRules", ::MerchantRule)

    /** Captured payments waiting for review (local-only table, newest first). */
    val toReview: StateFlow<List<Capture>> = dao.observe(CAPTURES)
        .map { l -> l.mapNotNull { runCatching { Capture(it.map()) }.getOrNull() }.filter { it.status == "pending" }.sortedByDescending { it.time } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val settings: StateFlow<Map<String, String>> = rawFlow("Settings")
        .map { l -> l.associate { it.s("key") to it.s("value") } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
    val syncLog = dao.observeLog().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val foods = dao.observeFoods().stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun s(k: String): String = settings.value[k]?.takeIf { it.isNotEmpty() } ?: Defaults.settings[k] ?: ""

    suspend fun settingNow(k: String): String =
        dao.get("Settings", k)?.map()?.s("value")?.takeIf { it.isNotEmpty() } ?: Defaults.settings[k] ?: ""

    suspend fun snap(t: String): List<Map<String, String>> = dao.all(t).map { it.map() }.filter { it["deleted"] != "TRUE" }
    suspend fun tasksNow() = snap("Tasks").mapNotNull { runCatching { Task(it) }.getOrNull() }
    suspend fun habitsNow() = snap("Habits").mapNotNull { runCatching { Habit(it) }.getOrNull() }
    suspend fun mealsNow() = snap("Meals").mapNotNull { runCatching { Meal(it) }.getOrNull() }

    // ---------- generic writes ----------
    suspend fun put(t: String, m: Map<String, String>): Map<String, String> {
        val norm = lock.withLock {
            val row = m.toMutableMap()
            val idc = Schema.idCol(t)
            if (row[idc].isNullOrBlank()) row[idc] = Schema.nextId(t, dao.all(t).map { it.id })
            row["updated_at"] = nowIso()
            val n = Schema.normalise(t, row)
            dao.upsert(RowEntity(t, n.getValue(idc), n.toJson(), n.getValue("updated_at"), true))
            n
        }
        changed()
        return norm
    }

    suspend fun update(t: String, id: String, vararg kv: Pair<String, String>) {
        val cur = dao.get(t, id)?.map() ?: return
        put(t, cur + kv)
    }

    suspend fun remove(t: String, id: String) {
        if (sheetId == null) {
            dao.delete(t, id)
        } else if (Schema.hasDeleted(t)) {
            update(t, id, "deleted" to "TRUE")
        } else {
            lock.withLock { dao.delete(t, id); dao.addTombstone(Tombstone(t, id)) }
        }
        changed()
    }

    fun launch(block: suspend () -> Unit) = scope.launch {
        try { block() } catch (e: Schema.Bad) { toast("Not saved: ${e.message}") } catch (e: Exception) { toast("Error: ${e.message}") }
    }

    // ---------- startup ----------
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            if (!prefs.getBoolean("seeded", false)) {
                for ((t, m) in Seed.rows()) runCatching { put(t, m) }
                prefs.edit().putBoolean("seeded", true).apply()
            }
            ensureMonth(YearMonth.now())
            Scheduler.rescheduleAll(ctx, this@Repo)
            runCatching { capture.prune() }
        }
        scope.launch {
            changes.debounce(1500).collect {
                runCatching { Scheduler.rescheduleAll(ctx, this@Repo) }
                com.shay.waypoint.widget.Widgets.updateAll(ctx)
                if (sheetId != null) SyncWorker.pushSoon(ctx)
            }
        }
        SyncWorker.schedulePeriodic(ctx)
    }

    fun syncNow(reason: String = "manual", token: String? = null) = scope.launch {
        if (sheetId == null) { toast("Link a Google Sheet in Settings to sync"); return@launch }
        val r = sync.sync(reason, token)
        if (reason != "open") toast(r.fold({ it }, { "Sync failed: ${it.message}" }))
    }

    // ---------- tasks ----------
    fun isLocked(t: Task, all: List<Task>) =
        t.chainId.isNotEmpty() && all.any { it.chainId == t.chainId && !it.done && it.chainOrder < t.chainOrder }

    suspend fun completeTask(id: String): String? {
        val all = tasksNow()
        val t = all.find { it.id == id } ?: return null
        if (t.done) return null
        if (t.recurrence != "none") {
            val today = LocalDate.now()
            var next = Recurrence.next(t.recurrence, t.dueDate ?: today)
            while (!next.isAfter(today)) next = Recurrence.next(t.recurrence, next)
            update("Tasks", id, "due_date" to next.toString(), "status" to "todo")
            return "Next: ${fmtDay(next)}"
        }
        update("Tasks", id, "status" to "done")
        if (t.chainId.isNotEmpty()) {
            val next = tasksNow().filter { it.chainId == t.chainId && !it.done }.minByOrNull { it.chainOrder }
            return if (next != null) {
                if (settingNow("notif_tasks") != "FALSE") Notifier.chainUnlocked(ctx, t.chainId, next)
                "Unlocked: ${next.title}"
            } else "Chain complete: ${t.chainId}"
        }
        return "Done"
    }

    suspend fun reopenTask(id: String) = update("Tasks", id, "status" to "todo")

    suspend fun snoozeTaskDays(id: String, days: Long) {
        val t = tasksNow().find { it.id == id } ?: return
        val base = maxOf(t.dueDate ?: LocalDate.now(), LocalDate.now())
        update("Tasks", id, "due_date" to base.plusDays(days).toString())
    }

    suspend fun setProjectActive(bucket: String, project: String, active: Boolean) {
        tasksNow().filter { it.project == project && it.bucket == bucket }.forEach {
            update("Tasks", it.id, "active" to if (active) "TRUE" else "FALSE")
        }
    }

    // ---------- buckets ----------
    suspend fun renameBucket(b: Bucket, newName: String) {
        val old = b.name
        update("Buckets", b.id, "name" to newName)
        tasksNow().filter { it.bucket == old }.forEach { update("Tasks", it.id, "bucket" to newName) }
    }

    suspend fun moveBucket(b: Bucket, dir: Int) {
        val l = buckets.value.toMutableList()
        val i = l.indexOfFirst { it.id == b.id }
        val j = i + dir
        if (i < 0 || j !in l.indices) return
        val tmp = l[i]; l[i] = l[j]; l[j] = tmp
        l.forEachIndexed { k, x -> if (x.order != (k + 1).toDouble()) update("Buckets", x.id, "order" to "${k + 1}") }
    }

    // ---------- habits ----------
    suspend fun habitOfType(type: String): Habit? = habitsNow().firstOrNull { it.type == type }

    suspend fun habitValue(habitId: String, date: LocalDate): Double =
        snap("HabitLog").filter { it.s("habit_id") == habitId && it.s("date") == date.toString() }
            .sumOf { it.s("value").toDoubleOrNull() ?: 0.0 }

    suspend fun setHabit(habitId: String, date: LocalDate, value: Double) {
        val rows = snap("HabitLog").filter { it.s("habit_id") == habitId && it.s("date") == date.toString() }
        if (rows.isEmpty()) {
            put("HabitLog", mapOf("habit_id" to habitId, "date" to date.toString(), "value" to fmtNum(value)))
        } else {
            update("HabitLog", rows[0].s("id"), "value" to fmtNum(value))
            rows.drop(1).forEach { remove("HabitLog", it.s("id")) }
        }
    }

    suspend fun addWater(n: Int) {
        val h = habitOfType("water") ?: return
        val d = LocalDate.now()
        setHabit(h.id, d, maxOf(0.0, habitValue(h.id, d) + n))
    }

    suspend fun setWorkoutDone(date: LocalDate, done: Boolean, minutes: Double? = null) {
        val h = habitOfType("workout") ?: return
        setHabit(h.id, date, if (done) (minutes ?: h.target) else 0.0)
    }

    // ---------- habit reminder slots (one route stop per reminder_time) ----------
    fun slotKey(habitId: String, time: java.time.LocalTime) = habitSlotKey(habitId, time)

    fun isTickedToday(key: String): Boolean = key in ticksFor(LocalDate.now())

    /** First day the app was used on this phone; earlier days have no route data. */
    val firstDay: LocalDate = prefs.getString("first_day", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: LocalDate.now().also { prefs.edit().putString("first_day", it.toString()).apply() }

    /** Route tick-offs for any past day (kept per day in local prefs). */
    fun ticksFor(date: LocalDate): Set<String> = prefs.getStringSet("ticks:$date", emptySet())!!.toSet()

    /**
     * Tick or untick one reminder slot of a habit and log it to HabitLog.
     * Workout: the day's value becomes the minimum duration (or 0).
     * Everything else: each ticked slot adds 1 to the day's value (water = +1 glass).
     */
    suspend fun tickHabitSlot(h: Habit, time: java.time.LocalTime, on: Boolean) {
        val today = LocalDate.now()
        val key = slotKey(h.id, time)
        if (h.type == "workout") {
            setTick(key, on)
            setWorkoutDone(today, on)
            return
        }
        val had = isTickedToday(key)
        if (on && had) return
        setTick(key, on)
        val cur = habitValue(h.id, today)
        setHabit(h.id, today, maxOf(0.0, cur + if (on) 1.0 else -1.0))
    }

    suspend fun tickHabitSlot(habitId: String, slot: String, on: Boolean) {
        val h = habitsNow().find { it.id == habitId } ?: return
        val t = parseTimeLenient(slot) ?: return
        tickHabitSlot(h, t, on)
    }

    // routine ticks (local only, per day)
    val ticks = MutableStateFlow(loadTicks())
    private fun loadTicks(): Set<String> = prefs.getStringSet("ticks:${LocalDate.now()}", emptySet())!!.toSet()
    @Synchronized
    fun setTick(key: String, on: Boolean) {
        val k = "ticks:${LocalDate.now()}"
        val cur = prefs.getStringSet(k, emptySet())!!.toMutableSet()
        if (on) cur += key else cur -= key
        prefs.edit().putStringSet(k, cur).apply()
        ticks.value = cur.toSet()
    }

    // ---------- food ----------
    suspend fun logFood(date: LocalDate, slot: String, food: String, kcal: Double, protein: Double, baseKcal: Double, baseProtein: Double, baseName: String) {
        put("FoodLog", mapOf("date" to date.toString(), "slot" to slot, "food" to food, "calories" to fmtNum(kcal), "protein_g" to fmtNum(protein)))
        val f = dao.food(baseName)
        dao.upsertFood((f ?: FoodItem(baseName, baseKcal, baseProtein)).copy(lastUsed = System.currentTimeMillis()))
    }

    suspend fun toggleFavourite(name: String, kcal: Double, protein: Double) {
        val f = dao.food(name) ?: FoodItem(name, kcal, protein)
        dao.upsertFood(f.copy(favourite = !f.favourite))
    }

    suspend fun addCustomFood(name: String, kcal: Double, protein: Double) =
        dao.upsertFood(FoodItem(name, kcal, protein, favourite = true, custom = true, lastUsed = System.currentTimeMillis()))

    // ---------- money ----------
    private val monthLock = Mutex()

    /** Renames a budget category everywhere it is used: budget lines, expenses and merchant rules. */
    suspend fun renameCategory(old: String, new: String) {
        if (old == new || new.isBlank()) return
        snap("Budget").filter { it.s("category") == old }.forEach { update("Budget", it.s("id"), "category" to new) }
        snap("Expenses").filter { it.s("category") == old }.forEach { update("Expenses", it.s("id"), "category" to new) }
        snap("MerchantRules").filter { it.s("category") == old }.forEach { update("MerchantRules", it.s("id"), "category" to new) }
    }

    /** Copies last month's categories and limits into [ym] once (guarded so two callers can't both do it). */
    suspend fun ensureMonth(ym: YearMonth) = monthLock.withLock {
        val lines = snap("Budget").mapNotNull { runCatching { BudgetLine(it) }.getOrNull() }
        if (lines.any { it.month == ym }) return@withLock
        val prev = lines.filter { it.month < ym }.maxOfOrNull { it.month }
        if (prev != null) {
            lines.filter { it.month == prev }.forEach {
                put("Budget", mapOf("month" to ym.toString(), "category" to it.category, "limit_inr" to (it.limit?.let(::fmtNum) ?: "")))
            }
        } else if (ym == YearMonth.now()) {
            Defaults.budgetCategories.forEach { put("Budget", mapOf("month" to ym.toString(), "category" to it, "limit_inr" to "")) }
        }
    }

    private suspend fun spent(ym: YearMonth, category: String): Double =
        snap("Expenses").mapNotNull { runCatching { Expense(it) }.getOrNull() }
            .filter { YearMonth.from(it.date) == ym && it.category == category }.sumOf { it.amount }

    suspend fun addExpense(date: LocalDate, amount: Double, category: String, note: String, source: String = "manual") {
        val ym = YearMonth.from(date)
        val before = spent(ym, category)
        put("Expenses", mapOf("date" to date.toString(), "amount_inr" to fmtNum(amount), "category" to category, "note" to note, "source" to source))
        checkBudget(ym, category, before, before + amount)
    }

    suspend fun checkBudget(ym: YearMonth, category: String, before: Double, after: Double) {
        val limit = snap("Budget").mapNotNull { runCatching { BudgetLine(it) }.getOrNull() }
            .firstOrNull { it.month == ym && it.category == category }?.limit ?: return
        if (limit <= 0 || settingNow("notif_money") == "FALSE") return
        val pb = before / limit; val pa = after / limit
        when {
            pb < 1.0 && pa >= 1.0 -> Notifier.money(ctx, "$category over budget", "${inr(after)} of ${inr(limit)} spent this month")
            pb < 0.8 && pa >= 0.8 -> Notifier.money(ctx, "$category at 80%", "${inr(after)} of ${inr(limit)}, ${inr(limit - after)} left")
        }
    }

    // ---------- import / export ----------
    data class Op(val action: String, val table: String, val row: Map<String, String>)
    data class ImportPlan(val ops: List<Op>, val adds: Int, val updates: Int, val completes: Int, val deletes: Int, val errors: List<String>) {
        val summary: String get() = buildList {
            if (adds > 0) add("adds $adds"); if (updates > 0) add("updates $updates")
            if (completes > 0) add("completes $completes"); if (deletes > 0) add("deletes $deletes")
            if (errors.isNotEmpty()) add("skips ${errors.size}")
        }.joinToString(", ").ifEmpty { "nothing to do" }
    }

    private fun JSONObject.toStrMap(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (k in keys()) m[k.trim().lowercase()] = jsonStr(opt(k))
        return m
    }

    suspend fun planImport(text: String): ImportPlan {
        val root = JSONObject(text.trim())
        val raw = mutableListOf<Op>()
        val errors = mutableListOf<String>()
        if (root.has("ops")) {
            val arr = root.getJSONArray("ops")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: run { errors += "op ${i + 1}: not an object"; null } ?: continue
                val t = Schema.canonical(o.optString("table"))
                if (t == null) { errors += "op ${i + 1}: unknown table '${o.optString("table")}'"; continue }
                raw += Op(o.optString("action").lowercase().trim(), t, o.optJSONObject("row")?.toStrMap() ?: emptyMap())
            }
        } else if (root.has("tables")) {
            val tabs = root.getJSONObject("tables")
            for (name in tabs.keys()) {
                val t = Schema.canonical(name) ?: run { errors += "unknown table '$name'"; null } ?: continue
                val arr = tabs.optJSONArray(name) ?: JSONArray()
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { raw += Op("upsert", t, it.toStrMap()) }
            }
        } else throw IllegalArgumentException("JSON needs an \"ops\" list")

        val ops = mutableListOf<Op>()
        var adds = 0; var updates = 0; var completes = 0; var deletes = 0
        raw.forEachIndexed { i, op ->
            val idc = Schema.idCol(op.table)
            val id = op.row[idc].orEmpty()
            val exists = id.isNotEmpty() && dao.get(op.table, id) != null
            val tag = "op ${i + 1} (${op.action} ${op.table})"
            when (op.action) {
                "add", "update", "upsert" -> {
                    val merged = if (exists) dao.get(op.table, id)!!.map() + op.row else op.row
                    val probe = merged.toMutableMap().apply { if (this[idc].isNullOrBlank()) this[idc] = "probe" }
                    try { Schema.normalise(op.table, probe) } catch (e: Exception) { errors += "$tag: ${e.message}"; return@forEachIndexed }
                    if (op.action == "update" && !exists) { errors += "$tag: id '$id' not found"; return@forEachIndexed }
                    if (exists) updates++ else adds++
                    ops += Op(if (exists) "update" else "add", op.table, op.row)
                }
                "complete" -> {
                    if (op.table != "Tasks") { errors += "$tag: complete only works on Tasks"; return@forEachIndexed }
                    if (!exists) { errors += "$tag: id '$id' not found"; return@forEachIndexed }
                    completes++; ops += op
                }
                "delete" -> {
                    if (!exists) { errors += "$tag: id '$id' not found"; return@forEachIndexed }
                    deletes++; ops += op
                }
                else -> errors += "$tag: action must be add, update, complete or delete"
            }
        }
        return ImportPlan(ops, adds, updates, completes, deletes, errors)
    }

    suspend fun applyImport(plan: ImportPlan): String {
        var ok = 0
        for (op in plan.ops) {
            val idc = Schema.idCol(op.table)
            val id = op.row[idc].orEmpty()
            try {
                when (op.action) {
                    "add" -> put(op.table, op.row)
                    "update" -> put(op.table, dao.get(op.table, id)!!.map() + op.row)
                    "complete" -> completeTask(id)
                    "delete" -> remove(op.table, id)
                }
                ok++
            } catch (e: Exception) {
                dao.log(SyncLogEntry(time = System.currentTimeMillis(), level = "SKIP", message = "Import ${op.action} ${op.table} $id: ${e.message}"))
            }
        }
        return "Imported $ok of ${plan.ops.size} changes"
    }

    suspend fun exportJson(): String {
        val tabs = JSONObject()
        for (t in Schema.tables.keys) {
            val arr = JSONArray()
            snap(t).forEach { arr.put(JSONObject(it as Map<*, *>)) }
            tabs.put(t, arr)
        }
        return JSONObject().put("exported_at", nowIso()).put("app", "Waypoint").put("tables", tabs).toString(2)
    }

    // ---------- sheet link ----------
    fun unlink() {
        sheetId = null
        prefs.edit().remove("replace_local").apply()
    }
}
