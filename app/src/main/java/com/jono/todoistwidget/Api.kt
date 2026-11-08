package com.jono.todoistwidget

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Todoist's API (v1), with your personal API token. Only api.todoist.com is ever contacted.
 * Calls block: run them off the main thread (Sync does).
 */
object Api {
    private const val BASE = "https://api.todoist.com/api/v1"
    private const val PAGE = 200
    private const val MAX_PAGES = 5

    class HttpError(val code: Int, message: String) : IOException(message)

    class Meta(val projects: List<Project>, val filters: List<SavedFilter>, val user: String?)

    /** Projects, saved filters and your name, in one call. */
    fun meta(token: String): Meta {
        val body = form(
            "sync_token" to "*",
            "resource_types" to """["projects","filters","user"]""",
        )
        val o = JSONObject(request(token, "POST", "/sync", body))
        val projects = objects(o.optJSONArray("projects"))
            .filter { !it.optBoolean("is_deleted") }
            .sortedBy { it.optInt("child_order") }
            .map { Project(it.getString("id"), it.optString("name")) }
        val filters = objects(o.optJSONArray("filters"))
            .filter { !it.optBoolean("is_deleted") }
            .sortedBy { it.optInt("item_order") }
            .map { SavedFilter(it.getString("id"), it.optString("name"), it.optString("query")) }
        val user = o.optJSONObject("user")?.optString("full_name")?.ifBlank { null }
        return Meta(projects, filters, user)
    }

    fun tasksByQuery(token: String, query: String): List<Task> = paged(token, "/tasks/filter", "query=${enc(query)}")

    fun tasksByProject(token: String, projectId: String): List<Task> =
        paged(token, "/tasks", "project_id=${enc(projectId)}")

    fun close(token: String, taskId: String) {
        request(token, "POST", "/tasks/${enc(taskId)}/close", null)
    }

    private fun paged(token: String, path: String, params: String): List<Task> {
        val out = ArrayList<Task>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val c = cursor?.let { "&cursor=${enc(it)}" } ?: ""
            val o = JSONObject(request(token, "GET", "$path?$params&limit=$PAGE$c", null))
            objects(o.optJSONArray("results")).forEach { out += parseTask(it) }
            cursor = o.optString("next_cursor").takeIf { s -> s.isNotEmpty() && s != "null" } ?: return out
        }
        return out
    }

    private fun parseTask(o: JSONObject): Task {
        val due = o.optJSONObject("due")
        // "date" is yyyy-MM-dd, or a date-time (floating "…T14:30:00", or fixed-zone "…Z").
        val raw = due?.optString("datetime")?.takeIf { it.isNotEmpty() && it != "null" }
            ?: due?.optString("date")?.takeIf { it.isNotEmpty() && it != "null" }
        var date: String? = null
        var time: String? = null
        if (raw != null) {
            if (raw.length <= 10) {
                date = raw
            } else {
                val local = parseDateTime(raw)
                if (local != null) {
                    date = local.toLocalDate().toString()
                    time = local.format(DateTimeFormatter.ofPattern("HH:mm"))
                } else {
                    date = raw.take(10)
                }
            }
        }
        val labels = o.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        return Task(
            id = o.getString("id"),
            content = clean(o.optString("content")),
            description = clean(o.optString("description").lineSequence().firstOrNull { it.isNotBlank() } ?: ""),
            priority = o.optInt("priority", 1),
            dueDate = date,
            dueTime = time,
            recurring = due?.optBoolean("is_recurring") ?: false,
            projectId = o.optString("project_id").ifEmpty { null },
            labels = labels,
            deadline = o.optJSONObject("deadline")?.optString("date")?.take(10)?.ifEmpty { null },
            order = o.optInt("child_order", o.optInt("order")),
        )
    }

    private fun parseDateTime(s: String): LocalDateTime? = runCatching {
        if (s.endsWith("Z") || s.length > 19) {
            val instant = if (s.endsWith("Z")) Instant.parse(s) else OffsetDateTime.parse(s).toInstant()
            LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        } else {
            LocalDateTime.parse(s)
        }
    }.getOrNull()

    /** Todoist text is markdown: keep link text, drop emphasis marks. */
    private fun clean(s: String): String = s
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        .replace("**", "").replace("__", "").replace("`", "")
        .trim()

    private fun objects(a: JSONArray?): List<JSONObject> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun form(vararg pairs: Pair<String, String>) =
        pairs.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    private fun request(token: String, method: String, path: String, formBody: String?): String {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            if (formBody != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(formBody.toByteArray()) }
            } else if (method == "POST") {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(0)
                conn.outputStream.close()
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                val msg = runCatching { JSONObject(err).optString("error") }.getOrNull()?.ifBlank { null }
                    ?: err.take(120).ifBlank { "HTTP $code" }
                throw HttpError(code, msg)
            }
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
