package com.jono.todoistwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import kotlin.math.min

/**
 * The expand panel: tap a widget's title and this floats over the home screen showing that
 * widget's full view (two-line rows with projects, labels, descriptions). It's drawn with the
 * same code as the widget and redraws with it. Tap outside to close.
 */
class PanelActivity : Activity() {
    private lateinit var card: FrameLayout
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val redraw: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        val dm = resources.displayMetrics
        val dp = dm.density
        val width = min(dm.widthPixels - (32 * dp).toInt(), (440 * dp).toInt())
        val height = min(dm.heightPixels - (160 * dp).toInt(), (600 * dp).toInt())

        val root = FrameLayout(this).apply { setOnClickListener { finish() } }
        card = FrameLayout(this).apply { isClickable = true } // taps inside don't close it
        root.addView(card, FrameLayout.LayoutParams(width, height, Gravity.CENTER))
        setContentView(root)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        render()
    }

    override fun onResume() {
        super.onResume()
        WidgetRenderer.listeners.add(redraw)
        render()
    }

    override fun onPause() {
        WidgetRenderer.listeners.remove(redraw)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        finish() // opening Todoist from the panel closes it
    }

    private fun render() {
        if (isFinishing || widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        card.removeAllViews()
        card.addView(WidgetRenderer.buildPanel(this, widgetId).apply(this, card))
    }
}
