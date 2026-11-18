package com.jono.todoistwidget

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.Switch
import android.widget.TextView

/**
 * Link back to the Claude Code session that builds this app: a small ✦ next to the widget's title
 * (and in the full panel), plus a section in the setup screen. It's a setting, on by default.
 *
 * Reusable: copy this file into any widget app, set URL, add the `ClaudeLink` style + ic_claude
 * drawable + claude_link color, put an ImageView with that style in the title row of each layout,
 * call bind() from the renderer and addSection() from the setup screen.
 */
object ClaudeLink {
    const val URL = "https://claude.ai/code/session_01DwykddgJFQMo6DNJreTGfm"
    private const val REQUEST = 0x5E55

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("claude_link", Context.MODE_PRIVATE)

    fun shown(ctx: Context): Boolean = prefs(ctx).getBoolean("shown", true)

    fun setShown(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("shown", on).apply()

    fun intent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Shows the link on [viewId] when the setting is on and the layout has [room] for it. */
    fun bind(ctx: Context, rv: RemoteViews, viewId: Int, room: Boolean = true) {
        if (room && shown(ctx)) {
            rv.setViewVisibility(viewId, View.VISIBLE)
            rv.setOnClickPendingIntent(
                viewId,
                PendingIntent.getActivity(
                    ctx, REQUEST, intent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            rv.setViewVisibility(viewId, View.GONE)
        }
    }

    /** Setup-screen section: the on/off setting and a button that opens the session. */
    fun addSection(a: Activity, col: LinearLayout, redraw: () -> Unit) {
        val d = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        col.addView(TextView(a).apply {
            text = "Claude session"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(6))
        })
        col.addView(Switch(a).apply {
            text = "Show a link on the widget"
            textSize = 16f
            isChecked = shown(a)
            setOnCheckedChangeListener { _, on ->
                setShown(a, on)
                redraw()
            }
        })
        col.addView(TextView(a).apply {
            text = "A small ✦ next to the title opens the Claude Code session that builds this app."
            textSize = 13f
            alpha = 0.7f
            setPadding(0, dp(4), 0, dp(8))
        })
        col.addView(Button(a).apply {
            text = "Open Claude session"
            setOnClickListener { a.startActivity(intent()) }
        })
    }

    /** Id of the "Claude session" entry in the app icon's long-press menu. */
    private const val SHORTCUT = "claude_session"

    /**
     * Puts "Claude session" in the app icon's long-press menu. Launchers don't let apps add items to
     * a widget's own long-press menu, so the widget side is the settings entry (see [addTopButton]).
     * Cheap: does nothing once the shortcut exists, so it's safe to call on every widget update.
     */
    fun syncShortcut(ctx: Context) {
        try {
            val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
            if (sm.dynamicShortcuts.any { it.id == SHORTCUT }) return
            val info = ShortcutInfo.Builder(ctx, SHORTCUT)
                .setShortLabel("Claude session")
                .setLongLabel("Open Claude session")
                .setIcon(Icon.createWithResource(ctx, R.drawable.ic_claude))
                .setIntent(Intent(ctx, ClaudeLinkActivity::class.java).setAction(Intent.ACTION_VIEW))
                .build()
            sm.addDynamicShortcuts(listOf(info))
        } catch (_: Exception) {
        }
    }

    /**
     * A button near the top of the setup screen. The widget's long-press menu has one entry apps can
     * add (widget settings → this screen), so this keeps the session one tap from that menu.
     */
    fun addTopButton(a: Activity, col: LinearLayout, index: Int = 1) {
        syncShortcut(a)
        col.addView(Button(a).apply {
            text = "✦  Open Claude session"
            isAllCaps = false
            setOnClickListener { a.startActivity(intent()) }
        }, minOf(index, col.childCount))
    }
}

/** Opened by the app-icon shortcut: opens the Claude session and closes straight away. */
class ClaudeLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivity(ClaudeLink.intent())
        } catch (_: Exception) {
        }
        finish()
    }
}
