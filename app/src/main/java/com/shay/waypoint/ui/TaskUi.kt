package com.shay.waypoint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shay.waypoint.data.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

fun taskSub(t: Task, today: LocalDate, showBucket: Boolean = true): String = buildList {
    if (showBucket && t.bucket.isNotEmpty()) add(t.bucket)
    if (t.project.isNotEmpty()) add(t.project)
    t.dueDate?.let { add(fmtRelDay(it, today)) }
    t.dueTime?.let { add(fmtTime(it)) }
    if (t.recurrence != "none") add(Recurrence.describe(t.recurrence))
    if (t.watch && t.dueDate == null) add("watch for date")
    if (t.chainId.isNotEmpty()) add(t.chainId)
    if (t.dueDate == null && !t.watch && t.notes.isNotEmpty()) add(t.notes)
}.joinToString(" · ")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskRow(
    t: Task, repo: Repo, locked: Boolean, today: LocalDate, onOpen: (Task) -> Unit, onSwipeLeft: (Task) -> Unit,
    showBucket: Boolean = true, divider: Boolean = true,
) = key(t.id) {
    // key(t.id): swipe state belongs to this task, not to a list position, so a reordered list
    // can never route a swipe to a different task.
    val accent = MaterialTheme.colorScheme.primary
    val task by rememberUpdatedState(t)
    val isLocked by rememberUpdatedState(locked)
    val swipeLeft by rememberUpdatedState(onSwipeLeft)
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        v != SwipeToDismissBoxValue.StartToEnd || (!isLocked && !task.done)
    })
    // Once the swipe has settled: run the action first (nothing here can be cancelled before it runs),
    // then snap the row back without an animation that a dialog could interrupt.
    LaunchedEffect(state.currentValue) {
        when (state.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> {
                val id = task.id
                repo.launch { repo.completeTask(id)?.let { repo.toast(it) } }
                state.snapTo(SwipeToDismissBoxValue.Settled)
            }
            SwipeToDismissBoxValue.EndToStart -> {
                swipeLeft(task)
                state.snapTo(SwipeToDismissBoxValue.Settled)
            }
            else -> {}
        }
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = !locked && !t.done,
        backgroundContent = {
            val dir = state.dismissDirection
            Row(
                Modifier.fillMaxSize().background(if (dir == SwipeToDismissBoxValue.StartToEnd) accent.copy(alpha = .25f) else AmberC.copy(alpha = .2f)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (dir == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start else Arrangement.End,
            ) { Text(if (dir == SwipeToDismissBoxValue.StartToEnd) "Complete" else "Snooze / edit", fontSize = 13.sp) }
        },
    ) {
        Box(Modifier.background(S1)) {
            ListRow(
                title = (if (locked) "🔒 " else "") + t.title,
                sub = taskSub(t, today, showBucket),
                done = t.done, muted = locked || !t.active,
                leading = {
                    CheckDot(t.done, locked) {
                        repo.launch {
                            if (t.done) repo.reopenTask(t.id) else repo.completeTask(t.id)?.let { repo.toast(it) }
                        }
                    }
                },
                trailing = {
                    when {
                        t.done -> {}
                        t.dueDate != null && t.dueDate.isBefore(today) -> Pill("Overdue", RedC)
                        t.dueDate == today -> Pill("Today", RedC)
                        t.priority == "high" -> Pill("High")
                        t.dueDate != null && t.dueDate.isBefore(today.plusDays(3)) -> Pill(fmtRelDay(t.dueDate, today), AmberC)
                    }
                },
                onClick = { onOpen(t) },
                divider = divider,
            )
        }
    }
}

@Composable
fun TaskActionsDialog(t: Task, repo: Repo, onEdit: (Task) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = S2,
        title = { Text(shortTitle(t.title, 40)) },
        text = {
            Column {
                listOf("Snooze to tomorrow" to 1L, "Snooze 3 days" to 3L, "Snooze 1 week" to 7L).forEach { (label, d) ->
                    TextButton({ repo.launch { repo.snoozeTaskDays(t.id, d); repo.toast("Moved to ${fmtDay(maxOf(t.dueDate ?: LocalDate.now(), LocalDate.now()).plusDays(d))}") }; onDismiss() }, Modifier.fillMaxWidth()) {
                        Text(label, Modifier.fillMaxWidth())
                    }
                }
                TextButton({ onEdit(t); onDismiss() }, Modifier.fillMaxWidth()) { Text("Edit", Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipRow(options: List<T>, selected: T?, label: (T) -> String, onPick: (T?) -> Unit, allowNone: Boolean = true) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onPick(if (o == selected && allowNone) null else o) },
                label = { Text(label(o)) },
            )
        }
    }
}

/** Full task editor. [t] null = new task. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskEditor(t: Task?, repo: Repo, onDismiss: () -> Unit, defaultBucket: String? = null) {
    val buckets by repo.buckets.collectAsState()
    var title by remember { mutableStateOf(t?.title ?: "") }
    var bucket by remember { mutableStateOf(t?.bucket ?: defaultBucket ?: "") }
    var project by remember { mutableStateOf(t?.project ?: "") }
    var notes by remember { mutableStateOf(t?.notes ?: "") }
    var date by remember { mutableStateOf(t?.dueDate) }
    var time by remember { mutableStateOf(t?.dueTime) }
    var priority by remember { mutableStateOf(t?.priority ?: "med") }
    var remind by remember { mutableStateOf(t?.m?.s("remind_minutes_before") ?: "") }
    var rec by remember { mutableStateOf(t?.recurrence ?: "none") }
    var chain by remember { mutableStateOf(t?.chainId ?: "") }
    var order by remember { mutableStateOf(t?.m?.s("chain_order") ?: "") }
    var watch by remember { mutableStateOf(t?.watch ?: false) }
    var active by remember { mutableStateOf(t?.active ?: true) }
    var done by remember { mutableStateOf(t?.done ?: false) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = S1) {
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (t == null) "New task" else "Edit task", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
            SectionLabel("Bucket")
            ChipRow(buckets.map { it.name }, bucket.ifEmpty { null }, { it }, { bucket = it ?: "" })
            OutlinedTextField(project, { project = it }, label = { Text("Project (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            SectionLabel("Due")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip({ pickDate = true }, { Text(date?.let { fmtDay(it) } ?: "No date") }, leadingIcon = { Icon(Icons.Filled.DateRange, null, Modifier.size(16.dp)) })
                AssistChip({ pickTime = true }, { Text(time?.let { fmtTime(it) } ?: "No time") })
                if (date != null) AssistChip({ date = null; time = null }, { Text("Clear") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Watch for date (unknown yet)", Modifier.weight(1f)); Switch(watch, { watch = it })
            }
            SectionLabel("Priority")
            ChipRow(listOf("low", "med", "high"), priority, { it.replaceFirstChar { c -> c.uppercase() } }, { priority = it ?: "med" }, allowNone = false)
            SectionLabel("Reminders (minutes before)")
            val presets = listOf("0" to "At time", "60" to "1 h", "1440" to "1 day", "10080" to "1 week")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { (v, l) ->
                    val set = remind.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    FilterChip(v in set, { remind = (if (v in set) set - v else set + v).joinToString(",") }, { Text(l) })
                }
            }
            OutlinedTextField(remind, { remind = it }, label = { Text("Custom, e.g. 30,120") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
            SectionLabel("Repeat")
            val recBase = rec.substringBefore(":")
            ChipRow(listOf("none", "daily", "weekly", "monthly", "every"), recBase, { if (it == "every") "Custom" else it.replaceFirstChar { c -> c.uppercase() } }, { p ->
                rec = when (p) {
                    "weekly" -> "weekly:" + Recurrence.dayCodes[(date ?: LocalDate.now()).dayOfWeek.value - 1]
                    "monthly" -> "monthly:" + (date ?: LocalDate.now()).dayOfMonth
                    "every" -> "every:2:days"
                    null -> "none"
                    else -> p ?: "none"
                }
            }, allowNone = false)
            if (recBase == "weekly") {
                val days = rec.substringAfter(":", "").split(",").filter { it.isNotEmpty() }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Recurrence.dayCodes.forEach { d ->
                        FilterChip(d in days, {
                            val nd = if (d in days) days - d else days + d
                            rec = if (nd.isEmpty()) "weekly" else "weekly:" + Recurrence.dayCodes.filter { it in nd }.joinToString(",")
                        }, { Text(d.take(2)) })
                    }
                }
            }
            if (recBase == "monthly" || recBase == "every") {
                OutlinedTextField(rec, { rec = it }, label = { Text("monthly:17 or every:3:days / weeks / months") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            SectionLabel("Chain (ordered steps)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(chain, { chain = it }, label = { Text("Chain name") }, singleLine = true, modifier = Modifier.weight(2f))
                OutlinedTextField(order, { order = it }, label = { Text("Step #") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Active (off = dormant until triggered)", Modifier.weight(1f)); Switch(active, { active = it }) }
            if (t != null) Row(verticalAlignment = Alignment.CenterVertically) { Text("Done", Modifier.weight(1f)); Switch(done, { done = it }) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (t != null) OutlinedButton({ confirmDelete = true }) { Text("Delete", color = RedC) }
                Spacer(Modifier.weight(1f))
                Button({
                    val m = (t?.m ?: emptyMap()) + mapOf(
                        "title" to title, "bucket" to bucket, "project" to project, "notes" to notes,
                        "due_date" to (date?.toString() ?: ""), "due_time" to (if (date == null) "" else time?.let { fmtTime(it) } ?: ""),
                        "priority" to priority, "remind_minutes_before" to remind, "recurrence" to rec,
                        "chain_id" to chain.trim(), "chain_order" to if (chain.isBlank()) "" else order.ifBlank { "1" },
                        "watch_for_date" to if (watch) "TRUE" else "FALSE", "active" to if (active) "TRUE" else "FALSE",
                        "status" to if (done) "done" else "todo", "deleted" to "FALSE",
                    )
                    repo.launch {
                        if (t != null && done && !t.done) {
                            repo.put("Tasks", m + ("status" to "todo"))
                            repo.completeTask(t.id)?.let { repo.toast(it) }
                        } else repo.put("Tasks", m)
                    }
                    onDismiss()
                }, enabled = title.isNotBlank()) { Text("Save") }
            }
        }
    }
    if (pickDate) DateDialog(date, { pickDate = false }) { date = it }
    if (pickTime) TimeDialog("Due time", time, { pickTime = false }, onClear = { time = null }) { time = it; if (date == null) date = LocalDate.now() }
    if (confirmDelete && t != null) ConfirmDialog("Delete task?", t.title, "Delete", { confirmDelete = false }) {
        repo.launch { repo.remove("Tasks", t.id) }; onDismiss()
    }
}

/** Quick Capture from the + button: task, expense or food in two taps. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickCapture(initialMode: String, repo: Repo, onDismiss: () -> Unit, onFood: () -> Unit) {
    var mode by remember { mutableStateOf(initialMode) }
    val buckets by repo.buckets.collectAsState()
    val budget by repo.budget.collectAsState()
    val focus = remember { FocusRequester() }
    var title by remember { mutableStateOf("") }
    var bucket by remember { mutableStateOf<String?>(null) }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var time by remember { mutableStateOf<LocalTime?>(null) }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var expDate by remember { mutableStateOf(LocalDate.now()) }
    var pickDate by remember { mutableStateOf(false) }
    var pickExpDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val cats = budget.filter { it.month == YearMonth.now() }.map { it.category }.ifEmpty { Defaults.budgetCategories }

    fun saveTask() {
        if (title.isBlank()) return
        repo.launch {
            repo.put("Tasks", mapOf(
                "title" to title.trim(), "bucket" to (bucket ?: ""), "due_date" to (date?.toString() ?: ""),
                "due_time" to (time?.let { fmtTime(it) } ?: ""), "priority" to "med", "status" to "todo", "active" to "TRUE",
            ))
            repo.toast("Added: ${title.trim()}")
        }
        onDismiss()
    }
    fun saveExpense() {
        val a = parseNum(amount) ?: return
        val c = category ?: cats.firstOrNull() ?: "Misc"
        repo.launch { repo.addExpense(expDate, a, c, note.trim()); repo.toast("${inr(a)} · $c") }
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = S1) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("task" to "Task", "expense" to "Expense", "food" to "Food").forEach { (k, l) ->
                    FilterChip(mode == k, { if (k == "food") { onDismiss(); onFood() } else mode = k }, { Text(l) })
                }
            }
            if (mode == "task") {
                OutlinedTextField(
                    title, { title = it }, placeholder = { Text("What needs doing?") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { saveTask() }),
                )
                ChipRow(buckets.map { it.name }, bucket, { it }, { bucket = it })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val today = LocalDate.now()
                    FilterChip(date == today, { date = if (date == today) null else today }, { Text("Today") })
                    FilterChip(date == today.plusDays(1), { date = if (date == today.plusDays(1)) null else today.plusDays(1) }, { Text("Tomorrow") })
                    AssistChip({ pickDate = true }, { Text(date?.takeIf { it != today && it != today.plusDays(1) }?.let { fmtDay(it) } ?: "Pick date") })
                    AssistChip({ pickTime = true }, { Text(time?.let { fmtTime(it) } ?: "Time") })
                }
                Button(::saveTask, enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Filled.Check, null); Spacer(Modifier.width(6.dp)); Text("Save")
                }
            } else {
                OutlinedTextField(
                    amount, { amount = it }, placeholder = { Text("Amount ₹") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { saveExpense() }),
                )
                ChipRow(cats, category ?: cats.firstOrNull(), { it }, { category = it }, allowNone = false)
                OutlinedTextField(note, { note = it }, placeholder = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AssistChip({ pickExpDate = true }, { Text(fmtRelDay(expDate, LocalDate.now())) }, leadingIcon = { Icon(Icons.Filled.DateRange, null, Modifier.size(16.dp)) })
                Button(::saveExpense, enabled = parseNum(amount) != null, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Add expense") }
            }
        }
    }
    LaunchedEffect(mode) { runCatching { focus.requestFocus() } }
    if (pickDate) DateDialog(date, { pickDate = false }) { date = it }
    if (pickExpDate) DateDialog(expDate, { pickExpDate = false }) { expDate = it }
    if (pickTime) TimeDialog("Time", time, { pickTime = false }, onClear = { time = null }) { time = it; if (date == null) date = LocalDate.now() }
}

val Color.Companion.Muted get() = Mute
