package com.jono.todoistwidget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Fetches tasks for every widget. All network work runs on one background thread, in order, so a
 * completion and the refresh after it never race.
 */
object Sync {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Refreshes the given widgets (all if null), then redraws. [done] runs afterwards on the worker thread. */
    fun refresh(ctx: Context, ids: IntArray? = null, done: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        exec.execute {
            try {
                fetch(app, ids)
            } finally {
                WidgetRenderer.renderAll(app)
                done?.invoke()
            }
        }
    }

    /**
     * Completes a task: it disappears from every widget at once, then Todoist is told. If that
     * fails it comes back with a message. Then everything refreshes (a recurring task returns
     * with its next date).
     */
    fun complete(ctx: Context, taskId: String, done: () -> Unit) {
        val app = ctx.applicationContext
        Store.hide(taskId)
        WidgetRenderer.renderAll(app)
        exec.execute {
            try {
                val token = Store.token(app) ?: throw IOException("Not connected")
                Api.close(token, taskId)
                fetch(app, null)
            } catch (e: Exception) {
                main.post { Toast.makeText(app, "Couldn't complete it: ${describe(e)}", Toast.LENGTH_LONG).show() }
            } finally {
                Store.unhide(taskId)
                WidgetRenderer.renderAll(app)
                done()
            }
        }
    }

    private fun fetch(ctx: Context, only: IntArray?) {
        val token = Store.token(ctx) ?: return
        val ids = only ?: WidgetRenderer.widgetIds(ctx)
        if (ids.isEmpty()) return

        // Projects (for names) and saved filters (their queries may have been edited in Todoist).
        try {
            val m = Api.meta(token)
            Store.saveMeta(ctx, m.projects, m.filters, m.user)
            Store.setAuthError(ctx, false)
        } catch (e: Api.HttpError) {
            if (e.code == 401 || e.code == 403) {
                Store.setAuthError(ctx, true)
                return
            }
        } catch (e: Exception) {
            ids.forEach { Store.setError(ctx, it, describe(e)) }
            return
        }

        val filters = Store.filters(ctx).associateBy { it.id }
        for (id in ids) {
            var cfg = Store.config(ctx, id) ?: continue
            if (cfg.source == Config.SOURCE_FILTER) {
                val f = filters[cfg.ref]
                if (f != null && f.query != cfg.query) {
                    cfg = cfg.copy(query = f.query)
                    Store.setConfig(ctx, id, cfg)
                }
            }
            try {
                val tasks = when (cfg.source) {
                    Config.SOURCE_PROJECT -> Api.tasksByProject(token, cfg.ref)
                    else -> Api.tasksByQuery(token, cfg.query)
                }
                Store.saveTasks(ctx, id, tasks)
            } catch (e: Exception) {
                Store.setError(ctx, id, describe(e))
            }
        }
    }

    fun describe(e: Exception): String = when {
        e is Api.HttpError && (e.code == 401 || e.code == 403) -> "Todoist token not accepted"
        e is Api.HttpError && e.code == 400 -> "Todoist: ${e.message}"
        e is Api.HttpError -> "Todoist error ${e.code}"
        e is java.net.UnknownHostException || e is java.net.SocketTimeoutException ||
            e is java.net.ConnectException -> "offline"
        else -> e.message ?: e.javaClass.simpleName
    }
}

/** Background refresh: every ~15 min while online, plus a couple of times after you visit Todoist. */
class SyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Sync.refresh(this) { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val PERIODIC = 1
        private const val SOON_1 = 2
        private const val SOON_2 = 3

        private fun js(ctx: Context) = ctx.getSystemService(JobScheduler::class.java)
        private fun component(ctx: Context) = ComponentName(ctx, SyncJob::class.java)

        fun schedulePeriodic(ctx: Context) {
            if (js(ctx).getPendingJob(PERIODIC) != null) return
            js(ctx).schedule(
                JobInfo.Builder(PERIODIC, component(ctx))
                    .setPeriodic(15 * 60 * 1000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build(),
            )
        }

        /** After opening Todoist from the widget: catch whatever you changed there when you come back. */
        fun scheduleSoon(ctx: Context) {
            for ((id, delay) in listOf(SOON_1 to 25_000L, SOON_2 to 120_000L)) {
                js(ctx).schedule(
                    JobInfo.Builder(id, component(ctx))
                        .setMinimumLatency(delay)
                        .setOverrideDeadline(delay + 60_000L)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .build(),
                )
            }
        }

        fun cancelAll(ctx: Context) = js(ctx).cancelAll()
    }
}
