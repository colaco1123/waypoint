package com.shay.waypoint.data

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoUnit
import java.util.Locale

fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

private fun fmt(p: String): DateTimeFormatter =
    DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p).toFormatter(Locale.ENGLISH)

private val datePatterns = listOf(
    "yyyy-M-d", "yyyy/M/d", "d/M/yyyy", "d-M-yyyy", "d.M.yyyy", "d MMM yyyy", "d MMMM yyyy",
    "MMM d, yyyy", "MMMM d, yyyy", "d MMM, yyyy", "EEE, d MMM yyyy", "EEEE, d MMMM yyyy",
    // last resort: US order, as Google Sheets shows auto-formatted dates in US-locale sheets (11/15/2026)
    "M/d/yyyy",
).map { fmt(it) }

private val timePatterns = listOf("H:mm", "H:mm:ss", "h:mm a", "h:mm:ss a", "h:mma", "h a", "ha", "H.mm", "HHmm").map { fmt(it) }

fun parseDateLenient(s: String): LocalDate? {
    val t = s.trim()
    if (t.isEmpty()) return null
    for (f in datePatterns) try { return LocalDate.parse(t, f) } catch (_: Exception) {}
    try { return LocalDateTime.parse(t.replace(' ', 'T')).toLocalDate() } catch (_: Exception) {}
    try { return OffsetDateTime.parse(t).toLocalDate() } catch (_: Exception) {}
    return null
}

fun parseTimeLenient(s: String): LocalTime? {
    val t = s.trim()
    if (t.isEmpty()) return null
    for (f in timePatterns) try { return LocalTime.parse(t, f) } catch (_: Exception) {}
    return null
}

fun parseInstantLenient(s: String?): Instant? {
    val t = s?.trim().orEmpty()
    if (t.isEmpty()) return null
    try { return Instant.parse(t) } catch (_: Exception) {}
    try { return OffsetDateTime.parse(t).toInstant() } catch (_: Exception) {}
    try { return LocalDateTime.parse(t.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant() } catch (_: Exception) {}
    try { return LocalDate.parse(t).atStartOfDay(ZoneId.systemDefault()).toInstant() } catch (_: Exception) {}
    return null
}

/** null = unreadable */
fun parseBool(s: String, default: Boolean): Boolean? = when (s.trim().lowercase()) {
    "" -> default
    "true", "yes", "y", "1", "x", "on", "✓", "✔" -> true
    "false", "no", "n", "0", "off" -> false
    else -> null
}

fun parseNum(s: String): Double? =
    s.replace("₹", "").replace(",", "").replace("Rs.", "").replace("Rs", "").replace("INR", "")
        .replace("kcal", "").replace("kg", "").replace("g", "").trim().toDoubleOrNull()

fun fmtNum(d: Double): String =
    if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString()
    else "%.2f".format(Locale.US, d).trimEnd('0').trimEnd('.')

fun fmtTime(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)

private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val longDayFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)
fun fmtDay(d: LocalDate): String = dayFmt.format(d)
fun fmtLongDay(d: LocalDate): String = longDayFmt.format(d)
fun fmtMonth(ym: YearMonth): String = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH).format(ym)

fun fmtRelDay(d: LocalDate, today: LocalDate): String {
    val diff = ChronoUnit.DAYS.between(today, d)
    return when {
        diff == 0L -> "Today"
        diff == 1L -> "Tomorrow"
        diff == -1L -> "Yesterday"
        diff in 2..6 -> d.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3) + " " + d.dayOfMonth
        else -> fmtDay(d) + if (d.year != today.year) " " + d.year else ""
    }
}

fun inr(d: Double): String {
    val n = Math.round(d)
    val s = Math.abs(n).toString()
    // Indian grouping: 1,23,456
    val out = if (s.length <= 3) s else {
        val last3 = s.takeLast(3)
        var rest = s.dropLast(3)
        val parts = mutableListOf<String>()
        while (rest.length > 2) { parts.add(0, rest.takeLast(2)); rest = rest.dropLast(2) }
        if (rest.isNotEmpty()) parts.add(0, rest)
        parts.joinToString(",") + "," + last3
    }
    return (if (n < 0) "-₹" else "₹") + out
}

fun shortTitle(s: String, max: Int = 29): String = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

object Recurrence {
    private val dows = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY,
        "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
    )
    val dayCodes = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    /** Returns normalised recurrence or null if unreadable. */
    fun normalise(s: String): String? {
        val t = s.trim().lowercase().replace(" ", "")
        if (t.isEmpty() || t == "none" || t == "no") return "none"
        if (t == "daily" || t == "everyday") return "daily"
        if (t == "weekly") return "weekly"
        if (t == "monthly") return "monthly"
        Regex("weekly:([a-z,]+)").matchEntire(t)?.let { m ->
            val days = m.groupValues[1].split(",").filter { it.isNotEmpty() }.map { it.take(2).uppercase() }
            if (days.isEmpty() || days.any { it !in dows }) return null
            return "weekly:" + dayCodes.filter { it in days }.joinToString(",")
        }
        Regex("monthly:(\\d{1,2})").matchEntire(t)?.let { m ->
            val d = m.groupValues[1].toInt()
            return if (d in 1..31) "monthly:$d" else null
        }
        Regex("every:(\\d+):(day|days|week|weeks|month|months)").matchEntire(t)?.let { m ->
            val n = m.groupValues[1].toInt()
            if (n < 1) return null
            val unit = m.groupValues[2].let { if (it.endsWith("s")) it else it + "s" }
            return "every:$n:$unit"
        }
        return null
    }

    fun next(rec: String, from: LocalDate): LocalDate {
        val p = rec.split(":")
        return when (p[0]) {
            "daily" -> from.plusDays(1)
            "weekly" -> {
                if (p.size < 2) return from.plusWeeks(1)
                val days = p[1].split(",").mapNotNull { dows[it] }.toSet()
                var d = from.plusDays(1)
                repeat(7) {
                    if (d.dayOfWeek in days) return d
                    d = d.plusDays(1)
                }
                from.plusWeeks(1)
            }
            "monthly" -> {
                val dom = p.getOrNull(1)?.toIntOrNull() ?: from.dayOfMonth
                var ym = YearMonth.from(from)
                var cand = ym.atDay(minOf(dom, ym.lengthOfMonth()))
                if (!cand.isAfter(from)) {
                    ym = ym.plusMonths(1)
                    cand = ym.atDay(minOf(dom, ym.lengthOfMonth()))
                }
                cand
            }
            "every" -> {
                val n = p.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(1) ?: 1
                when (p.getOrNull(2)) {
                    "weeks" -> from.plusWeeks(n)
                    "months" -> from.plusMonths(n)
                    else -> from.plusDays(n)
                }
            }
            else -> from.plusDays(1)
        }
    }

    fun describe(rec: String): String {
        val p = rec.split(":")
        return when (p[0]) {
            "daily" -> "Daily"
            "weekly" -> if (p.size < 2) "Weekly" else "Weekly " + p[1].split(",").joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
            "monthly" -> if (p.size < 2) "Monthly" else "Monthly on the ${p[1]}"
            "every" -> "Every ${p.getOrNull(1)} ${p.getOrNull(2)}"
            else -> ""
        }
    }
}
