package com.shay.waypoint.sync

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SheetsException(val code: Int, msg: String) : Exception(msg)

/** Minimal Google Sheets API v4 client over HttpURLConnection. */
class SheetsApi(private val token: String) {
    private val base = "https://sheets.googleapis.com/v4/spreadsheets"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun range(tab: String) = "'${tab.replace("'", "''")}'"

    private fun call(method: String, url: String, body: JSONObject? = null): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 40_000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrElse { text.take(200) }
                throw SheetsException(code, "HTTP $code: $msg")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    fun create(title: String, tabs: List<String>): String {
        val sheets = JSONArray()
        tabs.forEach { sheets.put(JSONObject().put("properties", JSONObject().put("title", it))) }
        val body = JSONObject().put("properties", JSONObject().put("title", title)).put("sheets", sheets)
        return call("POST", base, body).getString("spreadsheetId")
    }

    fun tabTitles(id: String): List<String> {
        val r = call("GET", "$base/$id?fields=sheets.properties.title")
        val arr = r.optJSONArray("sheets") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).getJSONObject("properties").getString("title") }
    }

    fun addTabs(id: String, titles: List<String>) {
        if (titles.isEmpty()) return
        val reqs = JSONArray()
        titles.forEach { reqs.put(JSONObject().put("addSheet", JSONObject().put("properties", JSONObject().put("title", it)))) }
        call("POST", "$base/$id:batchUpdate", JSONObject().put("requests", reqs))
    }

    /** Returns grid per tab (rows of cell strings). */
    fun batchGet(id: String, tabs: List<String>): Map<String, List<List<String>>> {
        val q = tabs.joinToString("&") { "ranges=" + enc(range(it)) }
        val r = call("GET", "$base/$id/values:batchGet?$q&valueRenderOption=FORMATTED_VALUE&majorDimension=ROWS")
        val vrs = r.optJSONArray("valueRanges") ?: JSONArray()
        val out = LinkedHashMap<String, List<List<String>>>()
        for (i in tabs.indices) {
            val vr = vrs.optJSONObject(i)
            val values = vr?.optJSONArray("values")
            val grid = mutableListOf<List<String>>()
            if (values != null) for (r0 in 0 until values.length()) {
                val row = values.optJSONArray(r0) ?: JSONArray()
                grid += (0 until row.length()).map { row.opt(it)?.toString() ?: "" }
            }
            out[tabs[i]] = grid
        }
        return out
    }

    fun batchClear(id: String, tabs: List<String>) {
        val arr = JSONArray(); tabs.forEach { arr.put(range(it)) }
        call("POST", "$base/$id/values:batchClear", JSONObject().put("ranges", arr))
    }

    fun batchWrite(id: String, data: Map<String, List<List<String>>>) {
        val arr = JSONArray()
        data.forEach { (tab, grid) ->
            val values = JSONArray()
            grid.forEach { row -> values.put(JSONArray(row)) }
            arr.put(JSONObject().put("range", "${range(tab)}!A1").put("majorDimension", "ROWS").put("values", values))
        }
        call("POST", "$base/$id/values:batchUpdate", JSONObject().put("valueInputOption", "RAW").put("data", arr))
    }

    companion object {
        fun idFromUrl(s: String): String? {
            val t = s.trim()
            Regex("/spreadsheets/d/([a-zA-Z0-9-_]+)").find(t)?.let { return it.groupValues[1] }
            return if (Regex("[a-zA-Z0-9-_]{25,}").matches(t)) t else null
        }
    }
}
