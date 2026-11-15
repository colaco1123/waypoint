package com.shay.waypoint.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shay.waypoint.data.*
import com.shay.waypoint.notify.Notifier
import com.shay.waypoint.notify.Scheduler
import com.shay.waypoint.sync.SheetsApi
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.time.YearMonth
import java.util.Date
import java.util.Locale

private class EditReq(val title: String, val initial: String, val number: Boolean, val onDelete: (() -> Unit)? = null, val onSave: (String) -> Unit)
private class TimeReq(val title: String, val initial: LocalTime?, val onClear: (() -> Unit)? = null, val onSave: (LocalTime) -> Unit)

@Composable
private fun SettingRow(title: String, value: String, divider: Boolean = true, onClick: () -> Unit) =
    ListRow(title, null, divider = divider, onClick = onClick, trailing = { Text(value, fontSize = 13.sp, color = Mute) })

@Composable
private fun SwitchRow(title: String, sub: String? = null, on: Boolean, divider: Boolean = true, onChange: (Boolean) -> Unit) =
    ListRow(title, sub, divider = divider, trailing = { Switch(on, onChange) })

fun isIgnoringBattery(ctx: Context): Boolean =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

fun requestBatteryExemption(ctx: Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
    } catch (_: Exception) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(repo: Repo, act: MainActivity) {
    val ctx = LocalContext.current
    val settings by repo.settings.collectAsState()
    val buckets by repo.buckets.collectAsState()
    val meals by repo.meals.collectAsState()
    val habits by repo.habits.collectAsState()
    val budget by repo.budget.collectAsState()
    val linked by repo.linked.collectAsState()
    val lastSync by repo.lastSync.collectAsState()
    val syncing by repo.syncing.collectAsState()
    val log by repo.syncLog.collectAsState()
    fun s(k: String) = settings[k]?.takeIf { it.isNotEmpty() } ?: Defaults.settings[k] ?: ""
    fun set(k: String, v: String) = repo.launch { repo.put("Settings", mapOf("key" to k, "value" to v)) }

    var edit by remember { mutableStateOf<EditReq?>(null) }
    var timeEdit by remember { mutableStateOf<TimeReq?>(null) }
    var bucketEdit by remember { mutableStateOf<Bucket?>(null) }
    var newBucket by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var battery by remember { mutableStateOf(isIgnoringBattery(ctx)) }
    var exact by remember { mutableStateOf(Scheduler.canExact(ctx)) }
    var resumeTick by remember { mutableIntStateOf(0) }
    var askScan by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        resumeTick++; battery = isIgnoringBattery(ctx); exact = Scheduler.canExact(ctx)
        onPauseOrDispose { }
    }

    val workout = habits.firstOrNull { it.type == "workout" }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp)) {
        item {
            WCard(label = "Appearance · accent") {
                SwatchRow(Defaults.accentPresets, s("accent_hex")) { set("accent_hex", it) }
                Spacer(Modifier.height(6.dp))
                SettingRow("Custom hex", s("accent_hex"), divider = false) {
                    edit = EditReq("Accent colour (#RRGGBB)", s("accent_hex"), false) { v ->
                        Schema.normColour(v)?.let { set("accent_hex", it) } ?: repo.toast("Use a hex colour like #2F80FF")
                    }
                }
            }
        }
        item {
            WCard(label = "Routine") {
                listOf("wake_time" to "Wake up", "leave_time" to "Leave by", "bedtime" to "Bedtime").forEach { (k, l) ->
                    SettingRow(l, s(k).ifEmpty { "Off" }) { timeEdit = TimeReq(l, parseTimeLenient(s(k))) { set(k, fmtTime(it)) } }
                }
                SettingRow("Wind-down reminder", "${s("winddown_minutes")} min before bed") {
                    edit = EditReq("Wind-down minutes before bedtime (0 = off)", s("winddown_minutes"), true) { set("winddown_minutes", it) }
                }
                SwitchRow("Morning briefing at wake time", on = s("briefing_on") != "FALSE", divider = false) { set("briefing_on", if (it) "TRUE" else "FALSE") }
            }
        }
        item {
            WCard(label = "Meal slots", trailing = {
                TextButton({ edit = EditReq("New meal slot", "", false) { n -> if (n.isNotBlank()) repo.launch { repo.put("Meals", mapOf("slot_name" to n, "time" to "12:00", "reminder_on" to "TRUE")) } } }, Modifier.padding(bottom = 4.dp)) { Text("+ Add", fontSize = 12.sp) }
            }) {
                meals.forEachIndexed { i, m ->
                    ListRow(
                        m.name, if (m.reminder) "Reminder on" else "No reminder", divider = i < meals.lastIndex,
                        onClick = { edit = EditReq("Rename meal slot", m.name, false, onDelete = { repo.launch { repo.remove("Meals", m.id) } }) { n -> if (n.isNotBlank()) repo.launch { repo.update("Meals", m.id, "slot_name" to n) } } },
                        trailing = {
                            TextButton({ timeEdit = TimeReq(m.name, m.time) { t -> repo.launch { repo.update("Meals", m.id, "time" to fmtTime(t)) } } }) { Text(fmtTime(m.time)) }
                            Switch(m.reminder, { on -> repo.launch { repo.update("Meals", m.id, "reminder_on" to if (on) "TRUE" else "FALSE") } })
                        },
                    )
                }
            }
        }
        item {
            WCard(label = "Health targets") {
                SettingRow("Calorie target", s("calorie_target").ifEmpty { "Not set" }) {
                    edit = EditReq("Daily calorie target (kcal)", s("calorie_target"), true) { set("calorie_target", parseNum(it)?.let(::fmtNum) ?: "") }
                }
                SettingRow("Protein target", s("protein_target").ifEmpty { "Not set" }.let { if (it == "Not set") it else "$it g" }) {
                    edit = EditReq("Daily protein target (g)", s("protein_target"), true) { set("protein_target", parseNum(it)?.let(::fmtNum) ?: "") }
                }
                if (workout != null) {
                    SettingRow("Workout minimum", "${fmtNum(workout.target)} ${workout.unit}") {
                        edit = EditReq("Minimum workout minutes", fmtNum(workout.target), true) { v -> parseNum(v)?.let { repo.launch { repo.update("Habits", workout.id, "daily_target" to fmtNum(it)) } } }
                    }
                    SettingRow("Workout reminder", workout.times.joinToString { fmtTime(it) }.ifEmpty { "Off" }, divider = false) {
                        timeEdit = TimeReq("Workout reminder", workout.times.firstOrNull()) { t -> repo.launch { repo.update("Habits", workout.id, "reminder_times" to fmtTime(t)) } }
                    }
                }
            }
        }
        item {
            WCard(label = "Water") {
                SettingRow("Daily goal", "${s("water_goal")} glasses") { edit = EditReq("Glasses per day", s("water_goal"), true) { set("water_goal", parseNum(it)?.let(::fmtNum) ?: "8") } }
                SettingRow("Remind every", "${s("water_interval_hours")} h", divider = false) {
                    edit = EditReq("Hours between water reminders (0 = off)", s("water_interval_hours"), true) { set("water_interval_hours", parseNum(it)?.let(::fmtNum) ?: "2") }
                }
            }
        }
        item {
            WCard(label = "Custom habits", trailing = {
                TextButton({ edit = EditReq("New habit (name)", "", false) { n -> if (n.isNotBlank()) repo.launch { repo.put("Habits", mapOf("name" to n, "type" to "custom", "daily_target" to "1", "unit" to "times")) } } }, Modifier.padding(bottom = 4.dp)) { Text("+ Add", fontSize = 12.sp) }
            }) {
                val customs = habits.filter { it.type == "custom" }
                if (customs.isEmpty()) MutedText("Track anything daily, e.g. skincare, reading.")
                customs.forEachIndexed { i, h ->
                    ListRow(h.name, "${fmtNum(h.target)} ${h.unit}/day" + if (h.times.isNotEmpty()) " · " + h.times.joinToString { fmtTime(it) } else "", divider = i < customs.lastIndex,
                        onClick = { edit = EditReq("Daily target for ${h.name}", fmtNum(h.target), true, onDelete = { repo.launch { repo.remove("Habits", h.id) } }) { v -> parseNum(v)?.let { repo.launch { repo.update("Habits", h.id, "daily_target" to fmtNum(it)) } } } },
                        trailing = {
                            TextButton({
                                timeEdit = TimeReq("Add a ${h.name} reminder", null,
                                    onClear = if (h.times.isEmpty()) null else ({ repo.launch { repo.update("Habits", h.id, "reminder_times" to "") } })) { t ->
                                    val all = (h.times + t).distinct().sorted().joinToString(",") { fmtTime(it) }
                                    repo.launch { repo.update("Habits", h.id, "reminder_times" to all) }
                                }
                            }) { Text("+ Time", fontSize = 12.sp) }
                        })
                }
            }
        }
        item {
            WCard(label = "Notifications") {
                Notifier.channels.forEach { (id, name) ->
                    SwitchRow(name, on = s("notif_$id") != "FALSE") { set("notif_$id", if (it) "TRUE" else "FALSE") }
                }
                ListRow("Battery optimisation", if (battery) "Exempt: reminders arrive on time" else "Restricted: reminders may be late. Tap to exempt",
                    onClick = { requestBatteryExemption(ctx) }, trailing = { if (!battery) Pill("Fix", AmberC) else Pill("OK", GreenC) })
                if (!exact) ListRow("Exact alarms", "Not allowed. Tap to allow exact reminder times",
                    onClick = { if (Build.VERSION.SDK_INT >= 31) runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName))) } },
                    trailing = { Pill("Fix", AmberC) })
                ListRow("System notification settings", "Vibration, sound, watch mirroring", onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                })
                ListRow("Send test notification", "Check it reaches your watch", divider = false, onClick = {
                    Notifier.show(ctx, Notifier.TASKS, 4242, "Waypoint test", "If you see this on your watch, mirroring works")
                })
            }
        }
        item {
            val notifAccess = remember(resumeTick) {
                androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
            }
            val senders = s("sms_senders").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            WCard(label = "Auto expense capture") {
                SwitchRow("SMS capture", "Bank and UPI debit SMS go to Money > To review", on = s("sms_capture") == "TRUE") { on ->
                    if (!on) set("sms_capture", "FALSE")
                    else act.requestSms { ok ->
                        if (ok) { set("sms_capture", "TRUE"); askScan = true }
                        else repo.toast("SMS permission not granted. If Android says it's restricted: App info > ⋮ > Allow restricted settings")
                    }
                }
                SwitchRow(
                    "Notification capture",
                    if (notifAccess) "Google Pay, PhonePe, Paytm, Samsung Wallet" else "Needs notification access. Turning on opens the setting",
                    on = s("notif_capture") == "TRUE" && notifAccess,
                ) { on ->
                    set("notif_capture", if (on) "TRUE" else "FALSE")
                    if (on && !notifAccess) runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                }
                SettingRow("Bank / UPI sender IDs", "${senders.size} IDs") {
                    edit = EditReq("Sender IDs, comma separated (e.g. HDFCBK, SBIUPI)", senders.joinToString(", "), false) { v ->
                        val clean = v.split(",", "\n", " ").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
                        set("sms_senders", clean.joinToString(","))
                    }
                }
                SwitchRow("Auto-confirm known merchants", "Skip review when a learned merchant rule matches", on = s("auto_confirm_known") == "TRUE") {
                    set("auto_confirm_known", if (it) "TRUE" else "FALSE")
                }
                ListRow("Scan last 30 days of SMS", "Queue past debits for review", onClick = {
                    act.requestSms { ok -> if (ok) askScan = true else repo.toast("SMS permission not granted") }
                })
                MutedText(
                    "Kept: amount, merchant, time, last 4 digits of the account and the UPI ref. Message text, OTPs and full account numbers are never stored. " +
                        "Android 13+: if a permission is blocked as restricted, open App info > ⋮ > Allow restricted settings.",
                    Modifier.padding(top = 8.dp),
                )
            }
        }
        item {
            WCard(label = "Buckets", trailing = { TextButton({ newBucket = true }, Modifier.padding(bottom = 4.dp)) { Text("+ Add", fontSize = 12.sp) } }) {
                buckets.forEachIndexed { i, b ->
                    ListRow(
                        b.name, null, divider = i < buckets.lastIndex, onClick = { bucketEdit = b },
                        leading = { Box(Modifier.padding(end = 10.dp).size(14.dp).clip(CircleShape).background(hexColor(b.colour))) },
                        trailing = {
                            IconButton({ repo.launch { repo.moveBucket(b, -1) } }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Up") }
                            IconButton({ repo.launch { repo.moveBucket(b, 1) } }, enabled = i < buckets.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, "Down") }
                        },
                    )
                }
            }
        }
        item {
            val ym = YearMonth.now()
            val lines = budget.filter { it.month == ym }
            WCard(label = "Budget categories · ${fmtMonth(ym)}", trailing = {
                TextButton({ edit = EditReq("New budget category", "", false) { n -> if (n.isNotBlank()) repo.launch { repo.put("Budget", mapOf("month" to ym.toString(), "category" to n, "limit_inr" to "")) } } }, Modifier.padding(bottom = 4.dp)) { Text("+ Add", fontSize = 12.sp) }
            }) {
                lines.forEachIndexed { i, l ->
                    ListRow(l.category, null, divider = i < lines.lastIndex,
                        onClick = { edit = EditReq("Rename category", l.category, false, onDelete = { repo.launch { repo.remove("Budget", l.id) } }) { n -> if (n.isNotBlank()) repo.launch { repo.renameCategory(l.category, n.trim()) } } },
                        trailing = {
                            TextButton({ edit = EditReq("${l.category} limit (₹)", l.limit?.let(::fmtNum) ?: "", true) { v -> repo.launch { repo.update("Budget", l.id, "limit_inr" to (parseNum(v)?.let(::fmtNum) ?: "")) } } }) {
                                Text(l.limit?.let { inr(it) } ?: "Set limit", fontSize = 13.sp)
                            }
                        })
                }
            }
        }
        item {
            WCard(label = "Google account and sheet") {
                if (!linked) {
                    MutedText("Sign in with Google, then create the 'Waypoint Data' sheet or link one you already have. The app works fully offline without this.")
                    Spacer(Modifier.height(10.dp))
                    Button({
                        act.authorize { token, err ->
                            if (token == null) repo.toast(err ?: "Sign-in failed")
                            else repo.launch {
                                repo.toast("Creating Waypoint Data…")
                                repo.sync.createSheet(token).onSuccess { repo.syncNow("first sync", token) }.onFailure { repo.toast("Could not create sheet: ${it.message}") }
                            }
                        }
                    }, Modifier.fillMaxWidth()) { Text("Sign in and create 'Waypoint Data'") }
                    OutlinedButton({
                        edit = EditReq("Paste the Google Sheet URL", "", false) { url ->
                            val id = SheetsApi.idFromUrl(url)
                            if (id == null) repo.toast("That doesn't look like a Google Sheets link")
                            else act.authorize { token, err ->
                                if (token == null) repo.toast(err ?: "Sign-in failed")
                                else { repo.sync.linkExisting(id); repo.syncNow("link", token) }
                            }
                        }
                    }, Modifier.fillMaxWidth()) { Text("Link existing sheet by URL") }
                } else {
                    val id = repo.sheetId.orEmpty()
                    ListRow("Waypoint Data", "Sheet ${id.take(10)}… · " + if (lastSync == 0L) "never synced" else "last sync " + SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH).format(Date(lastSync)),
                        onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://docs.google.com/spreadsheets/d/$id"))) } },
                        trailing = { Pill("Linked", GreenC) })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button({ repo.syncNow() }, enabled = !syncing) { Text(if (syncing) "Syncing…" else "Sync now") }
                        OutlinedButton({ act.authorize { t, e -> if (t != null) repo.syncNow("reauth", t) else repo.toast(e ?: "Sign-in failed") } }) { Text("Re-sign in") }
                    }
                    TextButton({ repo.unlink(); repo.toast("Sheet unlinked. Local data kept.") }) { Text("Unlink sheet", color = RedC) }
                }
            }
        }
        item {
            WCard(label = "Data") {
                ListRow("Import JSON", "Paste {\"ops\":[…]} from Claude, preview, then apply", onClick = { importOpen = true })
                ListRow("Export JSON", "Save the full state to a file (also copied to the clipboard)", onClick = {
                    repo.launch {
                        val json = repo.exportJson()
                        act.runOnUiThread {
                            // The clipboard can't hold very large text, so the file save is the reliable copy.
                            if (json.length < 200_000) runCatching {
                                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Waypoint export", json))
                            }
                            act.saveExport(json)
                        }
                    }
                })
                ListRow("Sync log", log.firstOrNull()?.let { "${SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH).format(Date(it.time))} · ${it.message}" } ?: "Empty",
                    divider = false, onClick = { showLog = !showLog })
                if (showLog) {
                    log.take(60).forEach { e ->
                        Text(
                            "${SimpleDateFormat("d/M HH:mm", Locale.ENGLISH).format(Date(e.time))} ${e.level} ${e.message}",
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            color = when (e.level) { "ERROR" -> RedC; "SKIP" -> AmberC; else -> Mute },
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
        item { MutedText("Waypoint 1.0 · every default here is editable · data stays on this phone unless you link a sheet", Modifier.padding(8.dp)) }
    }

    edit?.let { r -> TextDialog(r.title, r.initial, { edit = null }, number = r.number, onDelete = r.onDelete, onSave = r.onSave) }
    timeEdit?.let { r -> TimeDialog(r.title, r.initial, { timeEdit = null }, onClear = r.onClear) { r.onSave(it) } }
    if (newBucket || bucketEdit != null) BucketDialog(bucketEdit, repo, buckets.size) { newBucket = false; bucketEdit = null }
    if (importOpen) ImportDialog(repo) { importOpen = false }
    if (askScan) ConfirmDialog(
        "Scan the last 30 days?",
        "Waypoint reads bank and UPI SMS from the last 30 days and puts the debits in Money > To review. Nothing reaches your budget until you confirm it.",
        "Scan", { askScan = false },
    ) {
        repo.launch {
            val n = repo.capture.scanInbox(ctx)
            repo.toast(if (n == 0) "No new debits found in the last 30 days" else "$n payments added to Money > To review")
        }
    }
}

@Composable
fun BucketDialog(b: Bucket?, repo: Repo, count: Int, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(b?.name ?: "") }
    var colour by remember { mutableStateOf(b?.colour ?: Defaults.bucketColours[count % Defaults.bucketColours.size]) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = S2,
        title = { Text(if (b == null) "New bucket" else "Edit bucket") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                SwatchRow(Defaults.bucketColours, colour) { colour = it }
            }
        },
        confirmButton = {
            TextButton({
                repo.launch {
                    if (b == null) repo.put("Buckets", mapOf("name" to name.trim(), "colour_hex" to colour, "order" to "${count + 1}"))
                    else {
                        if (name.trim() != b.name) repo.renameBucket(b, name.trim())
                        repo.update("Buckets", b.id, "colour_hex" to colour)
                    }
                }
                onDismiss()
            }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (b != null) TextButton({ confirmDelete = true }) { Text("Delete", color = RedC) }
                TextButton(onDismiss) { Text("Cancel") }
            }
        },
    )
    if (confirmDelete && b != null) ConfirmDialog("Delete ${b.name}?", "Its tasks are kept and shown under Inbox.", "Delete", { confirmDelete = false }) {
        repo.launch { repo.remove("Buckets", b.id) }; onDismiss()
    }
}

@Composable
fun ImportDialog(repo: Repo, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<Repo.ImportPlan?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = S2,
        title = { Text("Import JSON") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(text, { text = it; plan = null; error = null }, placeholder = { Text("{\"ops\":[{\"action\":\"add\",\"table\":\"Tasks\",\"row\":{\"title\":\"…\"}}]}") },
                    minLines = 5, maxLines = 10, modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                plan?.let { p ->
                    Text("Preview: ${p.summary}", modifier = Modifier.padding(top = 10.dp))
                    p.errors.take(8).forEach { Text(it, fontSize = 11.sp, color = AmberC) }
                }
                error?.let { Text(it, color = RedC, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            val p = plan
            if (p == null) TextButton({
                scope.launch {
                    try { plan = repo.planImport(text) } catch (e: Exception) { error = "Not valid JSON: ${e.message}" }
                }
            }, enabled = text.isNotBlank()) { Text("Preview") }
            else TextButton({ repo.launch { repo.toast(repo.applyImport(p)) }; onDismiss() }, enabled = p.ops.isNotEmpty()) { Text("Apply") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
