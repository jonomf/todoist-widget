package com.jono.todoistwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Setup screen, and the "settings" entry in the widget's long-press menu.
 * Sections: Todoist account, this widget (when opened for one), your widgets, updates.
 */
class MainActivity : Activity() {
    private lateinit var col: LinearLayout
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var fromConfigure = false

    // Form state for "this widget", kept across rebuilds.
    private var formFor = AppWidgetManager.INVALID_APPWIDGET_ID
    private var name = ""
    private var autoName = ""
    private var selected = "" // "p:<projectId>", "f:<filterId>" or "q"
    private var query = ""
    private var sort = Config.SORT_DATE

    private var accountStatus: String? = null
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(col) })
        readIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        build()
    }

    private fun readIntent(i: Intent) {
        widgetId = i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        fromConfigure = i.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
        if (fromConfigure) {
            // Settings are optional: the widget is fine as placed even if you back out.
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        }
    }

    override fun onResume() {
        super.onResume()
        build()
        loadAccount()
        WidgetRenderer.renderAll(this)
    }

    /** Fetches your name, projects and saved filters, so the pickers below are current. */
    private fun loadAccount() {
        val token = Store.token(this) ?: return
        loading = true
        Thread {
            val status = try {
                val m = Api.meta(token)
                Store.saveMeta(this, m.projects, m.filters, m.user)
                Store.setAuthError(this, false)
                "✓ Connected" + (m.user?.let { " as $it" } ?: "")
            } catch (e: Exception) {
                if (e is Api.HttpError && (e.code == 401 || e.code == 403)) Store.setAuthError(this, true)
                "⚠ ${Sync.describe(e)}"
            }
            runOnUiThread {
                loading = false
                accountStatus = status
                if (!isFinishing) build()
            }
        }.start()
    }

    // ---------------------------------------------------------------- UI

    private fun build() {
        col.removeAllViews()
        col.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(note(
            "Each widget shows one Todoist project or filter, most overdue first. Resize it to any " +
                "size; tap a circle to complete a task, a task to open it in Todoist, the title for the " +
                "full view, + to add a task.",
        ))

        val hasToken = Store.token(this) != null
        val editing = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        if (editing && hasToken) widgetSection()
        accountSection(hasToken)
        widgetsSection()
        updatesSection()
    }

    private fun accountSection(hasToken: Boolean) {
        col.addView(heading("Todoist account"))
        col.addView(TextView(this).apply {
            textSize = 15f
            text = when {
                !hasToken -> "Not connected yet."
                Store.authError(this@MainActivity) -> "⚠ Todoist didn't accept this token. Paste a new one."
                accountStatus != null -> accountStatus
                loading -> "Checking…"
                else -> "Token saved."
            }
        })
        val field = EditText(this).apply {
            hint = if (hasToken) "Paste a new API token to replace it" else "Paste your Todoist API token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        col.addView(field)
        col.addView(Button(this).apply {
            text = "Save token"
            setOnClickListener {
                val t = field.text.toString().trim()
                if (t.length < 20) {
                    Toast.makeText(this@MainActivity, "That doesn't look like a Todoist token", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Store.setToken(this@MainActivity, t)
                accountStatus = null
                field.setText("")
                build()
                loadAccount()
                Sync.refresh(this@MainActivity)
            }
        })
        col.addView(Button(this).apply {
            text = "Find my token in Todoist"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://app.todoist.com/app/settings/integrations/developer")))
            }
        })
        col.addView(note(
            "Todoist → Settings → Integrations → Developer → copy API token. It stays on this phone and " +
                "is only ever sent to api.todoist.com. The app's only permission is internet access.",
        ))
    }

    /** Pick what this widget shows: a saved filter, a project, or your own query. */
    private fun widgetSection() {
        val cfg = Store.config(this, widgetId)
        if (formFor != widgetId) {
            formFor = widgetId
            name = cfg?.name.orEmpty()
            autoName = name
            selected = when {
                cfg == null -> ""
                cfg.source == Config.SOURCE_PROJECT -> "p:${cfg.ref}"
                cfg.source == Config.SOURCE_FILTER -> "f:${cfg.ref}"
                else -> "q"
            }
            query = if (cfg != null && cfg.source == Config.SOURCE_QUERY) cfg.query else ""
            sort = cfg?.sort ?: Config.SORT_DATE
        }

        col.addView(heading(if (cfg == null) "Set up this widget" else "This widget"))
        val nameField = EditText(this).apply {
            hint = "Name (shown as the widget's title)"
            setText(name)
            isSingleLine = true
            addTextChangedListener(watcher { name = it })
        }
        col.addView(nameField)

        val filters = Store.filters(this)
        val projects = Store.projects(this)
        val group = RadioGroup(this)
        val keys = HashMap<Int, String>()
        val labels = HashMap<String, String>()
        fun option(key: String, label: String, sub: String? = null) {
            val b = RadioButton(this).apply {
                id = View.generateViewId()
                text = if (sub != null) "$label   ·   $sub" else label
                isChecked = key == selected
            }
            keys[b.id] = key
            labels[key] = label
            group.addView(b)
        }
        if (filters.isNotEmpty()) {
            group.addView(subheading("Your saved filters"))
            filters.forEach { option("f:${it.id}", it.name, it.query) }
        }
        if (projects.isNotEmpty()) {
            group.addView(subheading("Projects"))
            projects.forEach { option("p:${it.id}", it.name) }
        }
        if (filters.isEmpty() && projects.isEmpty()) {
            group.addView(subheading(if (loading) "Loading your projects and filters…" else "No projects or filters found"))
        }
        group.addView(subheading("Or any filter query"))
        option("q", "Custom query")
        col.addView(group)

        val queryField = EditText(this).apply {
            hint = "e.g. (overdue | today) & #Work"
            setText(query)
            isSingleLine = true
            addTextChangedListener(watcher { query = it })
            visibility = if (selected == "q") View.VISIBLE else View.GONE
        }
        col.addView(queryField)

        group.setOnCheckedChangeListener { _, checkedId ->
            val key = keys[checkedId] ?: return@setOnCheckedChangeListener
            selected = key
            queryField.visibility = if (key == "q") View.VISIBLE else View.GONE
            // Fill the name from the choice, unless you've typed your own.
            val current = nameField.text.toString()
            if (key != "q" && (current.isBlank() || current == autoName)) {
                autoName = labels[key].orEmpty()
                nameField.setText(autoName)
            }
        }

        col.addView(subheading("Order"))
        val sortGroup = RadioGroup(this)
        listOf(Config.SORT_DATE to "Most overdue first, then priority", Config.SORT_PRIORITY to "Priority first, then date")
            .forEach { (value, label) ->
                sortGroup.addView(RadioButton(this).apply {
                    id = View.generateViewId()
                    text = label
                    isChecked = sort == value
                    setOnClickListener { sort = value }
                })
            }
        col.addView(sortGroup)

        col.addView(Button(this).apply {
            text = "Save widget"
            setOnClickListener {
                name = nameField.text.toString().trim()
                query = queryField.text.toString().trim()
                save()
            }
        })
    }

    private fun save() {
        val newCfg = when {
            selected.startsWith("p:") -> {
                val id = selected.removePrefix("p:")
                val p = Store.projects(this).firstOrNull { it.id == id }
                Config(name.ifBlank { p?.name ?: "Project" }, Config.SOURCE_PROJECT, id, "", sort)
            }
            selected.startsWith("f:") -> {
                val id = selected.removePrefix("f:")
                val f = Store.filters(this).firstOrNull { it.id == id }
                Config(name.ifBlank { f?.name ?: "Filter" }, Config.SOURCE_FILTER, id, f?.query.orEmpty(), sort)
            }
            selected == "q" && query.isNotBlank() -> Config(name.ifBlank { query }, Config.SOURCE_QUERY, query, query, sort)
            else -> {
                Toast.makeText(this, "Pick a filter or project, or type a query", Toast.LENGTH_SHORT).show()
                return
            }
        }
        val old = Store.config(this, widgetId)
        Store.setConfig(this, widgetId, newCfg)
        if (old == null || old.source != newCfg.source || old.ref != newCfg.ref || old.query != newCfg.query) {
            Store.clearTasks(this, widgetId) // show "Loading…" rather than the old list
        }
        Store.setError(this, widgetId, null)
        WidgetRenderer.renderAll(this)
        Sync.refresh(this, intArrayOf(widgetId))
        SyncJob.schedulePeriodic(this)
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun widgetsSection() {
        col.addView(heading("Your widgets"))
        val ids = WidgetRenderer.widgetIds(this)
        if (ids.isEmpty()) col.addView(note("No widgets on your home screen yet."))
        ids.forEach { id ->
            col.addView(TextView(this).apply {
                text = WidgetRenderer.describe(this@MainActivity, id)
                textSize = 14f
                setTextIsSelectable(true)
                setPadding(0, dp(8), 0, 0)
            })
            if (id != widgetId) col.addView(Button(this).apply {
                text = "Change this widget"
                setOnClickListener {
                    startActivity(
                        Intent(this@MainActivity, MainActivity::class.java)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                    )
                }
            })
        }
        col.addView(Button(this).apply {
            text = "Add a widget"
            setOnClickListener { pinWidget() }
        })
        col.addView(Button(this).apply {
            text = "Refresh now"
            setOnClickListener {
                Sync.refresh(this@MainActivity) { runOnUiThread { if (!isFinishing) build() } }
            }
        })
    }

    private fun updatesSection() {
        col.addView(heading("Updates"))
        val build = UpdateStatus.currentBuild(this)
        val hasUpdater = UpdateStatus.updaterIntent(this) != null
        val latest = UpdateStatus.latest(this)
        col.addView(TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, dp(8))
            text = when {
                !hasUpdater -> "You're on build $build. Install Widget Updater to get an update symbol on " +
                    "the widget and one-tap updates for all your widgets."
                latest > build -> "Build $latest is available (you're on $build)."
                else -> "You're on build $build. Widget Updater keeps this up to date; the widget shows " +
                    "a small ↑ symbol when there's a new build."
            }
        })
        col.addView(Button(this).apply {
            text = when {
                !hasUpdater -> "Get Widget Updater"
                latest > build -> "Update now"
                else -> "Open Widget Updater"
            }
            setOnClickListener {
                startActivity(
                    UpdateStatus.updaterIntent(this@MainActivity)
                        ?: Intent(Intent.ACTION_VIEW, Uri.parse(UpdateStatus.UPDATER_RELEASES)),
                )
            }
        })
        col.addView(Button(this).apply {
            text = "Get the latest build in the browser"
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))) }
        })
    }

    private fun pinWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, TaskWidget::class.java), null, null)
        } else {
            Toast.makeText(this, "Long-press your home screen → Widgets → Todoist Widget", Toast.LENGTH_LONG).show()
        }
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun subheading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        alpha = 0.7f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(2))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        alpha = 0.7f
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun watcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val RELEASES_URL = "https://github.com/jonomf/todoist-widget/releases/latest"
    }
}
