package com.jono.todoistwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A task, trimmed to what the widget shows. Dates are local: dueDate yyyy-MM-dd, dueTime HH:mm. */
data class Task(
    val id: String,
    val content: String,
    val description: String,
    val priority: Int, // API value: 4 = p1 (urgent) … 1 = p4
    val dueDate: String?,
    val dueTime: String?,
    val recurring: Boolean,
    val projectId: String?,
    val labels: List<String>,
    val deadline: String?,
    val order: Int,
) {
    fun toJson() = JSONObject()
        .put("id", id).put("c", content).put("d", description).put("p", priority)
        .put("dd", dueDate ?: "").put("dt", dueTime ?: "").put("r", recurring)
        .put("pid", projectId ?: "").put("l", JSONArray(labels)).put("dl", deadline ?: "").put("o", order)

    companion object {
        fun fromJson(o: JSONObject) = Task(
            id = o.getString("id"),
            content = o.optString("c"),
            description = o.optString("d"),
            priority = o.optInt("p", 1),
            dueDate = o.optString("dd").ifEmpty { null },
            dueTime = o.optString("dt").ifEmpty { null },
            recurring = o.optBoolean("r"),
            projectId = o.optString("pid").ifEmpty { null },
            labels = o.optJSONArray("l")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            deadline = o.optString("dl").ifEmpty { null },
            order = o.optInt("o"),
        )
    }
}

/** What one placed widget shows. */
data class Config(
    val name: String,
    val source: String, // SOURCE_PROJECT, SOURCE_FILTER or SOURCE_QUERY
    val ref: String, // project id, saved-filter id, or the query itself
    val query: String, // the query actually run (for saved filters: refreshed from Todoist each sync)
    val sort: Int, // SORT_DATE or SORT_PRIORITY
) {
    fun toJson() = JSONObject().put("name", name).put("source", source).put("ref", ref)
        .put("query", query).put("sort", sort)

    companion object {
        const val SOURCE_PROJECT = "project"
        const val SOURCE_FILTER = "filter"
        const val SOURCE_QUERY = "query"
        const val SORT_DATE = 0
        const val SORT_PRIORITY = 1

        fun fromJson(o: JSONObject) = Config(
            o.optString("name"), o.optString("source"), o.optString("ref"), o.optString("query"), o.optInt("sort"),
        )
    }
}

data class SavedFilter(val id: String, val name: String, val query: String)
data class Project(val id: String, val name: String)

/** Everything kept on the phone: token, per-widget settings, and the last tasks fetched for each. */
object Store {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("store", Context.MODE_PRIVATE)

    // --- account
    fun token(ctx: Context): String? = prefs(ctx).getString("token", null)?.ifBlank { null }
    fun setToken(ctx: Context, token: String?) {
        prefs(ctx).edit().putString("token", token?.trim()).putBoolean("auth_error", false).apply()
    }
    fun authError(ctx: Context) = prefs(ctx).getBoolean("auth_error", false)
    fun setAuthError(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("auth_error", v).apply()
    fun userName(ctx: Context): String? = prefs(ctx).getString("user", null)

    // --- projects and saved filters (from Todoist's sync endpoint)
    fun saveMeta(ctx: Context, projects: List<Project>, filters: List<SavedFilter>, user: String?) {
        val p = JSONArray().apply { projects.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } }
        val f = JSONArray().apply {
            filters.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("query", it.query)) }
        }
        prefs(ctx).edit().putString("projects", p.toString()).putString("filters", f.toString())
            .putString("user", user).apply()
    }

    fun projects(ctx: Context): List<Project> = arr(ctx, "projects").map { Project(it.getString("id"), it.optString("name")) }
    fun filters(ctx: Context): List<SavedFilter> =
        arr(ctx, "filters").map { SavedFilter(it.getString("id"), it.optString("name"), it.optString("query")) }
    fun projectNames(ctx: Context): Map<String, String> = projects(ctx).associate { it.id to it.name }

    private fun arr(ctx: Context, key: String): List<JSONObject> = try {
        val a = JSONArray(prefs(ctx).getString(key, "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }
    } catch (e: Exception) {
        emptyList()
    }

    // --- per widget
    fun config(ctx: Context, id: Int): Config? =
        prefs(ctx).getString("cfg_$id", null)?.let { runCatching { Config.fromJson(JSONObject(it)) }.getOrNull() }

    fun setConfig(ctx: Context, id: Int, c: Config) = prefs(ctx).edit().putString("cfg_$id", c.toJson().toString()).apply()

    /** Tasks last fetched for a widget, or null if never fetched. */
    fun tasks(ctx: Context, id: Int): List<Task>? = prefs(ctx).getString("tasks_$id", null)?.let { s ->
        runCatching { JSONArray(s).let { a -> (0 until a.length()).map { Task.fromJson(a.getJSONObject(it)) } } }.getOrNull()
    }

    fun saveTasks(ctx: Context, id: Int, tasks: List<Task>) {
        val a = JSONArray().apply { tasks.forEach { put(it.toJson()) } }
        prefs(ctx).edit().putString("tasks_$id", a.toString()).putLong("synced_$id", System.currentTimeMillis())
            .remove("error_$id").apply()
    }

    fun clearTasks(ctx: Context, id: Int) = prefs(ctx).edit().remove("tasks_$id").remove("synced_$id").apply()

    fun synced(ctx: Context, id: Int): Long = prefs(ctx).getLong("synced_$id", 0L)
    fun error(ctx: Context, id: Int): String? = prefs(ctx).getString("error_$id", null)
    fun setError(ctx: Context, id: Int, msg: String?) = prefs(ctx).edit().putString("error_$id", msg).apply()

    fun remove(ctx: Context, id: Int) = prefs(ctx).edit()
        .remove("cfg_$id").remove("tasks_$id").remove("synced_$id").remove("error_$id").apply()

    // --- tasks being completed right now: hidden everywhere until Todoist confirms
    private val hidden = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun hide(id: String) = hidden.add(id)
    fun unhide(id: String) = hidden.remove(id)
    fun isHidden(id: String) = id in hidden
}
