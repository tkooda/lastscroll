package com.ionecho.lastscroll

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.ViewGroup
import android.widget.NumberPicker
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private class App(val label: String, val pkg: String, val icon: Drawable)

    private var a11yOn by mutableStateOf(false)
    private var notifOn by mutableStateOf(true)
    private var a11yTried by mutableStateOf(false)
    private var minutes by mutableIntStateOf(20)
    private var display by mutableIntStateOf(DISPLAY_BOTH)
    private var selected by mutableStateOf(emptySet<String>())
    private var requestedAt = 0L

    private val notifRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // No dialog was shown (answered too fast): the permission is blocked, so go to settings.
        if (!granted && SystemClock.elapsedRealtime() - requestedAt < 500) openNotificationSettings()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        minutes = minutes(this)
        display = display(this)
        selected = selected(this)
        a11yTried = prefs(this).getBoolean(KEY_A11Y_TRIED, false)
        val pm = packageManager
        setContent {
            AppTheme {
                val apps by produceState<List<App>?>(null) {
                    value = withContext(Dispatchers.IO) {
                        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        pm.queryIntentActivities(launcher, 0)
                            .filter { it.activityInfo.packageName != packageName }
                            .distinctBy { it.activityInfo.packageName }
                            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
                            .sortedBy { it.label.lowercase() }
                    }
                }
                ConfigScreen(apps)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        a11yOn = ActivityService.instance != null
        notifOn = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }

    private fun grantNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestedAt = SystemClock.elapsedRealtime()
            notifRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        )
    }

    private fun openAccessibilitySettings() {
        a11yTried = true
        prefs(this).edit().putBoolean(KEY_A11Y_TRIED, true).apply()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun saveMinutes(m: Int) {
        minutes = m
        prefs(this).edit().putInt(KEY_MINUTES, m).apply()
    }

    private fun saveDisplay(d: Int) {
        display = d
        prefs(this).edit().putInt(KEY_DISPLAY, d).apply()
        TimerService.instance?.refresh()
    }

    private fun saveApps(set: Set<String>) {
        selected = set
        prefs(this).edit().putStringSet(KEY_APPS, set).apply()
    }

    private fun addShortcut() {
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ConfigScreen(apps: List<App>?) {
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        var showDisclosure by rememberSaveable { mutableStateOf(false) }
        var showMinutes by rememberSaveable { mutableStateOf(false) }
        var showDisplay by rememberSaveable { mutableStateOf(false) }
        var showPicker by rememberSaveable { mutableStateOf(false) }
        val shown = apps.orEmpty().filter { it.pkg in selected }
        val ready = notifOn && a11yOn && shown.isNotEmpty()

        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            LazyColumn(
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                if (TimerService.running) {
                    item {
                        FilledTonalButton(
                            onClick = { TimerService.stop(this@MainActivity) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_stop),
                                contentDescription = null,
                                Modifier.size(ButtonDefaults.IconSize),
                            )
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.stop))
                        }
                    }
                } else if (ready) {
                    item {
                        Button(
                            onClick = { startForegroundService(Intent(this@MainActivity, TimerService::class.java)) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.start))
                        }
                    }
                }
                if (!notifOn || !a11yOn) {
                    item { SectionHeader(R.string.section_permissions) }
                    if (!notifOn) {
                        item {
                            PermissionRow(
                                title = R.string.notif_permission,
                                why = R.string.notif_why,
                                button = R.string.notif_grant,
                                onGrant = ::grantNotifications,
                            )
                        }
                    }
                    if (!a11yOn) {
                        item {
                            PermissionRow(
                                title = R.string.a11y_permission,
                                why = R.string.a11y_why,
                                button = R.string.a11y_grant,
                                onGrant = { showDisclosure = true },
                            )
                        }
                        if (a11yTried) item { RestrictedHelp() }
                    }
                    item { SectionDivider() }
                }
                item { SectionHeader(R.string.section_timer) }
                item {
                    ListItem(
                        modifier = Modifier.clickable { showMinutes = true },
                        headlineContent = { Text(stringResource(R.string.idle_title)) },
                        supportingContent = {
                            Text(pluralStringResource(R.plurals.idle_summary, minutes, minutes))
                        },
                        trailingContent = { DropdownValue(stringResource(R.string.minutes_value, minutes)) },
                    )
                }
                item {
                    ListItem(
                        modifier = Modifier.clickable { showDisplay = true },
                        headlineContent = { Text(stringResource(R.string.display_title)) },
                        trailingContent = { DropdownValue(stringResource(DISPLAY_LABELS[display])) },
                    )
                }

                item { SectionDivider() }
                item { SectionHeader(R.string.section_apps) }
                if (apps == null) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (shown.isEmpty()) {
                    item { EmptyApps() }
                } else {
                    items(shown, key = { it.pkg }) { app ->
                        val remove = stringResource(R.string.remove_app, app.label)
                        ListItem(
                            headlineContent = { Text(app.label) },
                            leadingContent = { AppIcon(app) },
                            trailingContent = {
                                IconButton(onClick = { saveApps(selected - app.pkg) }) {
                                    Icon(Icons.Filled.Close, contentDescription = remove)
                                }
                            },
                        )
                    }
                }
                item {
                    FilledTonalButton(
                        onClick = { showPicker = true },
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.add_apps))
                    }
                }

                item { SectionDivider() }
                item {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                        Text(
                            stringResource(R.string.shortcut_text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = ::addShortcut,
                            modifier = Modifier.padding(top = 12.dp),
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        ) {
                            Icon(Icons.Filled.Home, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.add_shortcut))
                        }
                    }
                }
            }
        }

        if (showDisclosure) {
            AlertDialog(
                onDismissRequest = { showDisclosure = false },
                modifier = Modifier.padding(horizontal = 24.dp),
                properties = DialogProperties(usePlatformDefaultWidth = false),
                icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                title = { Text(stringResource(R.string.disclosure_title)) },
                text = {
                    Text(stringResource(R.string.disclosure), Modifier.verticalScroll(rememberScrollState()))
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDisclosure = false
                        openAccessibilitySettings()
                    }) { Text(stringResource(R.string.agree)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDisclosure = false }) { Text(stringResource(R.string.decline)) }
                },
            )
        }
        if (showMinutes) {
            MinutesDialog(onDismiss = { showMinutes = false }, onSave = {
                showMinutes = false
                saveMinutes(it)
            })
        }
        if (showDisplay) {
            DisplayDialog(onDismiss = { showDisplay = false }, onSave = {
                showDisplay = false
                saveDisplay(it)
            })
        }
        if (showPicker) {
            AppPicker(apps, onDismiss = { showPicker = false }, onSave = {
                showPicker = false
                saveApps(it)
            })
        }
    }

    @Composable
    private fun SectionHeader(text: Int) {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                .semantics { heading() },
        )
    }

    @Composable
    private fun SectionDivider() {
        HorizontalDivider(Modifier.padding(top = 16.dp))
    }

    // Current value of a setting that opens a picker, with a dropdown arrow.
    @Composable
    private fun DropdownValue(value: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
    }

    @Composable
    private fun PermissionRow(title: Int, why: Int, button: Int, onGrant: () -> Unit) {
        val color = MaterialTheme.colorScheme.error
        ListItem(
            leadingContent = { Icon(Icons.Filled.Warning, contentDescription = null, tint = color) },
            headlineContent = { Text(stringResource(title)) },
            supportingContent = {
                Column {
                    Text(stringResource(R.string.not_enabled), color = color, style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(why), Modifier.padding(top = 4.dp))
                    Button(onClick = onGrant, modifier = Modifier.padding(top = 12.dp)) {
                        Text(stringResource(button))
                    }
                }
            },
        )
    }

    @Composable
    private fun RestrictedHelp() {
        Card(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.help_steps), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.help_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = ::openAppInfo, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.open_app_info))
                }
            }
        }
    }

    @Composable
    private fun EmptyApps() {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.no_apps_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                stringResource(R.string.no_apps_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    @Composable
    private fun AppIcon(app: App) {
        val px = with(LocalDensity.current) { 40.dp.roundToPx() }
        val bitmap = remember(app.pkg) { app.icon.toImageBitmap(px) }
        Image(bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
    }

    @Composable
    private fun MinutesDialog(onDismiss: () -> Unit, onSave: (Int) -> Unit) {
        var value by rememberSaveable { mutableIntStateOf(minutes) }
        AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.padding(horizontal = 24.dp),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            title = { Text(stringResource(R.string.idle_title)) },
            text = {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AndroidView(factory = { ctx ->
                        NumberPicker(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                            minValue = 1
                            maxValue = 180
                            this.value = value
                            wrapSelectorWheel = false
                            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                            setOnValueChangedListener { _, _, v -> value = v }
                        }
                    })
                    Text(
                        stringResource(R.string.minutes_unit),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { onSave(value) }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }

    @Composable
    private fun DisplayDialog(onDismiss: () -> Unit, onSave: (Int) -> Unit) {
        var value by rememberSaveable { mutableIntStateOf(display) }
        AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.padding(horizontal = 24.dp),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            title = { Text(stringResource(R.string.display_title)) },
            text = {
                Column {
                    DISPLAY_LABELS.forEachIndexed { i, label ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = value == i, role = Role.RadioButton, onClick = { value = i })
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == i, onClick = null)
                            Text(
                                stringResource(label),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onSave(value) }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppPicker(apps: List<App>?, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
        var query by rememberSaveable { mutableStateOf("") }
        var checked by rememberSaveable { mutableStateOf(selected.toList()) }
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Scaffold(
                topBar = { TopAppBar(title = { Text(stringResource(R.string.choose_apps)) }) },
                bottomBar = {
                    BottomAppBar(windowInsets = BottomAppBarDefaults.windowInsets.union(WindowInsets.ime)) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val set = checked.toSet()
                                onSave(apps.orEmpty().map { it.pkg }.filter { it in set }.toSet())
                            },
                            enabled = apps != null,
                            modifier = Modifier.padding(end = 12.dp),
                        ) { Text(stringResource(R.string.save)) }
                    }
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text(stringResource(R.string.search)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear_search))
                                }
                            }
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                    )
                    if (apps == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        val q = query.trim()
                        val filtered = apps.filter { it.label.contains(q, ignoreCase = true) }
                        if (filtered.isEmpty()) {
                            Text(
                                stringResource(R.string.no_match),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                            )
                        }
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(filtered, key = { it.pkg }) { app ->
                                val on = app.pkg in checked
                                val toggle = { checked = if (on) checked - app.pkg else checked + app.pkg }
                                ListItem(
                                    modifier = Modifier.clickable(onClick = toggle),
                                    leadingContent = { AppIcon(app) },
                                    headlineContent = { Text(app.label) },
                                    trailingContent = { Checkbox(checked = on, onCheckedChange = { toggle() }) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val KEY_APPS = "apps"
        private const val KEY_MINUTES = "minutes"
        private const val KEY_A11Y_TRIED = "a11y_tried"
        private const val KEY_DISPLAY = "status_bar"

        // Status bar display choices, in dialog order.
        const val DISPLAY_ICON = 0
        const val DISPLAY_TIME = 1
        const val DISPLAY_BOTH = 2
        private val DISPLAY_LABELS = listOf(R.string.display_icon, R.string.display_time, R.string.display_both)

        private fun prefs(c: Context): SharedPreferences =
            c.getSharedPreferences("settings", Context.MODE_PRIVATE)

        fun selected(c: Context): Set<String> = prefs(c).getStringSet(KEY_APPS, emptySet()) ?: emptySet()

        fun minutes(c: Context): Int = prefs(c).getInt(KEY_MINUTES, 20)

        fun display(c: Context): Int = prefs(c).getInt(KEY_DISPLAY, DISPLAY_BOTH)
    }
}

private fun Drawable.toImageBitmap(px: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val old = copyBounds()
    setBounds(0, 0, px, px)
    draw(Canvas(bitmap))
    bounds = old
    return bitmap.asImageBitmap()
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(LocalContext.current)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(LocalContext.current)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
