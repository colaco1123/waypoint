package com.shay.waypoint.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.mapLatest
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.shay.waypoint.data.*
import com.shay.waypoint.repo
import com.shay.waypoint.ui.MainActivity
import com.shay.waypoint.ui.habitToday
import com.shay.waypoint.ui.hexColor
import com.shay.waypoint.ui.streaks
import com.shay.waypoint.ui.workoutDates
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

// ---------------- data snapshot ----------------

data class WTask(val id: String, val title: String, val tag: String, val overdue: Boolean)

data class WData(
    val accent: Color, val dayLine: String, val tasks: List<WTask>, val dueCount: Int, val overdueCount: Int,
    val water: Int, val waterGoal: Int, val streak: Int, val nextLabel: String?, val nextTime: String?,
    val month: String, val spent: Double, val limit: Double, val topCat: String?, val topCatSpent: Double,
)

object WidgetData {
    suspend fun load(ctx: Context): WData {
        val repo = ctx.repo()
        val today = LocalDate.now()
        val now = LocalTime.now()
        val all = repo.tasksNow()
        val due = all.filter { !it.done && it.active && it.dueDate != null && !it.dueDate.isAfter(today) && !repo.isLocked(it, all) }
            .sortedBy { it.sortKey }
        val habits = repo.habitsNow()
        val log = repo.snap("HabitLog").mapNotNull { runCatching { HabitEntry(it) }.getOrNull() }
        val water = habitToday(habits, log, "water", today).toInt()
        val goal = repo.settingNow("water_goal").toDoubleOrNull()?.toInt() ?: 8
        val streak = streaks(workoutDates(habits, log), today).first

        // next routine stop
        val stops = mutableListOf<Pair<String, LocalTime>>()
        parseTimeLenient(repo.settingNow("wake_time"))?.let { stops += "Wake up" to it }
        habits.forEach { h -> h.times.distinct().forEach { stops += com.shay.waypoint.ui.habitStopLabel(h) to it } }
        parseTimeLenient(repo.settingNow("leave_time"))?.let { stops += "Leave" to it }
        repo.mealsNow().forEach { stops += it.name to it.time }
        parseTimeLenient(repo.settingNow("bedtime"))?.let { stops += "Bedtime" to it }
        val next = stops.sortedBy { it.second }.firstOrNull { !it.second.isBefore(now) }

        val ym = YearMonth.now()
        val lines = repo.snap("Budget").mapNotNull { runCatching { BudgetLine(it) }.getOrNull() }.filter { it.month == ym }
        val exps = repo.snap("Expenses").mapNotNull { runCatching { Expense(it) }.getOrNull() }.filter { YearMonth.from(it.date) == ym }
        val byCat = exps.groupBy { it.category }.mapValues { (_, l) -> l.sumOf { it.amount } }
        val top = byCat.maxByOrNull { it.value }

        return WData(
            accent = hexColor(repo.settingNow("accent_hex")),
            dayLine = "DAY %03d · %s".format(today.dayOfYear, fmtDay(today).uppercase()),
            tasks = due.take(5).map { t ->
                val over = t.dueDate!!.isBefore(today)
                WTask(t.id, t.title, if (over) "LATE" else t.dueTime?.let { fmtTime(it) } ?: "TODAY", over)
            },
            dueCount = due.size, overdueCount = due.count { it.dueDate!!.isBefore(today) },
            water = water, waterGoal = goal, streak = streak,
            nextLabel = next?.first, nextTime = next?.second?.let { fmtTime(it) },
            month = fmtMonth(ym).uppercase(), spent = exps.sumOf { it.amount }, limit = lines.sumOf { it.limit ?: 0.0 },
            topCat = top?.key, topCatSpent = top?.value ?: 0.0,
        )
    }
}

/** Re-emits a fresh snapshot whenever any table a widget shows changes in Room. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun widgetFlow(ctx: Context): kotlinx.coroutines.flow.Flow<WData> {
    val dao = ctx.repo().dao
    val tables = listOf("Tasks", "Habits", "HabitLog", "Meals", "Settings", "Budget", "Expenses")
    return kotlinx.coroutines.flow.merge(*tables.map { dao.observe(it) }.toTypedArray())
        .conflate()
        .mapLatest { WidgetData.load(ctx) }
}

@Composable
private fun rememberWidgetData(ctx: Context, initial: WData): WData {
    val flow = remember { widgetFlow(ctx) }
    val d by flow.collectAsState(initial)
    return d
}

object Widgets {
    suspend fun updateAll(ctx: Context) {
        runCatching { TodayWidget().updateAll(ctx) }
        runCatching { WaterWidget().updateAll(ctx) }
        runCatching { MoneyWidget().updateAll(ctx) }
    }
}

// ---------------- actions ----------------

val TaskIdKey = ActionParameters.Key<String>("task_id")

class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TaskIdKey] ?: return
        context.repo().completeTask(id)
        Widgets.updateAll(context)
    }
}

class AddWaterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        context.repo().addWater(1)
        Widgets.updateAll(context)
    }
}

// ---------------- shared look ----------------

private val BG = Color(0xFF000000)
private val S1 = Color(0xFF0D0D0D)
private val S2 = Color(0xFF1A1A1A)
private val TXT = Color(0xFFF2F2F2)
private val MUTE = Color(0xFF8A8A8A)
private val RED = Color(0xFFFF5A5A)

private fun mono(size: TextUnit, color: Color, bold: Boolean = false) =
    TextStyle(color = ColorProvider(color), fontSize = size, fontFamily = FontFamily.Monospace, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

private fun sans(size: TextUnit, color: Color, bold: Boolean = false) =
    TextStyle(color = ColorProvider(color), fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

private fun openApp(ctx: Context, what: String) =
    actionStartActivity(Intent(ctx, MainActivity::class.java).putExtra("open", what).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))

@Composable
private fun Segments(n: Int, filled: Int, accent: Color, height: Int = 8) {
    // Glance caps children per Row at 10, so segments are grouped in nested rows of 5.
    Row(GlanceModifier.fillMaxWidth()) {
        // equal-sized groups so every segment gets the same width
        val g = if (n <= 10) n else (2..10).firstOrNull { n % it == 0 && n / it <= 10 } ?: 5
        (0 until n).chunked(g).forEach { group ->
            Row(GlanceModifier.defaultWeight()) {
                group.forEach { i ->
                    Box(GlanceModifier.defaultWeight().padding(end = 2.dp)) {
                        Box(GlanceModifier.fillMaxWidth().height(height.dp).background(if (i < filled) accent else S2)) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(label: String, right: String, accent: Color) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("◆ $label", style = mono(11.sp, accent, bold = true))
        Spacer(GlanceModifier.defaultWeight())
        Text(right, style = mono(10.sp, MUTE))
    }
}

// ---------------- Today widget ----------------

class TodayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = WidgetData.load(context)
        provideContent { TodayContent(rememberWidgetData(context, d)) }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

@Composable
private fun TodayContent(d: WData) {
    val ctx = LocalContext.current
    Column(GlanceModifier.fillMaxSize().cornerRadius(18.dp).background(BG).padding(14.dp)) {
        Box(GlanceModifier.fillMaxWidth().clickable(openApp(ctx, "today"))) { Header("WAYPOINT", d.dayLine, d.accent) }
        Spacer(GlanceModifier.height(10.dp))
        Row(GlanceModifier.fillMaxWidth()) {
            Stat("DUE", "%02d".format(d.dueCount), if (d.overdueCount > 0) RED else TXT, GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(6.dp))
            Stat("WATER", "${d.water}/${d.waterGoal}", TXT, GlanceModifier.defaultWeight().clickable(actionRunCallback<AddWaterAction>()))
            Spacer(GlanceModifier.width(6.dp))
            Stat("STREAK", "${d.streak}d", TXT, GlanceModifier.defaultWeight())
        }
        if (d.nextLabel != null) {
            Spacer(GlanceModifier.height(8.dp))
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("NEXT ${d.nextTime}", style = mono(11.sp, d.accent, bold = true))
                Spacer(GlanceModifier.width(8.dp))
                Text(d.nextLabel, style = sans(13.sp, TXT), maxLines = 1)
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        // Glance allows at most 10 children per Column, so the task rows get their own Column.
        Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
        if (d.tasks.isEmpty()) {
            Text("Nothing due. Clear trail ahead.", style = sans(13.sp, MUTE))
        } else d.tasks.take(6).forEach { t ->
            // Only the diamond completes the task; the title opens the app, so a stray tap can't tick things off.
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    GlanceModifier.padding(start = 2.dp, end = 10.dp, top = 5.dp, bottom = 5.dp)
                        .clickable(actionRunCallback<CompleteTaskAction>(actionParametersOf(TaskIdKey to t.id))),
                ) { Text("◇", style = mono(16.sp, d.accent)) }
                Text(t.title, style = sans(13.sp, TXT), maxLines = 1,
                    modifier = GlanceModifier.defaultWeight().padding(vertical = 5.dp).clickable(openApp(ctx, "today")))
                Spacer(GlanceModifier.width(6.dp))
                Text(t.tag, style = mono(10.sp, if (t.overdue) RED else MUTE))
            }
        }
        }
        Row(GlanceModifier.fillMaxWidth()) {
            Button("+ TASK", d.accent, BG, GlanceModifier.defaultWeight().clickable(openApp(ctx, "capture")))
            Spacer(GlanceModifier.width(6.dp))
            Button("+1 GLASS", S2, TXT, GlanceModifier.defaultWeight().clickable(actionRunCallback<AddWaterAction>()))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, valueColor: Color, modifier: GlanceModifier) {
    Column(modifier.background(S1).cornerRadius(6.dp).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(label, style = mono(9.sp, MUTE))
        Text(value, style = mono(18.sp, valueColor, bold = true), maxLines = 1)
    }
}

@Composable
private fun Button(label: String, bg: Color, fg: Color, modifier: GlanceModifier) {
    Box(modifier.height(34.dp).background(bg).cornerRadius(6.dp), contentAlignment = Alignment.Center) {
        Text(label, style = mono(11.sp, fg, bold = true))
    }
}

// ---------------- Water widget ----------------

class WaterWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = WidgetData.load(context)
        provideContent { WaterContent(rememberWidgetData(context, d)) }
    }
}

class WaterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WaterWidget()
}

@Composable
private fun WaterContent(d: WData) {
    val goal = d.waterGoal.coerceIn(1, 16)
    Column(
        GlanceModifier.fillMaxSize().cornerRadius(18.dp).background(BG).padding(12.dp).clickable(actionRunCallback<AddWaterAction>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("◆ WATER", style = mono(10.sp, d.accent, bold = true))
        Spacer(GlanceModifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${d.water}", style = mono(34.sp, TXT, bold = true))
            Text("/${d.waterGoal}", style = mono(14.sp, MUTE))
        }
        Spacer(GlanceModifier.height(6.dp))
        Segments(goal, d.water, d.accent, 12)
        Spacer(GlanceModifier.height(6.dp))
        Text(if (d.water >= d.waterGoal) "GOAL REACHED" else "TAP · +1 GLASS", style = mono(9.sp, MUTE))
    }
}

// ---------------- Money widget ----------------

class MoneyWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = WidgetData.load(context)
        provideContent { MoneyContent(rememberWidgetData(context, d)) }
    }
}

class MoneyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MoneyWidget()
}

@Composable
private fun MoneyContent(d: WData) {
    val ctx = LocalContext.current
    val hasLimit = d.limit > 0
    val frac = if (hasLimit) (d.spent / d.limit).coerceIn(0.0, 1.0) else 0.0
    val over = hasLimit && d.spent > d.limit
    Column(GlanceModifier.fillMaxSize().cornerRadius(18.dp).background(BG).padding(12.dp).clickable(openApp(ctx, "money"))) {
        Header("MONEY", d.month.substringBefore(" "), d.accent)
        Spacer(GlanceModifier.height(6.dp))
        Text(if (hasLimit) inr(d.limit - d.spent) else inr(d.spent), style = mono(26.sp, if (over) RED else TXT, bold = true), maxLines = 1)
        Text(if (hasLimit) "LEFT OF ${inr(d.limit)}" else "SPENT · NO LIMITS SET", style = mono(9.sp, MUTE))
        Spacer(GlanceModifier.height(6.dp))
        Segments(16, Math.round(frac * 16).toInt(), if (over) RED else d.accent, 8)
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(d.topCat?.let { "TOP ${it.uppercase()} ${inr(d.topCatSpent)}" } ?: "NO SPENDING YET", style = mono(9.sp, MUTE), maxLines = 1,
                modifier = GlanceModifier.defaultWeight())
            Box(GlanceModifier.background(d.accent).cornerRadius(6.dp).padding(horizontal = 10.dp, vertical = 5.dp).clickable(openApp(ctx, "expense"))) {
                Text("+ ₹", style = mono(11.sp, BG, bold = true))
            }
        }
    }
}
