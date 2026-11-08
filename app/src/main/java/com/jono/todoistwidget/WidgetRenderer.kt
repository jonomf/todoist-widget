package com.jono.todoistwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.text.format.DateFormat
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Draws every placed widget. One widget fits every size from 1×1 up: a layout per size, and
 * Android shows the one closest to the widget's real size. Each placed widget has its own
 * project / filter, so each is drawn separately.
 */
object WidgetRenderer {

    /**
     * Every layout, the size (dp) it's designed for, and what it shows. Typical grid: 1 cell ≈ 80dp
     * wide, 2 ≈ 180, 3 ≈ 270, 4 ≈ 360; rows ≈ 80–100dp. The setup screen lists real sizes.
     */
    private enum class Kind(
        val layout: Int,
        val size: SizeF,
        val label: String,
        val list: Boolean = true,
        val detail: Boolean = false, // two-line rows: + project, labels, deadline, description
        val projectInline: Boolean = false, // one-line rows that name the project beside the date
        val icon: Boolean = false, // Todoist's icon before the title
        val status: Boolean = false, // "5m" since last refresh, or the error
        val refresh: Boolean = true,
        val expands: Boolean = true, // title opens the full panel (else opens the list in Todoist)
    ) {
        TILE(R.layout.widget_tile, SizeF(50f, 50f), "count", list = false),
        STRIP_S(R.layout.widget_list, SizeF(140f, 50f), "list", refresh = false),
        STRIP(R.layout.widget_list, SizeF(240f, 50f), "list + projects", projectInline = true, icon = true),
        STRIP_L(R.layout.widget_grid, SizeF(320f, 50f), "2-column list", projectInline = true, icon = true),
        SQUARE(R.layout.widget_list, SizeF(140f, 110f), "list"),
        TALL(R.layout.widget_list, SizeF(140f, 230f), "2-line list", detail = true),
        LARGE(R.layout.widget_list, SizeF(240f, 110f), "2-line list", detail = true, icon = true, status = true),
        XL(R.layout.widget_list, SizeF(240f, 230f), "everything", detail = true, icon = true, status = true,
            expands = false),
    }

    /** The most rows one list carries; the rest is a "+N more" row (keeps widget updates small). */
    private const val CAP = 60

    /** In-app views (the expand panel) that mirror the widget and redraw with it. */
    val listeners = CopyOnWriteArraySet<() -> Unit>()

    private enum class State { NO_TOKEN, BAD_TOKEN, NOT_SET_UP, LOADING, READY }

    private class Model(
        val id: Int,
        val state: State,
        val config: Config?,
        val title: String,
        val tasks: List<Task>,
        val overdue: Int,
        val error: String?,
        val synced: Long,
        val projects: Map<String, String>,
        val today: LocalDate,
        val now: LocalTime,
    )

    fun widgetIds(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, TaskWidget::class.java))

    fun renderAll(ctx: Context) {
        render(ctx, widgetIds(ctx))
        listeners.forEach { it() }
    }

    fun render(ctx: Context, ids: IntArray) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for (id in ids) {
            val m = model(ctx, id)
            val views = RemoteViews(Kind.values().associate { k -> k.size to fill(ctx, m, k) })
            mgr.updateAppWidget(id, views)
        }
    }

    /** The full view, for the expand panel. */
    fun buildPanel(ctx: Context, id: Int): RemoteViews = fill(ctx, model(ctx, id), Kind.XL)

    /** For the setup screen: each widget's size, which layout it gets, and its state. */
    fun describe(ctx: Context, id: Int): String {
        val o = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        val m = model(ctx, id)
        val c = m.config
        val what = when (c?.source) {
            null -> "not set up"
            Config.SOURCE_PROJECT -> "project"
            Config.SOURCE_FILTER -> "filter: ${c.query}"
            else -> "query: ${c.query}"
        }
        val state = when {
            m.error != null -> "⚠ ${m.error}"
            m.synced > 0 -> "${m.tasks.size} tasks, updated ${ago(m.synced)} ago"
            else -> ""
        }
        return "${m.title} ($what)\n${w}×${h} dp → ${bestFit(w.toFloat(), h.toFloat()).label}" +
            (if (state.isNotEmpty()) " · $state" else "")
    }

    /** Same choice Android makes: the layout that fits and is closest to the real size. */
    private fun bestFit(w: Float, h: Float): Kind =
        Kind.values().filter { it.size.width <= w && it.size.height <= h }
            .minByOrNull { (w - it.size.width).let { d -> d * d } + (h - it.size.height).let { d -> d * d } }
            ?: Kind.TILE

    // ---------------------------------------------------------------- model

    private fun model(ctx: Context, id: Int): Model {
        val cfg = Store.config(ctx, id)
        val cached = Store.tasks(ctx, id)
        val today = LocalDate.now()
        val now = LocalTime.now()
        val state = when {
            Store.token(ctx) == null -> State.NO_TOKEN
            Store.authError(ctx) -> State.BAD_TOKEN
            cfg == null -> State.NOT_SET_UP
            cached == null -> State.LOADING
            else -> State.READY
        }
        val tasks = (cached ?: emptyList()).filterNot { Store.isHidden(it.id) }.let { sort(it, cfg?.sort ?: 0) }
        return Model(
            id = id,
            state = state,
            config = cfg,
            title = cfg?.name?.ifBlank { null } ?: "Todoist",
            tasks = if (state == State.READY || state == State.LOADING) tasks else emptyList(),
            overdue = tasks.count { isOverdue(it, today, now) },
            error = Store.error(ctx, id),
            synced = Store.synced(ctx, id),
            projects = Store.projectNames(ctx),
            today = today,
            now = now,
        )
    }

    /** Most overdue first, then priority; undated last. Or priority first, then date. */
    private fun sort(tasks: List<Task>, sort: Int): List<Task> {
        val byDate = compareBy<Task> { it.dueDate ?: "9999-99-99" }
        val byPriority = compareByDescending<Task> { it.priority }
        val rest = compareBy<Task>({ it.dueTime ?: "99:99" }, { it.order })
        val c = if (sort == Config.SORT_PRIORITY) byPriority.then(byDate) else byDate.then(byPriority)
        return tasks.sortedWith(c.then(rest))
    }

    private fun isOverdue(t: Task, today: LocalDate, now: LocalTime): Boolean {
        val d = t.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
        if (d.isBefore(today)) return true
        if (d == today && t.dueTime != null) return runCatching { LocalTime.parse(t.dueTime).isBefore(now) }.getOrDefault(false)
        return false
    }

    // ---------------------------------------------------------------- drawing

    private fun fill(ctx: Context, m: Model, k: Kind): RemoteViews {
        val rv = RemoteViews(ctx.packageName, k.layout)

        // Update symbol (every layout).
        val updater = UpdateStatus.updaterIntent(ctx)
        if (UpdateStatus.available(ctx) && updater != null) {
            rv.setViewVisibility(R.id.update, View.VISIBLE)
            rv.setOnClickPendingIntent(R.id.update, activity(ctx, 900_000 + m.id, updater))
        } else {
            rv.setViewVisibility(R.id.update, View.GONE)
        }
        rv.setTextViewText(R.id.title, m.title)

        if (!k.list) {
            // 1×1: one number. Overdue (red) if any, else the total.
            val n = if (m.overdue > 0) m.overdue else m.tasks.size
            rv.setTextViewText(R.id.count_big, if (m.state == State.READY) n.toString() else "–")
            if (m.overdue > 0) rv.setTextColor(R.id.count_big, ctx.getColor(R.color.p1))
            rv.setOnClickPendingIntent(R.id.root, if (m.state == State.READY) panel(ctx, m.id) else setup(ctx, m.id))
            return rv
        }

        // Header.
        if (k.icon) todoistIcon(ctx)?.let {
            rv.setImageViewBitmap(R.id.app_icon, it)
            rv.setViewVisibility(R.id.app_icon, View.VISIBLE)
            rv.setOnClickPendingIntent(R.id.app_icon, tap(ctx, "view", m.id.toString()))
        }
        val ready = m.state == State.READY
        rv.setTextViewText(R.id.count, if (ready) m.tasks.size.toString() else "")
        if (ready && m.overdue > 0) {
            rv.setTextViewText(R.id.overdue, m.overdue.toString())
            rv.setViewVisibility(R.id.overdue, View.VISIBLE)
        }
        val status = when {
            m.error != null && k.status -> "⚠ ${m.error}"
            m.error != null -> "⚠"
            k.status && m.synced > 0 -> ago(m.synced)
            else -> null
        }
        if (status != null) {
            rv.setTextViewText(R.id.status, status)
            rv.setViewVisibility(R.id.status, View.VISIBLE)
        }
        rv.setOnClickPendingIntent(
            R.id.title_area,
            when {
                !ready -> setup(ctx, m.id)
                k.expands -> panel(ctx, m.id)
                else -> tap(ctx, "view", m.id.toString())
            },
        )
        rv.setViewVisibility(R.id.refresh, if (k.refresh) View.VISIBLE else View.GONE)
        rv.setOnClickPendingIntent(R.id.refresh, refresh(ctx))
        rv.setOnClickPendingIntent(R.id.add, tap(ctx, "add", m.id.toString()))

        // Empty state (also shown while loading, or when something needs setting up).
        val emptyText = when (m.state) {
            State.NO_TOKEN -> "Tap to connect Todoist"
            State.BAD_TOKEN -> "Todoist didn't accept the token. Tap to fix"
            State.NOT_SET_UP -> "Tap to choose a project or filter"
            State.LOADING -> m.error?.let { "⚠ $it\nTap to retry" } ?: "Loading…"
            State.READY -> m.error?.let { "⚠ $it\nTap to retry" } ?: "All done ✓"
        }
        rv.setTextViewText(R.id.empty, emptyText)
        rv.setOnClickPendingIntent(
            R.id.empty,
            if (m.state == State.READY || m.state == State.LOADING) refresh(ctx) else setup(ctx, m.id),
        )

        // The list.
        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(3)
        val showProject = m.config?.source != Config.SOURCE_PROJECT
        m.tasks.take(CAP).forEach { t ->
            items.addItem(t.id.hashCode().toLong() and 0xffffffffL, row(ctx, m, t, k, showProject))
        }
        if (m.tasks.size > CAP) {
            val more = RemoteViews(ctx.packageName, R.layout.row_more)
            more.setTextViewText(R.id.more, "+${m.tasks.size - CAP} more in Todoist")
            more.setOnClickFillInIntent(R.id.more, Intent().setData(Uri.parse("tap://more/${m.id}")))
            items.addItem(-1L, more)
        }
        rv.setRemoteAdapter(R.id.list, items.build())
        rv.setEmptyView(R.id.list, R.id.empty)
        rv.setPendingIntentTemplate(
            R.id.list,
            PendingIntent.getActivity(
                ctx, 0, Intent(ctx, TapActivity::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return rv
    }

    private fun row(ctx: Context, m: Model, t: Task, k: Kind, showProject: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, if (k.detail) R.layout.row_detail else R.layout.row_compact)
        rv.setTextViewText(R.id.content, t.content)
        rv.setImageViewResource(
            R.id.check,
            when (t.priority) {
                4 -> R.drawable.check_p1
                3 -> R.drawable.check_p2
                2 -> R.drawable.check_p3
                else -> R.drawable.check_p4
            },
        )
        val project = t.projectId?.let { m.projects[it] }?.takeIf { showProject }
        val (dueText, dueColor) = due(ctx, t, m.today, m.now)
        val inlineProject = if (k.projectInline && project != null) short(project, 14) else null
        val dueLine = listOfNotNull(dueText, inlineProject).joinToString(" · ")
        rv.setTextViewText(R.id.due, dueLine)
        rv.setViewVisibility(R.id.due, if (dueLine.isEmpty()) View.GONE else View.VISIBLE)
        if (dueColor != null && dueText != null) rv.setTextColor(R.id.due, dueColor)

        if (k.detail) {
            val meta = listOfNotNull(
                project,
                t.labels.takeIf { it.isNotEmpty() }?.joinToString(" ") { "@$it" },
                t.deadline?.let { "⚑ ${date(it, m.today)}" },
                t.description.ifBlank { null },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                rv.setTextViewText(R.id.meta, meta)
                rv.setViewVisibility(R.id.meta, View.VISIBLE)
            }
        }
        rv.setOnClickFillInIntent(R.id.row, Intent().setData(Uri.parse("tap://open/${Uri.encode(t.id)}")))
        rv.setOnClickFillInIntent(R.id.check, Intent().setData(Uri.parse("tap://complete/${Uri.encode(t.id)}")))
        return rv
    }

    /** "3d" overdue (red), "Today" / "2:30pm" (green), "Tmrw" (orange), "Fri" (purple), "Nov 12". ↻ if recurring. */
    private fun due(ctx: Context, t: Task, today: LocalDate, now: LocalTime): Pair<String?, Int?> {
        val d = t.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null to null
        val time = t.dueTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        val days = ChronoUnit.DAYS.between(d, today) // > 0: overdue by that many days
        val timeText = time?.let { clock(ctx, it) }
        val (text, color) = when {
            days >= 365 -> "${days / 365}y" to RED
            days >= 60 -> "${days / 30}mo" to RED
            days >= 14 -> "${days / 7}w" to RED
            days > 0 -> "${days}d" to RED
            days == 0L -> (timeText ?: "Today") to (if (time != null && time.isBefore(now)) RED else GREEN)
            days == -1L -> ("Tmrw" + (timeText?.let { " $it" } ?: "")) to ORANGE
            days >= -6 -> (d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) +
                (timeText?.let { " $it" } ?: "")) to PURPLE
            else -> date(d.toString(), today) to null
        }
        val withRepeat = if (t.recurring) "$text ↻" else text
        return withRepeat to color?.let { ctx.getColor(it) }
    }

    private val RED = R.color.p1
    private val ORANGE = R.color.p2
    private val GREEN = R.color.due_today
    private val PURPLE = R.color.due_week

    private fun date(iso: String, today: LocalDate): String {
        val d = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
        val pattern = if (d.year == today.year) "MMM d" else "MMM d ''yy"
        return d.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    private fun clock(ctx: Context, t: LocalTime): String =
        if (DateFormat.is24HourFormat(ctx)) t.format(DateTimeFormatter.ofPattern("HH:mm"))
        else t.format(DateTimeFormatter.ofPattern(if (t.minute == 0) "ha" else "h:mma", Locale.US)).lowercase()

    private fun short(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

    fun ago(t: Long): String {
        val min = (System.currentTimeMillis() - t) / 60_000
        return when {
            min < 1 -> "now"
            min < 60 -> "${min}m"
            min < 48 * 60 -> "${min / 60}h"
            else -> "${min / (24 * 60)}d"
        }
    }

    // ---------------------------------------------------------------- taps and icons

    private fun activity(ctx: Context, code: Int, i: Intent) =
        PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun tap(ctx: Context, what: String, arg: String) =
        activity(ctx, 0, Intent(ctx, TapActivity::class.java).setData(Uri.parse("tap://$what/$arg")))

    private fun panel(ctx: Context, id: Int) = activity(
        ctx, 0,
        Intent(ctx, PanelActivity::class.java).setData(Uri.parse("panel://$id"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    private fun setup(ctx: Context, id: Int) = activity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).setData(Uri.parse("setup://$id"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    private fun refresh(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, TaskWidget::class.java).setAction(TaskWidget.ACTION_REFRESH),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private var iconCache: Bitmap? = null

    /** Todoist's real installed icon, small. Null if Todoist isn't installed. */
    private fun todoistIcon(ctx: Context): Bitmap? {
        iconCache?.let { return it }
        val d = runCatching { ctx.packageManager.getApplicationIcon(TapActivity.TODOIST_PKG) }.getOrNull() ?: return null
        val px = 72
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px)
        d.draw(Canvas(b))
        iconCache = b
        return b
    }
}
