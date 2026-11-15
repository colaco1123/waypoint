package com.shay.waypoint.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shay.waypoint.data.*
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

@Composable
fun Heatmap(month: YearMonth, dates: Set<LocalDate>, today: LocalDate) {
    val accent = MaterialTheme.colorScheme.primary
    val first = month.atDay(1)
    val lead = first.dayOfWeek.value - 1
    val cells = lead + month.lengthOfMonth()
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), fontSize = 10.sp, color = Mute) }
        }
        for (w in 0 until (cells + 6) / 7) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (d in 0 until 7) {
                    val idx = w * 7 + d - lead
                    val day = if (idx in 0 until month.lengthOfMonth()) month.atDay(idx + 1) else null
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(5.dp)).background(
                            when {
                                day == null -> Color.Transparent
                                day in dates -> accent
                                day == today -> accent.copy(alpha = .25f)
                                else -> S2
                            },
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Month grid where each day is a square split into one section per route stop.
 * Sections fill green from the bottom up as stops are done; a complete day is solid green.
 */
@Composable
fun RouteHeatmap(month: YearMonth, today: LocalDate, score: (LocalDate) -> Pair<Int, Int>?, selected: LocalDate?, onSelect: (LocalDate) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val lead = month.atDay(1).dayOfWeek.value - 1
    val cells = lead + month.lengthOfMonth()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), fontSize = 10.sp, color = Mute, fontFamily = Mono) }
        }
        for (w in 0 until (cells + 6) / 7) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (d in 0 until 7) {
                    val idx = w * 7 + d - lead
                    val day = if (idx in 0 until month.lengthOfMonth()) month.atDay(idx + 1) else null
                    val sc = day?.let { score(it) }
                    Box(
                        Modifier.weight(1f).aspectRatio(1f)
                            .let { if (day != null && sc != null) it.clickable { onSelect(day) } else it },
                    ) {
                        if (day != null) RouteCell(
                            done = sc?.first ?: 0, total = sc?.second ?: 0, future = sc == null,
                            outline = when { day == selected -> TextC; day == today -> accent; else -> null },
                        )
                    }
                }
            }
        }
        // legend
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text("NONE", fontSize = 9.sp, color = Mute, fontFamily = Mono)
            Spacer(Modifier.width(6.dp))
            listOf(0 to 8, 3 to 8, 6 to 8, 8 to 8).forEach { (dn, tt) ->
                Box(Modifier.size(16.dp)) { RouteCell(dn, tt, false, null) }
                Spacer(Modifier.width(4.dp))
            }
            Text("ALL STOPS", fontSize = 9.sp, color = Mute, fontFamily = Mono)
        }
    }
}

@Composable
fun RouteCell(done: Int, total: Int, future: Boolean, outline: Color?) {
    Canvas(Modifier.fillMaxSize()) {
        val rad = CornerRadius(4.dp.toPx())
        when {
            future || total == 0 -> drawRoundRect(S2.copy(alpha = 0.5f), cornerRadius = rad)
            done >= total -> drawRoundRect(GreenC, cornerRadius = rad)
            else -> {
                drawRoundRect(Color(0xFF0F1A14), cornerRadius = rad)
                // prefer a grid that divides evenly (10 stops = 5x2) so every row is full
                val root = kotlin.math.sqrt(total.toDouble())
                val cols = (kotlin.math.ceil(root).toInt()..maxOf(kotlin.math.ceil(root).toInt(), (root * 2).toInt()))
                    .firstOrNull { total % it == 0 } ?: kotlin.math.ceil(root).toInt()
                val rows = (total + cols - 1) / cols
                val pad = 2.dp.toPx(); val gap = 1.dp.toPx()
                val cw = (size.width - 2 * pad - gap * (cols - 1)) / cols
                val ch = (size.height - 2 * pad - gap * (rows - 1)) / rows
                for (i in 0 until total) {
                    val r = rows - 1 - i / cols   // fill from the bottom row up
                    val c = i % cols
                    drawRect(
                        if (i < done) GreenC else GreenC.copy(alpha = 0.10f),
                        topLeft = Offset(pad + c * (cw + gap), pad + r * (ch + gap)), size = Size(cw, ch),
                    )
                }
            }
        }
        if (outline != null) drawRoundRect(outline, cornerRadius = rad, style = Stroke(1.5.dp.toPx()))
    }
}

@Composable
fun LineChart(points: List<Pair<LocalDate, Double>>, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    if (points.size < 2) { MutedText(if (points.isEmpty()) "No entries yet" else "Add another entry to see a trend"); return }
    val minV = points.minOf { it.second }; val maxV = points.maxOf { it.second }
    val span = (maxV - minV).takeIf { it > 0.01 } ?: 1.0
    val d0 = points.first().first.toEpochDay(); val dSpan = (points.last().first.toEpochDay() - d0).coerceAtLeast(1)
    Canvas(modifier.fillMaxWidth().height(90.dp)) {
        val pad = 6.dp.toPx()
        fun pt(p: Pair<LocalDate, Double>) = Offset(
            pad + (size.width - 2 * pad) * ((p.first.toEpochDay() - d0).toFloat() / dSpan),
            pad + (size.height - 2 * pad) * (1f - ((p.second - minV) / span).toFloat()),
        )
        val path = Path()
        points.forEachIndexed { i, p -> val o = pt(p); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, accent, style = Stroke(2.5.dp.toPx()))
        points.forEach { drawCircle(accent, 3.dp.toPx(), pt(it)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FoodSheet(repo: Repo, initialSlot: String?, onDismiss: () -> Unit) {
    val meals by repo.meals.collectAsState()
    val foods by repo.foods.collectAsState()
    val nowT = LocalTime.now()
    var slot by remember {
        mutableStateOf(initialSlot ?: meals.lastOrNull { !it.time.isAfter(nowT.plusMinutes(45)) }?.name ?: meals.firstOrNull()?.name ?: "Snack")
    }
    var q by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Foods.F?>(null) }
    var servings by remember { mutableDoubleStateOf(1.0) }
    var custom by remember { mutableStateOf(false) }
    var cName by remember { mutableStateOf("") }
    var cK by remember { mutableStateOf("") }
    var cP by remember { mutableStateOf("") }

    val customFoods = foods.filter { it.custom }.map { Foods.F(it.name, it.kcal, it.protein) }
    val catalogue = (customFoods + Foods.all).distinctBy { it.name }
    val favs = foods.filter { it.favourite }.map { it.name }.toSet()
    val list = if (q.isBlank()) {
        val recent = foods.filter { it.lastUsed > 0 }.sortedByDescending { it.lastUsed }.take(8).map { f -> catalogue.find { it.name == f.name } ?: Foods.F(f.name, f.kcal, f.protein) }
        val fav = catalogue.filter { it.name in favs }
        (fav + recent + catalogue).distinctBy { it.name }
    } else catalogue.filter { it.name.contains(q.trim(), ignoreCase = true) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = S1) {
        Column(Modifier.padding(horizontal = 16.dp).fillMaxHeight(0.92f).imePadding()) {
            Text("Log food", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (meals.map { it.name }.ifEmpty { listOf("Breakfast", "Lunch", "Snack", "Dinner") }).forEach { n ->
                    FilterChip(slot == n, { slot = n }, { Text(n) })
                }
            }
            val p = picked
            if (p != null) {
                WCard {
                    Text(p.name, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ servings = maxOf(0.5, servings - 0.5) }) { Text("−", fontSize = 20.sp) }
                        Text("× ${fmtNum(servings)}", fontSize = 16.sp)
                        TextButton({ servings += 0.5 }) { Text("+", fontSize = 20.sp) }
                        Spacer(Modifier.weight(1f))
                        Text("${fmtNum(Math.round(p.kcal * servings).toDouble())} kcal · ${fmtNum(Math.round(p.protein * servings * 10) / 10.0)} g", color = Mute, fontSize = 13.sp)
                    }
                    Row {
                        TextButton({ picked = null }) { Text("Cancel") }
                        Spacer(Modifier.weight(1f))
                        Button({
                            val name = if (servings == 1.0) p.name else "${p.name} ×${fmtNum(servings)}"
                            repo.launch {
                                repo.logFood(LocalDate.now(), slot, name, Math.round(p.kcal * servings).toDouble(), Math.round(p.protein * servings * 10) / 10.0, p.kcal, p.protein, p.name)
                                repo.setTick("meal:$slot", true)
                                repo.toast("Logged ${p.name} · $slot")
                            }
                            onDismiss()
                        }) { Text("Log") }
                    }
                }
            } else if (custom) {
                WCard(label = "Custom food (per serving)") {
                    OutlinedTextField(cName, { cName = it }, label = { Text("Name, e.g. Mom's thali") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(cK, { cK = it }, label = { Text("kcal") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(cP, { cP = it }, label = { Text("protein g") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                    Row {
                        TextButton({ custom = false }) { Text("Cancel") }
                        Spacer(Modifier.weight(1f))
                        Button({
                            val k = parseNum(cK) ?: 0.0; val pr = parseNum(cP) ?: 0.0
                            repo.launch { repo.addCustomFood(cName.trim(), k, pr) }
                            custom = false; picked = Foods.F(cName.trim(), k, pr); servings = 1.0
                        }, enabled = cName.isNotBlank() && parseNum(cK) != null) { Text("Save") }
                    }
                }
            } else {
                OutlinedTextField(q, { q = it }, placeholder = { Text("Search foods (dal, roti, poha…)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                TextButton({ custom = true; cName = q }) { Text("+ Custom food") }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.name }) { f ->
                    ListRow(
                        f.name, "${fmtNum(f.kcal)} kcal · ${fmtNum(f.protein)} g protein",
                        onClick = { picked = f; servings = 1.0 },
                        trailing = {
                            IconButton({ repo.launch { repo.toggleFavourite(f.name, f.kcal, f.protein) } }) {
                                Icon(Icons.Filled.Star, "Favourite", tint = if (f.name in favs) AmberC else Color(0xFF333333))
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HealthScreen(repo: Repo, onLogFood: (String?) -> Unit) {
    val today by repo.today.collectAsState()
    val habits by repo.habits.collectAsState()
    val hlog by repo.habitLog.collectAsState()
    val meals by repo.meals.collectAsState()
    val food by repo.foodLog.collectAsState()
    val weights by repo.weights.collectAsState()
    val settings by repo.settings.collectAsState()
    fun s(k: String) = settings[k]?.takeIf { it.isNotEmpty() } ?: Defaults.settings[k] ?: ""

    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selDay by remember { mutableStateOf<LocalDate?>(today) }
    val ticksToday by repo.ticks.collectAsState()
    var logWorkout by remember { mutableStateOf(false) }
    var addWeight by remember { mutableStateOf(false) }
    var sleepBed by remember { mutableStateOf<LocalTime?>(null) }
    var pickSleep by remember { mutableIntStateOf(0) } // 1 = bedtime, 2 = wake
    var customLog by remember { mutableStateOf<Habit?>(null) }

    val todayFood = food.filter { it.date == today }
    val kcal = todayFood.sumOf { it.kcal }; val protein = todayFood.sumOf { it.protein }
    val kT = s("calorie_target").toDoubleOrNull(); val pT = s("protein_target").toDoubleOrNull()
    val workout = habits.firstOrNull { it.type == "workout" }
    val wDates = workoutDates(habits, hlog)
    val (cur, best) = streaks(wDates, today)
    val workoutToday = habitToday(habits, hlog, "workout", today)
    val water = habitToday(habits, hlog, "water", today).toInt()
    val waterGoal = s("water_goal").toDoubleOrNull()?.toInt() ?: 8
    val sleepH = habits.firstOrNull { it.type == "sleep" }
    val sleepLog = sleepH?.let { h -> hlog.filter { it.habitId == h.id }.sortedByDescending { it.date }.take(7) }.orEmpty()
    val customs = habits.filter { it.type == "custom" }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp)) {
        item { SyncLine(repo) }
        item {
            WCard(label = "Calories today") {
                BigNumber(fmtNum(kcal), if (kT != null) "/ ${fmtNum(kT)} kcal" else "kcal")
                if (kT != null && kT > 0) ProgressBar((kcal / kT).toFloat(), if (kcal > kT) RedC else MaterialTheme.colorScheme.primary, Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Protein ${fmtNum(Math.round(protein).toDouble())}${pT?.let { " / ${fmtNum(it)}" } ?: ""} g", fontSize = 12.sp, color = Mute, modifier = Modifier.weight(1f))
                    Text(if (kT != null) "${fmtNum(maxOf(0.0, kT - kcal))} kcal left" else "Set targets in Settings", fontSize = 12.sp, color = Mute)
                }
            }
        }
        item {
            WCard(label = "Meals") {
                meals.forEachIndexed { i, m ->
                    val items = todayFood.filter { it.slot == m.name }
                    ListRow(
                        m.name, if (items.isEmpty()) fmtTime(m.time) else items.joinToString(", ") { it.food },
                        divider = i < meals.lastIndex,
                        trailing = {
                            if (items.isNotEmpty()) Text("${fmtNum(items.sumOf { it.kcal })} kcal", fontSize = 12.sp, color = Mute)
                            TextButton({ onLogFood(m.name) }) { Text("+ Log", fontSize = 12.sp) }
                        },
                    )
                }
            }
        }
        if (todayFood.isNotEmpty()) item {
            WCard(label = "Food log") {
                todayFood.forEachIndexed { i, f ->
                    ListRow(
                        f.food, "${f.slot} · ${fmtNum(f.protein)} g protein", divider = i < todayFood.lastIndex,
                        trailing = {
                            Text("${fmtNum(f.kcal)} kcal", fontSize = 12.sp, color = Mute)
                            IconButton({ repo.launch { repo.remove("FoodLog", f.id) } }) { Icon(Icons.Filled.Close, "Remove", tint = Mute, modifier = Modifier.size(18.dp)) }
                        },
                    )
                }
            }
        }
        item {
            val routes = remember(month, settings, habits, hlog, meals, food, ticksToday, today) {
                (1..month.lengthOfMonth()).map { month.atDay(it) }.filter { !it.isAfter(today) && !it.isBefore(repo.firstDay) }.associateWith { d ->
                    buildRoute(d, settings, habits, hlog, meals, food, if (d == today) ticksToday else repo.ticksFor(d))
                }
            }
            val sel = selDay?.let { routes[it] }
            val fullDays = routes.values.count { r -> r.isNotEmpty() && r.all { it.done } }
            val avg = routes.values.filter { it.isNotEmpty() }.map { r -> r.count { it.done } * 100 / r.size }.average().takeIf { !it.isNaN() } ?: 0.0
            WCard(label = "Daily route", trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                    IconButton({ month = month.minusMonths(1) }, Modifier.size(28.dp)) { Icon(Icons.Filled.KeyboardArrowLeft, null, tint = Mute) }
                    Text(fmtMonth(month), fontSize = 12.sp, color = Mute, fontFamily = Mono)
                    IconButton({ month = month.plusMonths(1) }, Modifier.size(28.dp)) { Icon(Icons.Filled.KeyboardArrowRight, null, tint = Mute) }
                }
            }) {
                RouteHeatmap(month, today, { d -> routes[d]?.let { r -> r.count { it.done } to r.size } }, selDay) { selDay = it }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text("FULL DAYS $fullDays · AVG ${avg.toInt()}%", fontSize = 11.sp, color = Mute, fontFamily = Mono, modifier = Modifier.weight(1f))
                    Text("WORKOUT STREAK $cur · BEST $best", fontSize = 11.sp, color = Mute, fontFamily = Mono)
                }
                if (selDay != null && sel != null) {
                    HorizontalDivider(color = LineC, modifier = Modifier.padding(vertical = 10.dp))
                    Text(
                        "${fmtDay(selDay!!).uppercase()} · ${sel.count { it.done }}/${sel.size} STOPS",
                        fontSize = 12.sp, fontFamily = Mono, color = if (sel.isNotEmpty() && sel.all { it.done }) GreenC else TextC,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                        sel.forEach { st -> Pill("${fmtTime(st.time)} ${st.label.substringBefore(" · ")}", if (st.done) GreenC else Mute) }
                    }
                }
                if (workout != null) Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Workout · min ${fmtNum(workout.target)} ${workout.unit}", fontSize = 13.sp, color = Mute, modifier = Modifier.weight(1f))
                    if (workoutToday >= workout.target) TextButton({ repo.launch { repo.setWorkoutDone(today, false) } }) { Text("✓ Done today", fontSize = 12.sp) }
                    else Button({ logWorkout = true }) { Text("Log workout") }
                }
            }
        }
        item {
            WCard(label = "Water · $water / $waterGoal glasses") {
                WaterGlasses(water, waterGoal) { n -> repo.launch { repo.habitOfType("water")?.let { repo.setHabit(it.id, today, n.toDouble()) } } }
                Text("Reminders every ${s("water_interval_hours")} h between ${s("wake_time")} and ${s("bedtime")}", fontSize = 12.sp, color = Mute, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (sleepH != null) item {
            WCard(label = "Sleep", trailing = { TextButton({ pickSleep = 1 }, Modifier.padding(bottom = 4.dp)) { Text("+ Log night", fontSize = 12.sp) } }) {
                Text("Bedtime ${s("bedtime")} · wind-down ${s("winddown_minutes")} min before", fontSize = 13.sp, color = Mute)
                sleepLog.forEach { e -> ListRow(fmtDay(e.date), null, trailing = { Text("${fmtNum(e.value)} h", fontSize = 13.sp, color = Mute) }, divider = false) }
            }
        }
        customs.forEach { h ->
            item(key = h.id) {
                val v = hlog.filter { e -> e.habitId == h.id && e.date == today }.sumOf { it.value }
                WCard(label = h.name) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${fmtNum(v)} / ${fmtNum(h.target)} ${h.unit}", Modifier.weight(1f))
                        OutlinedButton({ repo.launch { repo.setHabit(h.id, today, maxOf(0.0, v - 1)) } }) { Text("−") }
                        Spacer(Modifier.width(8.dp))
                        Button({ repo.launch { repo.setHabit(h.id, today, v + 1) } }) { Text("+1") }
                    }
                    ProgressBar((v / h.target).toFloat(), modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        item {
            WCard(label = "Weight", trailing = { TextButton({ addWeight = true }, Modifier.padding(bottom = 4.dp)) { Text("+ Add", fontSize = 12.sp) } }) {
                LineChart(weights.takeLast(30).map { it.date to it.kg })
                weights.lastOrNull()?.let { Text("Latest ${fmtNum(it.kg)} kg · ${fmtDay(it.date)}", fontSize = 13.sp, color = Mute, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
    }

    if (logWorkout && workout != null) TextDialog("Workout minutes", fmtNum(workout.target), { logWorkout = false }, number = true) { v ->
        parseNum(v)?.let { m -> repo.launch { repo.setHabit(workout.id, today, m); if (m < workout.target) repo.toast("Below the ${fmtNum(workout.target)} min minimum; streak needs ${fmtNum(workout.target)}") } }
    }
    if (addWeight) TextDialog("Weight (kg)", weights.lastOrNull()?.kg?.let(::fmtNum) ?: "", { addWeight = false }, number = true) { v ->
        parseNum(v)?.let { kg ->
            repo.launch {
                val existing = weights.firstOrNull { it.date == today }
                if (existing != null) repo.update("Weight", existing.id, "kg" to fmtNum(kg))
                else repo.put("Weight", mapOf("date" to today.toString(), "kg" to fmtNum(kg)))
            }
        }
    }
    if (pickSleep == 1) TimeDialog("Went to bed at", parseTimeLenient(s("bedtime")), { if (pickSleep == 1) pickSleep = 0 }) { sleepBed = it; pickSleep = 2 }
    if (pickSleep == 2) TimeDialog("Woke up at", parseTimeLenient(s("wake_time")), { pickSleep = 0 }) { w ->
        val b = sleepBed ?: return@TimeDialog
        var mins = Duration.between(b, w).toMinutes()
        if (mins <= 0) mins += 24 * 60
        val hours = Math.round(mins / 6.0) / 10.0
        sleepH?.let { h -> repo.launch { repo.setHabit(h.id, today, hours); repo.toast("Slept ${fmtNum(hours)} h") } }
        pickSleep = 0
    }
}
