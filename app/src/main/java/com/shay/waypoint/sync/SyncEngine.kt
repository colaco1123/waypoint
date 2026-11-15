package com.shay.waypoint.sync

import com.shay.waypoint.data.*
import com.shay.waypoint.notify.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Two-way merge between Room and the Google Sheet.
 * Per row: newer updated_at wins; equal stamps -> sheet wins unless the local row has unsynced edits.
 * deleted=TRUE removes locally. Blank ids get assigned and written back. Malformed rows are skipped,
 * logged, and preserved in the sheet untouched.
 */
class SyncEngine(private val repo: Repo) {
    private val mutex = Mutex()
    private val dao get() = repo.dao

    private suspend fun log(level: String, msg: String) {
        dao.log(SyncLogEntry(time = System.currentTimeMillis(), level = level, message = msg))
    }

    suspend fun createSheet(token: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val api = SheetsApi(token)
            val id = api.create("Waypoint Data", listOf("README") + Schema.tables.keys)
            api.batchWrite(id, mapOf("README" to Schema.readme))
            repo.prefs.edit().remove("replace_local").apply()
            repo.sheetId = id
            log("INFO", "Created spreadsheet 'Waypoint Data'")
            id
        }
    }

    fun linkExisting(id: String) {
        repo.prefs.edit().putBoolean("replace_local", true).apply()
        repo.sheetId = id
    }

    suspend fun sync(reason: String, tokenOverride: String? = null): Result<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            repo.syncing.value = true
            try {
                val msg = doSync(tokenOverride)
                val now = System.currentTimeMillis()
                repo.prefs.edit().putLong("last_sync", now).remove("last_sync_error").apply()
                repo.lastSync.value = now
                repo.lastSyncError.value = null
                log("INFO", "Sync ($reason): $msg")
                Result.success(msg)
            } catch (e: Exception) {
                val m = e.message ?: e.javaClass.simpleName
                repo.prefs.edit().putString("last_sync_error", m).apply()
                repo.lastSyncError.value = m
                log("ERROR", "Sync ($reason) failed: $m")
                Result.failure(e)
            } finally {
                dao.trimLog()
                repo.syncing.value = false
            }
        }
    }

    private suspend fun doSync(tokenOverride: String?): String {
        val sheetId = repo.sheetId ?: throw IllegalStateException("No sheet linked")
        val token = tokenOverride
            ?: runCatching { Auth.silentToken(repo.ctx) }.getOrElse { throw IllegalStateException("Google sign-in failed: ${it.message}") }
            ?: throw IllegalStateException("Sign in to Google in Settings")
        val api = SheetsApi(token)
        val tabs = Schema.tables.keys.toList()

        val existing = api.tabTitles(sheetId)
        val missing = (listOf("README") + tabs).filter { it !in existing }
        if (missing.isNotEmpty()) {
            api.addTabs(sheetId, missing)
            if ("README" in missing) api.batchWrite(sheetId, mapOf("README" to Schema.readme))
            log("INFO", "Added tabs: ${missing.joinToString()}")
        }

        val got = api.batchGet(sheetId, tabs)

        // First sync after linking an existing sheet: the sheet is the source of truth if it has data.
        if (repo.prefs.getBoolean("replace_local", false)) {
            val sheetHasData = got.values.any { g -> g.drop(1).any { r -> r.any { it.isNotBlank() } } }
            if (sheetHasData) {
                // keep local-only data such as the capture review queue
                repo.lock.withLock { tabs.forEach { dao.clearTable(it) }; dao.clearAllTombstones() }
                log("INFO", "Linked existing sheet: replaced local data with sheet data")
            }
            repo.prefs.edit().remove("replace_local").apply()
        }

        var pulled = 0; var skipped = 0; var assigned = 0
        val out = LinkedHashMap<String, List<List<String>>>()
        val cutoff = Instant.now().minus(30, ChronoUnit.DAYS)

        val pushed = HashMap<String, List<RowEntity>>()
        val pushedTombs = HashMap<String, Set<String>>()
        var liveCount = 0

        // Merge each tab under the repo lock so a local edit cannot interleave with the merge.
        for (t in tabs) repo.lock.withLock {
            val cols = Schema.tables.getValue(t)
            val idc = Schema.idCol(t)
            val grid = got[t].orEmpty()
            val headers = grid.firstOrNull()?.map { it.trim().lowercase() }.orEmpty()
            val tomb = dao.tombstones(t).toSet()
            val local = dao.all(t).associateBy { it.id }.toMutableMap()
            val keepRaw = mutableListOf<List<String>>()
            val deletedOut = LinkedHashMap<String, Map<String, String>>()
            val seen = mutableSetOf<String>()
            // every id already used anywhere in the sheet tab, so a new id never collides with a later row
            val idIdx = headers.indexOf(idc)
            val taken = (grid.drop(1).mapNotNull { it.getOrNull(idIdx)?.trim()?.takeIf { s -> s.isNotEmpty() } } + local.keys + tomb).toMutableSet()

            if (grid.isNotEmpty() && idc !in headers) {
                log("SKIP", "$t: header row has no '$idc' column; sheet rows ignored and headers rewritten")
            } else grid.drop(1).forEachIndexed { i, cells ->
                if (cells.all { it.isBlank() }) return@forEachIndexed
                val raw = HashMap<String, String>()
                headers.forEachIndexed { c, h -> if (h.isNotEmpty()) raw[h] = cells.getOrNull(c).orEmpty() }
                try {
                    var id = raw[idc]?.trim().orEmpty()
                    if (id.isEmpty()) {
                        if (t == "Settings") throw Schema.Bad("key is blank")
                        id = Schema.nextId(t, taken)
                        taken += id
                        raw[idc] = id
                        assigned++
                    }
                    if (raw["updated_at"].isNullOrBlank()) raw["updated_at"] = nowIso()
                    val norm = Schema.normalise(t, raw)
                    if (!seen.add(id)) throw Schema.Bad("duplicate id '$id'")
                    if (id in tomb) return@forEachIndexed

                    val loc = local[id]
                    val sheetWins = if (loc == null) true else {
                        val a = parseInstantLenient(norm["updated_at"]) ?: Instant.EPOCH
                        val b = parseInstantLenient(loc.updatedAt) ?: Instant.EPOCH
                        val cmp = a.compareTo(b)
                        cmp > 0 || (cmp == 0 && !loc.dirty)
                    }
                    if (sheetWins) {
                        if (norm["deleted"] == "TRUE") {
                            if (loc != null) { dao.delete(t, id); local.remove(id); pulled++ }
                            deletedOut[id] = norm
                        } else {
                            val e = RowEntity(t, id, norm.toJson(), norm.getValue("updated_at"), false)
                            if (loc == null || loc.map() != norm) { dao.upsert(e); pulled++ }
                            local[id] = e
                        }
                    }
                } catch (e: Exception) {
                    skipped++
                    log("SKIP", "$t row ${i + 2}: ${e.message}")
                    keepRaw += cols.map { c -> raw[c].orEmpty() }
                }
            }

            // Build the full tab to write back.
            val rows = mutableListOf<List<String>>()
            rows += cols
            val liveRows = local.values.map { it.map() }
            for (m in liveRows.sortedBy { it[idc].orEmpty() }) {
                if (m["deleted"] == "TRUE") deletedOut[m.s(idc)] = m
                else rows += cols.map { m[it].orEmpty() }
            }
            for ((_, m) in deletedOut) {
                val ts = parseInstantLenient(m["updated_at"]) ?: Instant.EPOCH
                if (ts.isAfter(cutoff)) rows += cols.map { m[it].orEmpty() }
            }
            rows += keepRaw
            liveCount += rows.size - 1
            // Overwrite the old contents in the same write (no separate clear that could leave the
            // sheet empty if the write then failed): pad to the old width and blank any leftover rows.
            val width = maxOf(cols.size, grid.maxOfOrNull { it.size } ?: 0)
            val padded = rows.map { r -> if (r.size < width) r + List(width - r.size) { "" } else r }.toMutableList()
            repeat(grid.size - padded.size) { padded += List(width) { "" } }
            out[t] = padded
            pushed[t] = local.values.toList()
            pushedTombs[t] = tomb
        }

        api.batchWrite(sheetId, out)

        // Local cleanup after a successful push. Only rows that are still exactly what was pushed are
        // touched, so an edit or delete made while the sync was running is kept for the next sync.
        repo.lock.withLock {
            for (t in tabs) {
                for (e in pushed[t].orEmpty()) {
                    if (e.map()["deleted"] == "TRUE") dao.deleteIf(t, e.id, e.data)
                    else if (e.dirty) dao.clearDirtyIf(t, e.id, e.data)
                }
                pushedTombs[t].orEmpty().forEach { dao.deleteTombstone(t, it) }
            }
        }
        repo.ensureMonth(java.time.YearMonth.now())
        runCatching { Scheduler.rescheduleAll(repo.ctx, repo) }
        com.shay.waypoint.widget.Widgets.updateAll(repo.ctx)

        val pushedRows = liveCount
        return buildString {
            append("$pulled changes in, $pushedRows rows in sheet")
            if (assigned > 0) append(", $assigned new ids")
            if (skipped > 0) append(", $skipped skipped (see sync log)")
        }
    }
}
