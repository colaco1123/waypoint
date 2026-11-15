package com.shay.waypoint.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.shay.waypoint.data.*
import com.shay.waypoint.repo
import java.time.YearMonth

class MainActivity : ComponentActivity() {
    private var pendingAuth: ((String?, String?) -> Unit)? = null
    val showBatteryPrompt = mutableStateOf(false)
    /** Set by widgets: "capture", "expense" or a tab name. */
    val launchRequest = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("open")?.let { launchRequest.value = it }
    }
    val showSheetPrompt = mutableStateOf(false)

    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val cb = pendingAuth; pendingAuth = null
        try {
            val r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data)
            cb?.invoke(r.accessToken, if (r.accessToken == null) "No access token" else null)
        } catch (e: Exception) {
            cb?.invoke(null, authError(e))
        }
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { afterPermissions() }

    private var pendingSms: ((Boolean) -> Unit)? = null
    private val smsPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val cb = pendingSms; pendingSms = null
        cb?.invoke(res.isNotEmpty() && res.values.all { it })
    }

    fun hasSmsPermission(): Boolean = listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    /** Asks for RECEIVE_SMS + READ_SMS; calls back with whether both were granted. */
    fun requestSms(cb: (Boolean) -> Unit) {
        if (hasSmsPermission()) { cb(true); return }
        pendingSms = cb
        smsPerm.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
    }

    private var pendingExport: String? = null
    private val exportDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = pendingExport; pendingExport = null
        if (uri != null && json != null) {
            runCatching { contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
                .onSuccess { repo().toast("Export saved (${json.length / 1024 + 1} KB)") }
                .onFailure { repo().toast("Could not save the export: ${it.message}") }
        }
    }

    /** Lets the user pick where to save the JSON export. */
    fun saveExport(json: String) {
        pendingExport = json
        runCatching { exportDoc.launch("waypoint-export-${java.time.LocalDate.now()}.json") }
            .onFailure { repo().toast("No file picker available on this phone") }
    }

    private fun authError(e: Exception): String {
        val code = (e as? ApiException)?.statusCode
        return when (code) {
            10 -> "Google sign-in isn't configured for this build (DEVELOPER_ERROR). See BUILD.md: add an Android OAuth client with this app's SHA-1."
            12501, 16 -> "Sign-in cancelled"
            7 -> "No network"
            else -> "Sign-in failed: ${e.message}"
        }
    }

    /** Get a Sheets access token, showing Google's consent UI if needed. */
    fun authorize(cb: (String?, String?) -> Unit) {
        Identity.getAuthorizationClient(this).authorize(com.shay.waypoint.sync.Auth.request())
            .addOnSuccessListener { r ->
                if (r.hasResolution()) {
                    pendingAuth = cb
                    try {
                        authLauncher.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build())
                    } catch (e: Exception) { pendingAuth = null; cb(null, "Sign-in failed: ${e.message}") }
                } else cb(r.accessToken, null)
            }
            .addOnFailureListener { cb(null, authError(it)) }
    }

    private fun afterPermissions() {
        val prefs = repo().prefs
        val askSheet = !prefs.getBoolean("asked_sheet", false) && repo().sheetId == null
        val askBattery = !prefs.getBoolean("asked_battery", false) && !isIgnoringBattery(this)
        prefs.edit().putBoolean("asked_sheet", true).putBoolean("asked_battery", true).apply()
        pendingSheetPrompt = askSheet
        if (askBattery) showBatteryPrompt.value = true
        else if (askSheet) { pendingSheetPrompt = false; showSheetPrompt.value = true }
    }

    /** Sheet prompt waiting for the battery prompt to close. */
    var pendingSheetPrompt = false
    fun batteryPromptClosed() {
        showBatteryPrompt.value = false
        if (pendingSheetPrompt) { pendingSheetPrompt = false; showSheetPrompt.value = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.BLACK), SystemBarStyle.dark(android.graphics.Color.BLACK))
        intent?.getStringExtra("open")?.let { launchRequest.value = it }
        setContent { App(this) }
        if (savedInstanceState == null) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else afterPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        val repo = repo()
        repo.refreshToday()
        val last = repo.prefs.getLong("last_sync", 0L)
        if (repo.sheetId != null && System.currentTimeMillis() - last > 2 * 60_000) repo.syncNow("open")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(act: MainActivity) {
    val repo = remember { act.repo() }
    val settings by repo.settings.collectAsState()
    val accent = hexColor(settings["accent_hex"]?.takeIf { it.isNotEmpty() } ?: "#2F80FF")

    WaypointTheme(accent) {
        var tab by rememberSaveable { mutableStateOf("today") }
        var settingsOpen by rememberSaveable { mutableStateOf(false) }
        var capture by remember { mutableStateOf<String?>(null) }
        var foodSlot by remember { mutableStateOf<String?>(null) }
        var foodOpen by remember { mutableStateOf(false) }
        var editTask by remember { mutableStateOf<Task?>(null) }
        var newTask by remember { mutableStateOf(false) }
        var actionTask by remember { mutableStateOf<Task?>(null) }
        var bucketSel by rememberSaveable { mutableStateOf<String?>(null) }
        var month by remember { mutableStateOf(YearMonth.now()) }
        val snack = remember { SnackbarHostState() }
        val syncing by repo.syncing.collectAsState()
        val today by repo.today.collectAsState()
        val tasks by repo.tasks.collectAsState()

        LaunchedEffect(Unit) { repo.messages.collect { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(it) } }
        // roll the date over if the app is left open past midnight
        LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); repo.refreshToday() } }
        val launchReq = act.launchRequest.value
        LaunchedEffect(launchReq) {
            when (launchReq) {
                null -> {}
                "capture" -> { settingsOpen = false; capture = "task" }
                "expense" -> { settingsOpen = false; tab = "money"; capture = "expense" }
                "today", "tasks", "health", "money" -> { settingsOpen = false; tab = launchReq }
            }
            act.launchRequest.value = null
        }
        BackHandler(settingsOpen) { settingsOpen = false }
        BackHandler(!settingsOpen && tab != "today") { tab = "today" }

        val (title, sub) = when {
            settingsOpen -> "Settings" to "Customise everything"
            tab == "tasks" -> "Tasks" to "${tasks.count { !it.done && it.active }} open"
            tab == "health" -> "Health" to fmtLongDay(today)
            tab == "money" -> "Money" to fmtMonth(month)
            else -> "Today" to fmtLongDay(today)
        }

        Scaffold(
            containerColor = Black,
            snackbarHost = { SnackbarHost(snack) },
            topBar = {
                Box(Modifier.fillMaxWidth().background(Black).statusBarsPadding()) {
                    TopoBackdrop(Modifier.matchParentSize())
                    Row(Modifier.fillMaxWidth().padding(start = if (settingsOpen) 4.dp else 18.dp, end = 10.dp, top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (settingsOpen) IconButton({ settingsOpen = false }) { Icon(Icons.Filled.ArrowBack, "Back") }
                        Column(Modifier.weight(1f)) {
                            Text(
                                "WP · DAY %03d · WK %02d".format(today.dayOfYear, today.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)),
                                fontSize = 10.sp, color = accent, fontFamily = Mono, letterSpacing = 1.5.sp,
                            )
                            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                            Text(sub, fontSize = 12.sp, color = Mute, fontFamily = Mono)
                        }
                        IconButton({ repo.syncNow() }, enabled = !syncing) {
                            if (syncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Filled.Refresh, "Sync", tint = Mute)
                        }
                        if (!settingsOpen) IconButton({ settingsOpen = true }, Modifier.clip(TagShape).background(S2)) {
                            Icon(Icons.Filled.Settings, "Settings", tint = TextC)
                        }
                    }
                }
            },
            bottomBar = {
                if (!settingsOpen) Column(Modifier.background(Black).navigationBarsPadding()) {
                    HorizontalDivider(color = LineC, thickness = 1.dp)
                    Row(Modifier.fillMaxWidth().height(60.dp)) {
                        listOf("today" to "Today", "tasks" to "Tasks", "health" to "Health", "money" to "Money").forEach { (k, l) ->
                            val on = tab == k
                            Column(
                                Modifier.weight(1f).fillMaxHeight().clickable { tab = k },
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            ) {
                                Diamond(7.dp, if (on) accent else Color.Transparent, if (on) accent else Color(0xFF3A3A3A), 1.dp)
                                Spacer(Modifier.height(6.dp))
                                Text(l.uppercase(), fontSize = 11.sp, fontFamily = Mono, letterSpacing = 1.4.sp,
                                    color = if (on) accent else Mute, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                if (!settingsOpen) FloatingActionButton(
                    onClick = { if (tab == "health") { foodSlot = null; foodOpen = true } else capture = if (tab == "money") "expense" else "task" },
                    containerColor = accent, contentColor = Color.White, shape = DiamondShape,
                    modifier = Modifier.size(66.dp),
                ) { Icon(Icons.Filled.Add, "Quick add", Modifier.size(28.dp)) }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (settingsOpen) SettingsScreen(repo, act)
                else PullToRefreshBox(isRefreshing = syncing, onRefresh = { repo.syncNow() }, modifier = Modifier.fillMaxSize()) {
                    val onOpen: (Task) -> Unit = { editTask = it }
                    val onSwipe: (Task) -> Unit = { actionTask = it }
                    when (tab) {
                        "tasks" -> TasksScreen(repo, onOpen, onSwipe, bucketSel) { bucketSel = it }
                        "health" -> HealthScreen(repo) { foodSlot = it; foodOpen = true }
                        "money" -> MoneyScreen(repo, month) { month = it }
                        else -> TodayScreen(repo, onOpen, onSwipe) { foodSlot = it; foodOpen = true }
                    }
                }
            }
        }

        capture?.let { mode ->
            QuickCapture(mode, repo, { capture = null }) { foodSlot = null; foodOpen = true }
        }
        if (foodOpen) FoodSheet(repo, foodSlot) { foodOpen = false }
        editTask?.let { t -> TaskEditor(t, repo, { editTask = null }) }
        if (newTask) TaskEditor(null, repo, { newTask = false }, defaultBucket = bucketSel)
        actionTask?.let { t -> TaskActionsDialog(t, repo, { editTask = it }) { actionTask = null } }

        if (act.showBatteryPrompt.value) ConfirmDialog(
            "Allow reminders on time?",
            "Samsung phones pause apps in the background, which delays reminders. Exempt Waypoint from battery optimisation so reminders fire on time and reach your watch.",
            "Exempt", { act.batteryPromptClosed() },
        ) { requestBatteryExemption(act) }
        if (act.showSheetPrompt.value && !act.showBatteryPrompt.value) ConfirmDialog(
            "Sync with Google Sheets?",
            "Link a 'Waypoint Data' Google Sheet so Claude can add and edit your tasks remotely. You can do this later in Settings; the app works fully offline.",
            "Open Settings", { act.showSheetPrompt.value = false },
        ) { settingsOpen = true }
    }
}
