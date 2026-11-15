package com.shay.waypoint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shay.waypoint.data.*

@Composable
fun BucketCard(name: String, count: Int, colour: Color, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier.height(IntrinsicSize.Min).clip(CardShape).background(if (selected) accent.copy(alpha = 0.10f) else S1)
            .border(1.dp, if (selected) accent else LineC, CardShape)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(colour))
        Column(Modifier.padding(14.dp)) {
            Text(name.uppercase(), fontSize = 10.sp, color = Mute, maxLines = 1, fontFamily = Mono, letterSpacing = 1.2.sp)
            Text("%02d".format(count), fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, modifier = Modifier.padding(top = 4.dp))
            Text("open", fontSize = 10.sp, color = Mute, fontFamily = Mono)
        }
    }
}

@Composable
fun ChainCard(name: String, steps: List<Task>, repo: Repo, onOpen: (Task) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val activeId = steps.filter { !it.done }.minByOrNull { it.chainOrder }?.id
    val doneN = steps.count { it.done }
    WCard(label = "Chain · $name", trailing = { Text("$doneN/${steps.size}", fontSize = 12.sp, color = Mute, modifier = Modifier.padding(bottom = 8.dp)) }) {
        steps.forEachIndexed { i, s ->
            val locked = !s.done && s.id != activeId
            Row(Modifier.fillMaxWidth().clickable { onOpen(s) }.heightIn(min = 44.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(28.dp)) {
                    Diamond(
                        16.dp, if (s.done) accent else if (s.id == activeId) Black else Color.Transparent,
                        if (s.done || s.id == activeId) accent else Color(0xFF444444), 2.dp,
                        Modifier.padding(top = 4.dp).clickable(enabled = !locked) {
                            repo.launch { if (s.done) repo.reopenTask(s.id) else repo.completeTask(s.id)?.let { repo.toast(it) } }
                        },
                    )
                    if (i < steps.lastIndex) Box(Modifier.width(2.dp).height(28.dp).background(Color(0xFF2A2A2A)))
                }
                Column(Modifier.weight(1f).padding(start = 8.dp, bottom = 10.dp)) {
                    Text(
                        (if (locked) "🔒 " else "") + s.title, fontSize = 14.sp,
                        color = when { s.done -> Mute; locked -> Color(0xFF555555); else -> TextC },
                        textDecoration = if (s.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    )
                    s.dueDate?.let { Text(fmtDay(it), fontSize = 12.sp, color = Mute) }
                }
                if (s.id == activeId) TextButton({ repo.launch { repo.completeTask(s.id)?.let { repo.toast(it) } } }) { Text("Done", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun TasksScreen(repo: Repo, onOpenTask: (Task) -> Unit, onSwipeLeft: (Task) -> Unit, selectedBucket: String?, onSelectBucket: (String?) -> Unit) {
    val today by repo.today.collectAsState()
    val tasks by repo.tasks.collectAsState()
    val buckets by repo.buckets.collectAsState()
    var showDone by rememberSaveable { mutableStateOf(false) }

    val bucketNames = buckets.map { it.name }
    val openCount = { b: String -> tasks.count { !it.done && it.active && it.bucket == b } }
    val orphan = tasks.filter { it.bucket !in bucketNames }
    val inBucket = if (selectedBucket == null) tasks else if (selectedBucket == "") orphan else tasks.filter { it.bucket == selectedBucket }

    val chains = inBucket.filter { it.chainId.isNotEmpty() }.groupBy { it.chainId }
        .filter { (_, l) -> l.any { !it.done } || showDone }
    val nonChain = inBucket.filter { it.chainId.isEmpty() }
    val projects = nonChain.filter { it.project.isNotEmpty() }.groupBy { it.bucket to it.project }
    val loose = nonChain.filter { it.project.isEmpty() && !it.done }
    val dated = loose.filter { it.dueDate != null }.sortedBy { it.sortKey }
    val undated = loose.filter { it.dueDate == null }.sortedWith(compareBy({ if (it.priority == "high") 0 else if (it.priority == "med") 1 else 2 }, { it.title }))
    val done = nonChain.filter { it.done && it.project.isEmpty() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp)) {
        item { SyncLine(repo) }
        item {
            val cards = buckets.map { Triple(it.name, openCount(it.name), hexColor(it.colour)) } +
                (if (orphan.any { !it.done }) listOf(Triple("", orphan.count { !it.done && it.active }, Mute)) else emptyList())
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                cards.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (n, c, col) ->
                            BucketCard(n.ifEmpty { "Inbox" }, c, col, selectedBucket == n, Modifier.weight(1f)) {
                                onSelectBucket(if (selectedBucket == n) null else n)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Text(selectedBucket?.ifEmpty { "Inbox" } ?: "All buckets", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                FilterChip(showDone, { showDone = !showDone }, { Text("Show done") })
            }
        }
        chains.forEach { (name, steps) ->
            item(key = "chain:$name") { ChainCard(name, steps.sortedBy { it.chainOrder }, repo, onOpenTask) }
        }
        if (dated.isNotEmpty()) item {
            WCard(label = "Scheduled") {
                dated.forEachIndexed { i, t -> TaskRow(t, repo, false, today, onOpenTask, onSwipeLeft, showBucket = selectedBucket == null, divider = i < dated.lastIndex) }
            }
        }
        if (undated.isNotEmpty()) item {
            WCard(label = "Anytime") {
                undated.forEachIndexed { i, t -> TaskRow(t, repo, false, today, onOpenTask, onSwipeLeft, showBucket = selectedBucket == null, divider = i < undated.lastIndex) }
            }
        }
        projects.forEach { (key, items) ->
            val (bucket, project) = key
            val active = items.any { it.active }
            val shown = items.filter { showDone || !it.done }
            item(key = "proj:$bucket:$project") {
                WCard(
                    label = "Project · $project",
                    trailing = {
                        TextButton({ repo.launch { repo.setProjectActive(bucket, project, !active); repo.toast(if (active) "$project paused" else "$project activated") } }, Modifier.padding(bottom = 4.dp)) {
                            Text(if (active) "Pause" else "Activate", fontSize = 12.sp)
                        }
                    },
                ) {
                    if (!active) MutedText("Inactive until triggered. Tap Activate when it starts.", Modifier.padding(bottom = 6.dp))
                    shown.forEachIndexed { i, t -> TaskRow(t, repo, false, today, onOpenTask, onSwipeLeft, showBucket = false, divider = i < shown.lastIndex) }
                }
            }
        }
        if (showDone && done.isNotEmpty()) item {
            WCard(label = "Completed · ${done.size}") {
                done.forEachIndexed { i, t -> TaskRow(t, repo, false, today, onOpenTask, onSwipeLeft, showBucket = selectedBucket == null, divider = i < done.lastIndex) }
            }
        }
        if (inBucket.isEmpty()) item { MutedText("No tasks here yet. Tap + to add one.") }
    }
}
