package com.shay.waypoint.capture

import android.content.Context
import android.net.Uri
import com.shay.waypoint.data.*
import com.shay.waypoint.notify.Notifier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth

/**
 * Turns parsed debits into review-queue items: dedupe, category suggestion, optional auto-confirm,
 * and merchant-rule learning. Only amount, merchant, time, last-4 digits and UPI ref are stored.
 */
class CaptureEngine(private val repo: Repo) {
    private val dao get() = repo.dao
    private val mutex = Mutex()

    private val keywordCategories = listOf(
        "Food" to listOf("zomato", "swiggy", "domino", "pizza", "kfc", "mcdonald", "starbucks", "cafe", "restaurant", "blinkit", "zepto", "bigbasket", "instamart", "dunzo", "bakery", "sweets", "dhaba", "hotel", "food"),
        "Transport" to listOf("uber", "ola", "rapido", "irctc", "redbus", "metro", "petrol", "fuel", "indianoil", "iocl", "hpcl", "bpcl", "fastag", "parking", "makemytrip", "goibibo", "indigo", "auto"),
        "Rent and Bills" to listOf("electricity", "bescom", "msedcl", "tata power", "goa electricity", "airtel", "jio", "vodafone", " vi ", "broadband", "act fibernet", "recharge", "rent", "landlord", "gas", "water bill", "insurance"),
        "Projects and Hobbies" to listOf("robu", "robocraze", "electronicscomp", "thingbits", "hobbyking", "3d print", "filament", "evelta", "digikey", "mouser", "lcsc"),
    )

    fun merchantKey(merchant: String): String =
        merchant.lowercase().substringBefore('@').replace(Regex("""[0-9]+"""), " ").replace(Regex("""[^a-z&' ]"""), " ")
            .replace(Regex("""\s+"""), " ").trim().take(30)

    /** Whole-word match, so "rent" does not match "parent" and "ola" does not match "cola". */
    private fun hasWord(hay: String, word: String): Boolean {
        val w = word.trim()
        return w.isNotEmpty() && Regex("(?<![a-z0-9])" + Regex.escape(w) + "(?![a-z0-9])").containsMatchIn(hay)
    }

    // Read from the database (not the UI StateFlows) so this also works in a cold-started receiver.
    private suspend fun categories(): List<String> {
        val ym = YearMonth.now()
        return repo.snap("Budget").mapNotNull { runCatching { BudgetLine(it) }.getOrNull() }
            .filter { it.month == ym }.map { it.category }.ifEmpty { Defaults.budgetCategories }
    }

    private suspend fun rules() = repo.snap("MerchantRules").mapNotNull { runCatching { MerchantRule(it) }.getOrNull() }

    /** Learned rule first (longest match wins), then keyword guesses, else Misc. Second value: came from a rule. */
    suspend fun suggest(merchant: String): Pair<String, Boolean> {
        val low = merchant.lowercase()
        val key = merchantKey(merchant)
        rules().filter { it.match.isNotBlank() && (hasWord(low, it.match) || hasWord(key, it.match)) }
            .maxByOrNull { it.match.length }?.let { return it.category to true }
        val cats = categories()
        for ((cat, words) in keywordCategories) {
            if (cat in cats && words.any { hasWord(low, it) }) return cat to false
        }
        return (cats.firstOrNull { it.equals("Misc", true) } ?: cats.lastOrNull() ?: "Misc") to false
    }

    private suspend fun all(): List<Capture> = dao.all(CAPTURES).mapNotNull { runCatching { Capture(it.map()) }.getOrNull() }

    private suspend fun save(map: Map<String, String>) {
        dao.upsert(RowEntity(CAPTURES, map.getValue("id"), map.toJson(), nowIso(), false))
    }

    /**
     * Adds a captured debit unless it duplicates an earlier capture
     * (same UPI ref, or same amount within 2 minutes). Returns true if a new item was queued.
     */
    suspend fun ingest(p: ParsedTxn, timeMillis: Long, source: String, notify: Boolean): Boolean = mutex.withLock {
        val existing = all()
        val dup = existing.firstOrNull { c ->
            // Two different UPI refs are two real payments, even for the same amount seconds apart.
            if (p.upiRef != null && c.upiRef.isNotEmpty()) c.upiRef == p.upiRef
            else Math.abs(c.amount - p.amount) < 0.005 && Math.abs(c.time - timeMillis) <= 2 * 60_000
        }
        if (dup != null) {
            // Fill in details the other channel lacked (SMS has ref and account; notifications often have a nicer name).
            if (dup.status == "pending") {
                val merged = dup.m.toMutableMap()
                if (dup.upiRef.isEmpty() && p.upiRef != null) merged["upi_ref"] = p.upiRef
                if (dup.account4.isEmpty() && p.account4 != null) merged["account4"] = p.account4.takeLast(4)
                if (dup.merchant.isEmpty() && p.merchant != null) merged["merchant"] = p.merchant
                if (merged != dup.m) save(merged)
            }
            return@withLock false
        }
        val merchant = p.merchant ?: ""
        val (cat, known) = suggest(merchant.ifEmpty { "unknown" })
        val id = "c-$timeMillis-${(p.amount * 100).toLong()}"
        val row = mapOf(
            "id" to id, "amount" to fmtNum(p.amount), "merchant" to merchant, "time" to timeMillis.toString(),
            "account4" to (p.account4?.takeLast(4) ?: ""), "upi_ref" to (p.upiRef ?: ""), "source" to source,
            "status" to "pending", "category" to cat,
        )
        save(row)
        val label = merchant.ifEmpty { "Unknown payee" }
        if (known && repo.settingNow("auto_confirm_known") == "TRUE") {
            confirm(id, cat)
            if (notify && repo.settingNow("notif_money") != "FALSE")
                Notifier.show(repo.ctx, Notifier.MONEY, id.hashCode(), "${inr(p.amount)} · $cat", "Auto-added: $label")
        } else if (notify && repo.settingNow("notif_money") != "FALSE") {
            Notifier.show(repo.ctx, Notifier.MONEY, id.hashCode(), "Review ${inr(p.amount)}", "$label · suggested $cat. Open Money to confirm")
        }
        true
    }

    /** Writes the capture to Expenses and learns merchant -> category. */
    suspend fun confirm(id: String, category: String) {
        val c = dao.get(CAPTURES, id)?.map()?.let(::Capture) ?: return
        if (c.status != "pending") return
        val date = Instant.ofEpochMilli(c.time).atZone(ZoneId.systemDefault()).toLocalDate()
        repo.addExpense(date, c.amount, category, c.merchant.ifEmpty { "Captured payment" }, c.source)
        save(c.m + ("status" to "confirmed") + ("category" to category))
        learn(c.merchant, category)
    }

    suspend fun dismiss(id: String) {
        val c = dao.get(CAPTURES, id)?.map()?.let(::Capture) ?: return
        save(c.m + ("status" to "dismissed"))
    }

    suspend fun setSuggestion(id: String, category: String) {
        val c = dao.get(CAPTURES, id)?.map()?.let(::Capture) ?: return
        save(c.m + ("category" to category))
    }

    private suspend fun learn(merchant: String, category: String) {
        val key = merchantKey(merchant)
        if (key.length < 3) return
        val same = rules().firstOrNull { it.match == key }
        when {
            same == null -> repo.put("MerchantRules", mapOf("match_text" to key, "category" to category))
            same.category != category -> repo.update("MerchantRules", same.id, "category" to category)
        }
    }

    /** Drops reviewed items older than 90 days; keeps recent ones for de-duplication. */
    suspend fun prune() {
        val cutoff = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
        all().filter { it.status != "pending" && it.time < cutoff }.forEach { dao.delete(CAPTURES, it.id) }
    }

    fun senders(): List<String> = repo.s("sms_senders").split(",").map { it.trim() }.filter { it.isNotEmpty() }

    suspend fun onSms(sender: String, body: String, timeMillis: Long, notify: Boolean): Boolean {
        val list = repo.settingNow("sms_senders").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (!TxnParser.isBankSender(sender, list)) return false
        val p = TxnParser.parseSms(body) ?: return false
        return ingest(p, timeMillis, "sms", notify)
    }

    /** Reads the last [days] days of the SMS inbox. Needs READ_SMS. Returns the number of new captures. */
    suspend fun scanInbox(ctx: Context, days: Int = 30): Int {
        val since = System.currentTimeMillis() - days * 24L * 3600 * 1000
        var added = 0
        ctx.contentResolver.query(
            Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"),
            "date > ?", arrayOf(since.toString()), "date ASC",
        )?.use { cur ->
            val a = cur.getColumnIndex("address"); val b = cur.getColumnIndex("body"); val d = cur.getColumnIndex("date")
            while (cur.moveToNext()) {
                val addr = cur.getString(a) ?: continue
                val body = cur.getString(b) ?: continue
                if (onSms(addr, body, cur.getLong(d), notify = false)) added++
            }
        }
        return added
    }
}
