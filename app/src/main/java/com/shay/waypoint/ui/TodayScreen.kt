package com.shay.waypoint.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shay.waypoint.data.*
import java.time.LocalDate
import java.time.LocalTime

fun workoutDates(habits: List<Habit>, log: List<HabitEntry>): Set<LocalDate> {
    val h = habits.firstOrNull { it.type == "workout" } ?: return emptySet()
    return log.filter { it.habitId == h.id }.groupBy { it.date }.filter { (_, l) -> l.sumOf { it.value } >= h.target }.keys
}

fun streaks(dates: Set<LocalDate>, today: LocalDate): Pair<Int, Int> {
    var cur = 0
    var d = if (today in dates) today else today.minusDays(1)
    while (d in dates) { cur++; d = d.minusDays(1) }
    var best = 0; var run = 0; var prev: LocalDate? = null
    for (x in dates.sorted()) {
        run = if (prev != null && x == prev.plusDays(1)) run + 1 else 1
        best = maxOf(best, run); prev = x
    }
    return cur to maxOf(best, cur)
}

fun habitToday(habits: List<Habit>, log: List<HabitEntry>, type: String, day: LocalDate): Double {
    val h = habits.firstOrNull { it.type == type } ?: return 0.0
    return log.filter { it.habitId == h.id && it.date == day }.sumOf { it.value }
}

@Composable
fun SyncLine(repo: Repo) {
    val linked by repo.linked.collectAsState()
    val last by repo.lastSync.collectAsState()
    val err by repo.lastSyncError.collectAsState()
    val syncing by repo.syncing.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); now = System.currentTimeMillis() } }
    val (dot, text) = when {
        !linked -> Mute to "Offline mode · link a Google Sheet in Settings"
        syncing -> MaterialTheme.colorScheme.primary to "Syncing…"
        err != null -> RedC to "Sync problem: $err"
        last == 0L -> AmberC to "Not synced yet"
        else -> {
            val mins = (now - last) / 60_000
            GreenC to "Synced with Waypoint Data, " + if (mins < 1) "just now" else if (mins < 60) "$mins min ago" else "${mins / 60} h ago"
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        Diamond(7.dp, dot)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 11.sp, color = Mute, maxLines = 1, fontFamily = Mono)
    }
}

@Composable
fun Ring(frac: Float, label: String, size: Int = 46) {
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 5.dp.toPx()
            drawArc(S2, 0f, 360f, false, topLeft = Offset(sw / 2, sw / 2), size = Size(this.size.width - sw, this.size.height - sw), style = Stroke(sw))
            drawArc(accent, -90f, 360f * frac.coerceIn(0f, 1f), false, topLeft = Offset(sw / 2, sw / 2), size = Size(this.size.width - sw, this.size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
        }
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun StatBox(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(TagShape).background(S1).border(1.dp, LineC, TagShape).padding(vertical = 12.dp, horizontal = 10.dp).heightIn(min = 84.dp),
        horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.Center, content = content,
    )
}

@Composable
fun StatValue(v: String) = Text(v, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, letterSpacing = (-0.5).sp, maxLines = 1)

@Composable
fun StatLabel(v: String) = Text(v.uppercase(), fontSize = 9.sp, color = Mute, fontFamily = Mono, letterSpacing = 1.sp, maxLines = 1)

/** Routine as a route: time · waypoint diamond on a dotted trail · label. */
@Composable
fun RouteStop(b: Block, isNext: Boolean, last: Boolean, onLog: (() -> Unit)?) {
    val accent = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable { b.toggle() }) {
        Text(
            fmtTime(b.time), fontFamily = Mono, fontSize = 12.sp, color = if (isNext) accent else Mute,
            modifier = Modifier.width(48.dp).padding(top = 14.dp),
        )
        Box(Modifier.width(28.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            if (!last) Canvas(Modifier.fillMaxHeight().width(2.dp).padding(top = 24.dp)) {
                val dash = 4.dp.toPx(); var y = 0f
                while (y < size.height) { drawLine(Color(0xFF3A3A3A), Offset(size.width / 2, y), Offset(size.width / 2, minOf(y + dash, size.height)), 2.dp.toPx()); y += dash * 2 }
            }
            Box(Modifier.padding(top = 14.dp)) {
                Diamond(
                    if (isNext) 16.dp else 13.dp,
                    if (b.done) accent else if (isNext) Black else S1,
                    if (b.done || isNext) accent else Color(0xFF4A4A4A), 2.dp,
                )
            }
        }
        Row(Modifier.weight(1f).heightIn(min = 46.dp).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                b.label, fontSize = 15.sp, modifier = Modifier.weight(1f),
                color = if (b.done) Mute else TextC,
                textDecoration = if (b.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                fontWeight = if (isNext) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (isNext && !b.done) Pill("next")
            if (onLog != null) TextButton(onLog) { Text("LOG", fontSize = 11.sp, fontFamily = Mono) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WaterGlasses(filled: Int, goal: Int, onSet: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until maxOf(goal, filled)) {
            val f = i < filled
            val shape = CutCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
            Box(
                Modifier.size(30.dp, 38.dp).clip(shape)
                    .background(if (f) accent else Color.Transparent)
                    .border(2.dp, if (f) accent else Color(0xFF333333), shape)
                    .clickable { onSet(if (i < filled) i else i + 1) },
            )
        }
        Box(
            Modifier.size(30.dp, 38.dp).clip(TagShape).border(1.dp, LineC, TagShape).clickable { onSet(filled + 1) },
            contentAlignment = Alignment.Center,
        ) { Text("+", color = Mute, fontFamily = Mono) }
    }
}

data class Stop(val key: String, val label: String, val time: LocalTime, val done: Boolean, val habit: Habit? = null)

/**
 * The day's route: wake, workout, leave, meals, bedtime and one stop per habit reminder_time,
 * sorted by time. Works for any [date]; [ticks] are that day's local tick-offs.
 */
fun buildRoute(
    date: LocalDate, settings: Map<String, String>, habits: List<Habit>, hlog: List<HabitEntry>,
    meals: List<Meal>, food: List<FoodEntry>, ticks: Set<String>,
): List<Stop> {
    fun s(k: String) = settings[k]?.takeIf { it.isNotEmpty() } ?: Defaults.settings[k] ?: ""
    fun value(h: Habit) = hlog.filter { it.habitId == h.id && it.date == date }.sumOf { it.value }
    val wake = parseTimeLenient(s("wake_time")) ?: LocalTime.of(7, 0)
    val out = mutableListOf<Stop>()
    out += Stop("wake", "Wake up", wake, "wake" in ticks)
    // Workout without reminder_times keeps a fixed stop after wake-up; with times it comes from the habit stops.
    habits.firstOrNull { it.type == "workout" }?.takeIf { it.times.isEmpty() }?.let { w ->
        out += Stop("workout", habitStopLabel(w), wake.plusMinutes(10), value(w) >= w.target)
    }
    parseTimeLenient(s("leave_time"))?.let { out += Stop("leave", "Leave", it, "leave" in ticks) }
    meals.forEach { m ->
        val k = "meal:${m.name}"
        out += Stop(k, m.name, m.time, k in ticks || food.any { it.date == date && it.slot == m.name })
    }
    parseTimeLenient(s("bedtime"))?.let { out += Stop("bedtime", "Bedtime", it, "bedtime" in ticks) }
    // One stop per reminder_time of every habit row (Skincare 07:30 and 22:45 = two stops).
    habits.filter { it.times.isNotEmpty() }.forEach { h ->
        val v = value(h)
        val times = h.times.distinct().sorted()
        // Slots ticked in the app are tracked per slot. Any extra logged value (from the sheet, the +1
        // buttons or another device) fills the remaining slots in time order.
        var extra = (v - times.count { habitSlotKey(h.id, it) in ticks }).toInt().coerceAtLeast(0)
        times.forEach { t ->
            val key = habitSlotKey(h.id, t)
            val done = when {
                h.type == "workout" -> v >= h.target
                key in ticks -> true
                extra > 0 -> { extra--; true }
                else -> false
            }
            out += Stop(key, habitStopLabel(h), t, done, h)
        }
    }
    return out.sortedBy { it.time }
}

/** Route / notification label for a habit reminder. */
fun habitStopLabel(h: Habit): String = when (h.type) {
    "workout" -> "${h.name} · ${fmtNum(h.target)} ${h.unit}".trim()
    "water" -> "${h.name} · +1 glass"
    else -> h.name
}

data class Block(val key: String, val label: String, val time: LocalTime, val done: Boolean, val toggle: () -> Unit)

@Composable
fun TodayScreen(repo: Repo, onOpenTask: (Task) -> Unit, onSwipeLeft: (Task) -> Unit, onLogMeal: (String) -> Unit) {
    val today by repo.today.collectAsState()
    val tasks by repo.tasks.collectAsState()
    val habits by repo.habits.collectAsState()
    val hlog by repo.habitLog.collectAsState()
    val meals by repo.meals.collectAsState()
    val food by repo.foodLog.collectAsState()
    val settings by repo.settings.collectAsState()
    val ticks by repo.ticks.collectAsState()
    fun s(k: String) = settings[k]?.takeIf { it.isNotEmpty() } ?: Defaults.settings[k] ?: ""

    val open = tasks.filter { !it.done && it.active && !repo.isLocked(it, tasks) }
    val overdue = open.filter { it.dueDate != null && it.dueDate.isBefore(today) }
    val dueToday = open.filter { it.dueDate == today }
    val upcoming = open.filter { it.dueDate != null && it.dueDate.isAfter(today) && !it.dueDate.isAfter(today.plusDays(7)) }

    val (streak, _) = streaks(workoutDates(habits, hlog), today)
    val kcal = food.filter { it.date == today }.sumOf { it.kcal }
    val kcalTarget = s("calorie_target").toDoubleOrNull()
    val water = habitToday(habits, hlog, "water", today).toInt()
    val waterGoal = s("water_goal").toDoubleOrNull()?.toInt() ?: 8
    val workout = habits.firstOrNull { it.type == "workout" }
    val workoutDone = workout != null && habitToday(habits, hlog, "workout", today) >= workout.target

    val blocks = buildRoute(today, settings, habits, hlog, meals, food, ticks).map { st ->
        Block(st.key, st.label, st.time, st.done) {
            when {
                st.habit != null -> repo.launch { repo.tickHabitSlot(st.habit, st.time, !st.done) }
                st.key == "workout" -> repo.launch { repo.setWorkoutDone(today, !st.done) }
                else -> repo.setTick(st.key, st.key !in ticks)
            }
        }
    }
    val nowT = LocalTime.now()
    val nextKey = blocks.firstOrNull { !it.done && !it.time.isBefore(nowT.minusMinutes(30)) }?.key

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp)) {
        item { SyncLine(repo) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                StatBox(Modifier.weight(1f)) {
                    StatLabel("Streak")
                    StatValue("${streak}d")
                    ProgressBar((streak % 7) / 7f, modifier = Modifier.padding(top = 6.dp).height(5.dp))
                }
                StatBox(Modifier.weight(1f)) {
                    StatLabel(if (kcalTarget != null && kcalTarget > 0) "kcal ${(kcal * 100 / kcalTarget).toInt()}%" else "kcal")
                    StatValue(fmtNum(kcal))
                    ProgressBar(if (kcalTarget != null && kcalTarget > 0) (kcal / kcalTarget).toFloat() else 0f, modifier = Modifier.padding(top = 6.dp).height(5.dp))
                }
                StatBox(Modifier.weight(1f).clickable { repo.launch { repo.addWater(1) } }) {
                    StatLabel("Water +1")
                    StatValue("$water/$waterGoal")
                    ProgressBar(water / waterGoal.coerceAtLeast(1).toFloat(), modifier = Modifier.padding(top = 6.dp).height(5.dp))
                }
            }
        }
        if (overdue.isNotEmpty()) item {
            WCard(label = "Overdue · ${overdue.size}") {
                overdue.forEachIndexed { i, t -> TaskRow(t, repo, false, today, onOpenTask, onSwipeLeft, divider = i < overdue.lastIndex) }
            }
        }
        item {
            WCard(label = "Due today") {
                if (dueToday.isEmpty()) MutedText("Nothing due today. Swipe right on a task to complete it.")
                dueToday.forEachIndexed { i, t -> TaskRow(t, repo, false, today, onOpenTask, onSwipeLeft, divider = i < dueToday.lastIndex) }
            }
        }
        if (upcoming.isNotEmpty()) item {
            WCard(label = "Coming up") {
                upcoming.take(8).forEachIndexed { i, t -> TaskRow(t, repo, false, today, onOpenTask, onSwipeLeft, divider = i < minOf(upcoming.size, 8) - 1) }
            }
        }
        item {
            WCard(label = "Route · ${blocks.count { it.done }}/${blocks.size}") {
                blocks.forEachIndexed { i, b ->
                    RouteStop(b, b.key == nextKey, i == blocks.lastIndex,
                        if (b.key.startsWith("meal:") && !b.done) ({ onLogMeal(b.label) }) else null)
                }
            }
        }
        item {
            WCard(label = "Water · tap a glass") {
                WaterGlasses(water, waterGoal) { n ->
                    repo.launch { repo.habitOfType("water")?.let { repo.setHabit(it.id, today, n.toDouble()) } }
                }
            }
        }
    }
}
