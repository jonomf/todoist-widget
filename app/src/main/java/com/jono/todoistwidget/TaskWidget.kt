package com.jono.todoistwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** The widget. Each placed copy has its own project / filter (see MainActivity); drawing is in WidgetRenderer. */
class TaskWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetRenderer.render(ctx, ids)
        SyncJob.schedulePeriodic(ctx)
        val pending = goAsync()
        Sync.refresh(ctx, ids) { pending.finish() }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        WidgetRenderer.render(ctx, intArrayOf(id))
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        ids.forEach { Store.remove(ctx, it) }
    }

    override fun onDisabled(ctx: Context) {
        SyncJob.cancelAll(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                val pending = goAsync()
                Sync.refresh(ctx) { pending.finish() }
            }
            ACTION_COMPLETE -> {
                val task = intent.getStringExtra(EXTRA_TASK) ?: return
                val pending = goAsync()
                Sync.complete(ctx, task) { pending.finish() }
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.jono.todoistwidget.REFRESH"
        const val ACTION_COMPLETE = "com.jono.todoistwidget.COMPLETE"
        const val EXTRA_TASK = "task"
    }
}

/**
 * Invisible tap handler. Widget taps that open something (a task, quick add, the list in Todoist)
 * or complete a task come here as a URI, e.g. tap://open/<taskId>, tap://complete/<taskId>,
 * tap://add/<widgetId>, tap://view/<widgetId>. It acts and closes immediately.
 */
class TapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        val arg = uri?.lastPathSegment.orEmpty()
        when (uri?.host) {
            "complete" -> sendBroadcast(
                Intent(this, TaskWidget::class.java).setAction(TaskWidget.ACTION_COMPLETE)
                    .putExtra(TaskWidget.EXTRA_TASK, arg),
            )
            "open" -> openTodoist("todoist://task?id=$arg", "https://app.todoist.com/app/task/$arg")
            "add" -> openAdd(arg.toIntOrNull())
            "view" -> openView(arg.toIntOrNull())
            "more" -> openView(arg.toIntOrNull())
        }
        finish()
        overridePendingTransition(0, 0)
    }

    private fun openAdd(widgetId: Int?) {
        val cfg = widgetId?.let { Store.config(this, it) }
        val q = if (cfg != null && cfg.source == Config.SOURCE_PROJECT) "?project_id=${Uri.encode(cfg.ref)}" else ""
        openTodoist("todoist://addtask$q", null)
    }

    /** The widget's project or saved filter, in Todoist. A custom query opens Todoist's search. */
    private fun openView(widgetId: Int?) {
        val cfg = widgetId?.let { Store.config(this, it) }
        if (cfg == null) {
            openTodoist("todoist://", null)
            return
        }
        when (cfg.source) {
            Config.SOURCE_PROJECT -> openTodoist(
                "todoist://project?id=${Uri.encode(cfg.ref)}", "https://app.todoist.com/app/project/${cfg.ref}",
            )
            Config.SOURCE_FILTER -> openTodoist(
                "todoist://filter?id=${Uri.encode(cfg.ref)}", "https://app.todoist.com/app/filter/${cfg.ref}",
            )
            else -> openTodoist("todoist://search?query=${Uri.encode(cfg.query)}", null)
        }
    }

    /** Opens Todoist at [appUrl]; if that fails, the web URL, or failing that just the Todoist app. */
    private fun openTodoist(appUrl: String, webUrl: String?) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val attempts = listOfNotNull(
            Intent(Intent.ACTION_VIEW, Uri.parse(appUrl)).setPackage(TODOIST_PKG),
            webUrl?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) },
            packageManager.getLaunchIntentForPackage(TODOIST_PKG),
        )
        for (i in attempts) {
            try {
                startActivity(i.addFlags(flags))
                SyncJob.scheduleSoon(this)
                return
            } catch (e: ActivityNotFoundException) {
                // try the next one
            }
        }
    }

    companion object {
        const val TODOIST_PKG = "com.todoist"
    }
}
