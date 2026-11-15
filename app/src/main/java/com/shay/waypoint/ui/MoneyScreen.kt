package com.shay.waypoint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shay.waypoint.data.*
import java.time.YearMonth

/** "zomato@hdfcbank" -> "Zomato"; plain names are left as they are. */
fun displayMerchant(m: String): String {
    if (m.isBlank()) return "Unknown payee"
    if ('@' !in m) return m
    val local = m.substringBefore('@').replace(Regex("""[._\-]+"""), " ").replace(Regex("""\d+"""), "").trim()
    return local.split(" ").filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }.ifEmpty { m }
}

/** One captured payment awaiting review: tick to confirm, tap the category to change it, swipe to dismiss. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewRow(c: Capture, repo: Repo, onPickCategory: (Capture) -> Unit, divider: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v != SwipeToDismissBoxValue.Settled) {
            repo.launch { repo.capture.dismiss(c.id); repo.toast("Dismissed ${inr(c.amount)}") }
            true
        } else false
    })
    SwipeToDismissBox(state, backgroundContent = {
        Row(Modifier.fillMaxSize().background(RedC.copy(alpha = .18f)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("DISMISS", fontFamily = Mono, fontSize = 11.sp, color = RedC, modifier = Modifier.weight(1f))
            Text("DISMISS", fontFamily = Mono, fontSize = 11.sp, color = RedC)
        }
    }) {
        Column(Modifier.background(S1)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(inr(c.amount), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(displayMerchant(c.merchant), fontSize = 14.sp, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 2.dp))
                    }
                    val t = java.time.Instant.ofEpochMilli(c.time).atZone(java.time.ZoneId.systemDefault())
                    Text(
                        buildList {
                            add("${fmtDay(t.toLocalDate())} ${fmtTime(t.toLocalTime())}")
                            add(if (c.source == "sms") "SMS" else "APP")
                            if (c.account4.isNotEmpty()) add("A/c ··${c.account4}")
                        }.joinToString(" · "),
                        fontSize = 11.sp, color = Mute, fontFamily = Mono,
                    )
                }
                Box(Modifier.widthIn(max = 120.dp).clickable { onPickCategory(c) }.padding(4.dp)) { Pill(c.suggested.ifEmpty { "Category" }) }
                Spacer(Modifier.width(4.dp))
                IconButton({ repo.launch { repo.capture.confirm(c.id, c.suggested); repo.toast("Added ${inr(c.amount)} · ${c.suggested}") } }) {
                    Box(contentAlignment = Alignment.Center) {
                        Diamond(30.dp, accent)
                        Icon(Icons.Filled.Check, "Confirm", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (divider) HorizontalDivider(color = LineC)
        }
    }
}

@Composable
fun MoneyScreen(repo: Repo, month: YearMonth, onMonth: (YearMonth) -> Unit) {
    val budget by repo.budget.collectAsState()
    val expenses by repo.expenses.collectAsState()
    val review by repo.toReview.collectAsState()
    var pickFor by remember { mutableStateOf<Capture?>(null) }
    var editLimit by remember { mutableStateOf<BudgetLine?>(null) }
    var addCat by remember { mutableStateOf(false) }
    LaunchedEffect(month) { if (month == YearMonth.now()) repo.ensureMonth(month) }

    val lines = budget.filter { it.month == month }
    val monthExp = expenses.filter { YearMonth.from(it.date) == month }
    val spentBy = monthExp.groupBy { it.category }.mapValues { (_, l) -> l.sumOf { it.amount } }
    val totalLimit = lines.sumOf { it.limit ?: 0.0 }
    val totalSpent = monthExp.sumOf { it.amount }
    val extraCats = spentBy.keys.filter { c -> lines.none { it.category == c } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp)) {
        item { SyncLine(repo) }
        if (review.isNotEmpty()) item {
            WCard(label = "To review · ${review.size}") {
                MutedText("Captured from SMS / payment apps. Tick to add, tap the category to change it, swipe to dismiss.", Modifier.padding(bottom = 4.dp))
                review.forEachIndexed { i, c -> key(c.id) { ReviewRow(c, repo, { pickFor = it }, i < review.lastIndex) } }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                IconButton({ onMonth(month.minusMonths(1)) }) { Icon(Icons.Filled.KeyboardArrowLeft, "Previous month") }
                Text(fmtMonth(month), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton({ onMonth(month.plusMonths(1)) }) { Icon(Icons.Filled.KeyboardArrowRight, "Next month") }
            }
        }
        item {
            WCard(label = "${fmtMonth(month).substringBefore(" ")} budget") {
                if (totalLimit > 0) {
                    BigNumber(inr(totalLimit - totalSpent), "left of ${inr(totalLimit)}")
                    val f = (totalSpent / totalLimit).toFloat()
                    ProgressBar(f, if (f >= 1f) RedC else if (f >= .8f) AmberC else MaterialTheme.colorScheme.primary, Modifier.padding(top = 6.dp))
                } else {
                    BigNumber(inr(totalSpent), "spent")
                    MutedText("Tap a category below to set its limit", Modifier.padding(top = 4.dp))
                }
            }
        }
        item {
            WCard(label = "Categories", trailing = { TextButton({ addCat = true }, Modifier.padding(bottom = 4.dp)) { Text("+ Add", fontSize = 12.sp) } }) {
                lines.sortedBy { Defaults.budgetCategories.indexOf(it.category).let { i -> if (i < 0) 99 else i } }.forEach { l ->
                    val sp = spentBy[l.category] ?: 0.0
                    val lim = l.limit
                    Column(Modifier.fillMaxWidth().clickable { editLimit = l }.padding(vertical = 7.dp)) {
                        Row {
                            Text(l.category, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(if (lim != null && lim > 0) "${inr(sp)} / ${inr(lim)}" else "${inr(sp)} · no limit", fontSize = 13.sp, color = Mute)
                        }
                        if (lim != null && lim > 0) {
                            val f = (sp / lim).toFloat()
                            ProgressBar(f, if (f >= 1f) RedC else if (f >= .8f) AmberC else MaterialTheme.colorScheme.primary, Modifier.padding(top = 6.dp))
                            Text(if (sp <= lim) "${inr(lim - sp)} left" else "${inr(sp - lim)} over", fontSize = 11.sp, color = if (sp > lim) RedC else Mute, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
                extraCats.forEach { c -> ListRow(c, "Not in budget", trailing = { Text(inr(spentBy[c] ?: 0.0), color = Mute, fontSize = 13.sp) }, divider = false) }
            }
        }
        if (totalSpent > 0) item {
            WCard(label = "Breakdown") {
                spentBy.entries.sortedByDescending { it.value }.forEach { (c, v) ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c, fontSize = 13.sp, modifier = Modifier.width(130.dp), maxLines = 1)
                        ProgressBar((v / totalSpent).toFloat(), modifier = Modifier.weight(1f))
                        Text(" ${(v * 100 / totalSpent).toInt()}%", fontSize = 12.sp, color = Mute, modifier = Modifier.width(44.dp))
                    }
                }
            }
        }
        item {
            WCard(label = "Expenses · ${monthExp.size}") {
                if (monthExp.isEmpty()) MutedText("No expenses this month. Tap + to add one.")
                monthExp.forEachIndexed { i, e ->
                    ListRow(
                        e.note.ifEmpty { e.category }, "${e.category} · ${fmtDay(e.date)}", divider = i < monthExp.lastIndex,
                        trailing = {
                            Text(inr(e.amount), fontSize = 13.sp)
                            IconButton({ repo.launch { repo.remove("Expenses", e.id) } }) { Icon(Icons.Filled.Close, "Delete", tint = Mute, modifier = Modifier.size(18.dp)) }
                        },
                    )
                }
            }
        }
        item {
            val history = (1..6).map { month.minusMonths(it.toLong()) }.map { m ->
                m to expenses.filter { YearMonth.from(it.date) == m }.sumOf { it.amount }
            }.filter { it.second > 0 }
            if (history.isNotEmpty()) WCard(label = "History") {
                history.forEachIndexed { i, (m, v) ->
                    ListRow(fmtMonth(m), null, onClick = { onMonth(m) }, divider = i < history.lastIndex, trailing = { Text(inr(v), color = Mute, fontSize = 13.sp) })
                }
            }
        }
    }

    editLimit?.let { l ->
        TextDialog("${l.category} limit (₹)", l.limit?.let(::fmtNum) ?: "", { editLimit = null }, number = true,
            onDelete = { repo.launch { repo.remove("Budget", l.id) } }) { v ->
            repo.launch { repo.update("Budget", l.id, "limit_inr" to (parseNum(v)?.let(::fmtNum) ?: "")) }
        }
    }
    pickFor?.let { c ->
        val cats = budget.filter { it.month == YearMonth.now() }.map { it.category }.ifEmpty { Defaults.budgetCategories }
        AlertDialog(
            onDismissRequest = { pickFor = null }, containerColor = S2,
            title = { Text("${inr(c.amount)} · ${displayMerchant(c.merchant)}") },
            text = {
                Column {
                    MutedText("Pick a category. Waypoint remembers it for this merchant.", Modifier.padding(bottom = 8.dp))
                    cats.forEach { cat ->
                        ListRow(cat, null, divider = false, onClick = {
                            repo.launch { repo.capture.setSuggestion(c.id, cat) }; pickFor = null
                        }, trailing = { if (cat == c.suggested) Pill("now") })
                    }
                }
            },
            confirmButton = { TextButton({ pickFor = null }) { Text("Close") } },
        )
    }
    if (addCat) TextDialog("New category", "", { addCat = false }) { v ->
        if (v.isNotBlank()) repo.launch { repo.put("Budget", mapOf("month" to month.toString(), "category" to v, "limit_inr" to "")) }
    }
}
