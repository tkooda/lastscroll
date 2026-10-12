package com.ionecho.lastscroll

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private class App(val label: String, val pkg: String, val icon: Drawable)

    private lateinit var status: TextView
    private var apps: List<App>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val prefs = prefs(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        status = findViewById(R.id.status)
        findViewById<Button>(R.id.accessibility).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.disclosure_title)
                .setMessage(R.string.disclosure)
                .setPositiveButton(R.string.agree) { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton(R.string.decline, null)
                .show()
        }

        val minutes = findViewById<EditText>(R.id.minutes)
        minutes.setText(minutes(this).toString())
        minutes.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                val m = s.toString().toIntOrNull()
                if (m != null && m > 0) prefs.edit().putInt(KEY_MINUTES, m).apply()
            }
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
        })

        findViewById<Button>(R.id.start).setOnClickListener {
            startForegroundService(Intent(this, TimerService::class.java))
        }

        findViewById<Button>(R.id.shortcut).setOnClickListener {
            val sm = getSystemService(ShortcutManager::class.java)
            if (sm.isRequestPinShortcutSupported) {
                val info = ShortcutInfo.Builder(this, "pinned_start")
                    .setShortLabel(getString(R.string.shortcut_label))
                    .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                    .setIntent(Intent(this, StartActivity::class.java).setAction(Intent.ACTION_MAIN))
                    .build()
                sm.requestPinShortcut(info, null)
            } else {
                Toast.makeText(this, R.string.no_pin, Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.add_apps).setOnClickListener { showAppsDialog() }

        val pm = packageManager
        Thread {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val loaded = pm.queryIntentActivities(launcher, 0)
                .filter { it.activityInfo.packageName != packageName }
                .distinctBy { it.activityInfo.packageName }
                .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                apps = loaded
                findViewById<Button>(R.id.add_apps).isEnabled = true
                showSelected()
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        val on = ActivityService.instance != null
        status.setText(if (on) R.string.a11y_on else R.string.a11y_off)
        findViewById<View>(R.id.a11y_off).visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun bindRow(row: View, app: App) {
        row.findViewById<ImageView>(R.id.icon).setImageDrawable(app.icon)
        row.findViewById<TextView>(R.id.label).text = app.label
    }

    private fun showSelected() {
        val selected = selected(this)
        val shown = apps.orEmpty().filter { it.pkg in selected }
        val container = findViewById<LinearLayout>(R.id.apps)
        container.removeAllViews()
        for (app in shown) {
            val row = layoutInflater.inflate(R.layout.app_row, container, false)
            bindRow(row, app)
            container.addView(row)
        }
        findViewById<View>(R.id.no_apps).visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAppsDialog() {
        val all = apps ?: return
        val checked = selected(this).toMutableSet()
        var shown = all
        val view = layoutInflater.inflate(R.layout.apps_dialog, null)
        val list = view.findViewById<ListView>(R.id.list)
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.app_row, parent, false)
                val app = shown[position]
                bindRow(row, app)
                row.findViewById<CheckBox>(R.id.check).apply {
                    visibility = View.VISIBLE
                    isChecked = app.pkg in checked
                }
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, row, position, _ ->
            val pkg = shown[position].pkg
            if (!checked.remove(pkg)) checked.add(pkg)
            row.findViewById<CheckBox>(R.id.check).isChecked = pkg in checked
        }
        view.findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                val q = s.toString().trim()
                shown = all.filter { it.label.contains(q, ignoreCase = true) }
                adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
        })
        AlertDialog.Builder(this)
            .setTitle(R.string.add_apps)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val set = all.map { it.pkg }.filter { it in checked }.toSet()
                prefs(this).edit().putStringSet(KEY_APPS, set).apply()
                showSelected()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val KEY_APPS = "apps"
        private const val KEY_MINUTES = "minutes"

        private fun prefs(c: Context): SharedPreferences =
            c.getSharedPreferences("settings", Context.MODE_PRIVATE)

        fun selected(c: Context): Set<String> = prefs(c).getStringSet(KEY_APPS, emptySet()) ?: emptySet()

        fun minutes(c: Context): Int = prefs(c).getInt(KEY_MINUTES, 20)
    }
}
