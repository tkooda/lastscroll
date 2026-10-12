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
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView

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

        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        val list = findViewById<ListView>(R.id.apps)
        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, apps.map { it.first })
        val selected = selected(this)
        apps.forEachIndexed { i, app -> list.setItemChecked(i, app.second in selected) }
        list.setOnItemClickListener { _, _, _, _ ->
            val set = apps.filterIndexed { i, _ -> list.isItemChecked(i) }.map { it.second }.toSet()
            prefs.edit().putStringSet(KEY_APPS, set).apply()
        }
    }

    override fun onResume() {
        super.onResume()
        status.setText(if (ActivityService.instance != null) R.string.a11y_on else R.string.a11y_off)
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
