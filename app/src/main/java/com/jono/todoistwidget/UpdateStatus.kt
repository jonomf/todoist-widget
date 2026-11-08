package com.jono.todoistwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Update symbol support. This app stays offline: the separate Widget Updater app checks GitHub
 * and sends us the latest build number (UpdateStatusReceiver). When it's newer than this build,
 * the widget shows a small update symbol; tapping it opens the Updater to install the update.
 *
 * Reusable: copy this file into any widget app, plus the manifest receiver (with the repo/asset
 * meta-data) and the <queries> entry for com.jono.widgetupdater.
 */
object UpdateStatus {
    const val UPDATER_PKG = "com.jono.widgetupdater"
    const val UPDATER_RELEASES = "https://github.com/jonomf/widget-updater/releases/latest"
    private const val EXTRA_LATEST = "latest"
    private const val EXTRA_PACKAGE = "package"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("update_status", Context.MODE_PRIVATE)

    fun currentBuild(ctx: Context): Long = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    fun latest(ctx: Context): Long = prefs(ctx).getLong("latest", 0L)

    /** True when the Updater has reported a newer build than the one installed. */
    fun available(ctx: Context): Boolean = latest(ctx) > currentBuild(ctx)

    /** Opens the Updater straight into updating this app; null if the Updater isn't installed. */
    fun updaterIntent(ctx: Context): Intent? =
        ctx.packageManager.getLaunchIntentForPackage(UPDATER_PKG)
            ?.putExtra(EXTRA_PACKAGE, ctx.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun onStatus(ctx: Context, intent: Intent) {
        val latest = intent.getLongExtra(EXTRA_LATEST, 0L)
        if (latest <= 0L) return
        prefs(ctx).edit().putLong("latest", latest).apply()
    }
}

/** Receives the latest build number from the Widget Updater, then redraws the widget. */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        UpdateStatus.onStatus(ctx, intent)
        WidgetRenderer.renderAll(ctx)
    }
}
