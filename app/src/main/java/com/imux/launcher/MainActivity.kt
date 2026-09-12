package com.imux.launcher

import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        CrashLogger.log(this, "INFO", "MainActivity created")
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val scheme = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) androidx.compose.material3.dynamicDarkColorScheme(this)
                else androidx.compose.material3.dynamicLightColorScheme(this)
            } else if (dark) {
                androidx.compose.material3.darkColorScheme()
            } else {
                androidx.compose.material3.lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                ImuxHome(prefs, ::loadApps, ::requestDefaultLauncher, ::requestRoot)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.getBoolean("vivo_compat_mode", false) &&
            prefs.getBoolean("root_granted_session", false)
        ) {
            Thread {
                VivoLauncherManager.suppressStockLauncher().onSuccess {
                    CrashLogger.log(this, "INFO", "Vivo compatibility: stock launcher force-stopped")
                }.onFailure {
                    CrashLogger.log(this, "WARN", "Vivo compatibility failed: ${it.message}")
                }
            }.start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            CrashLogger.log(this, "INFO", "HOME intent received by Imux")
        }
    }

    private fun loadApps(): List<AppInfo> = runCatching {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(query, PackageManager.MATCH_ALL)
            .distinctBy { it.activityInfo.packageName }
            .map { info ->
                AppInfo(
                    label = info.loadLabel(pm).toString(),
                    packageName = info.activityInfo.packageName,
                    iconLoader = { info.loadIcon(pm) },
                    launch = {
                        val launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent)
                        }
                    }
                )
            }
            .sortedBy { it.label.lowercase() }
    }.getOrElse {
        CrashLogger.log(this, "ERROR", "Failed to load apps: ${it.stackTraceToString()}")
        emptyList()
    }

    private fun requestDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                startActivityForResult(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME),
                    1001
                )
                return
            }
        }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun requestRoot(callback: (Result<String>) -> Unit) {
        Thread {
            val result = RootManager.requestRoot()
            if (result.isSuccess) {
                prefs.edit().putBoolean("root_granted_session", true).apply()
            }
            runOnUiThread { callback(result) }
        }.start()
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ImuxHome(
    prefs: android.content.SharedPreferences,
    loadApps: () -> List<AppInfo>,
    requestDefaultLauncher: () -> Unit,
    requestRoot: ((Result<String>) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var drawerOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var logsOpen by remember { mutableStateOf(false) }
    var swipeDrawer by remember { mutableStateOf(prefs.getBoolean("swipe_drawer", false)) }
    var backProtection by remember { mutableStateOf(prefs.getBoolean("back_protection", true)) }
    var vivoCompat by remember { mutableStateOf(prefs.getBoolean("vivo_compat_mode", false)) }
    var rootBusy by remember { mutableStateOf(false) }
    var rootMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.Default) { loadApps() }
    }

    val pages = apps.chunked(28).ifEmpty { listOf(emptyList()) }
    val pager = rememberPagerState(pageCount = { pages.size })

    BackHandler(enabled = backProtection && (drawerOpen || settingsOpen || logsOpen)) {
        when {
            logsOpen -> logsOpen = false
            settingsOpen -> settingsOpen = false
            drawerOpen -> drawerOpen = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .pointerInput(swipeDrawer) {
                if (swipeDrawer) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dragAmount ->
                            if (dragAmount < -18f) drawerOpen = true
                        }
                    )
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val target = if (drawerOpen || settingsOpen) .96f else 1f
                    scaleX = target
                    scaleY = target
                }
                .padding(horizontal = 10.dp, vertical = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = { settingsOpen = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }

            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { page ->
                val pageApps = pages[page]
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(pageApps, key = { it.packageName }) { app ->
                        AppCell(app) {
                            scope.launch {
                                delay(65)
                                app.launch()
                            }
                        }
                    }
                }
            }

            if (pages.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        "${pager.currentPage + 1} / ${pages.size}",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = drawerOpen,
            enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(180)) +
                scaleIn(
                    initialScale = .94f,
                    animationSpec = androidx.compose.animation.core.tween(
                        220,
                        easing = FastOutSlowInEasing
                    )
                ),
            exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(140)) +
                scaleOut(
                    targetScale = .96f,
                    animationSpec = androidx.compose.animation.core.tween(160)
                )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().padding(18.dp).navigationBarsPadding(),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 8.dp
            ) {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Apps", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { drawerOpen = false }) { Text("Close") }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(apps, key = { "drawer-${it.packageName}" }) { app ->
                            AppCell(app) {
                                drawerOpen = false
                                scope.launch {
                                    delay(65)
                                    app.launch()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        ModalBottomSheet(onDismissRequest = { settingsOpen = false }) {
            Column(Modifier.fillMaxWidth().padding(20.dp).navigationBarsPadding()) {
                Text("Imux Launcher", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                SettingRow("Swipe up for app drawer", swipeDrawer) {
                    swipeDrawer = it
                    prefs.edit().putBoolean("swipe_drawer", it).apply()
                }
                SettingRow("Back protection", backProtection) {
                    backProtection = it
                    prefs.edit().putBoolean("back_protection", it).apply()
                }
                SettingRow("Vivo launcher compatibility", vivoCompat) {
                    vivoCompat = it
                    VivoLauncherManager.setEnabled(context, it)
                    if (it && prefs.getBoolean("root_granted_session", false)) {
                        Thread { VivoLauncherManager.suppressStockLauncher() }.start()
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = requestDefaultLauncher,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Set Imux as default launcher")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    enabled = !rootBusy,
                    onClick = {
                        rootBusy = true
                        rootMessage = "Requesting su permission…"
                        requestRoot { result ->
                            rootBusy = false
                            rootMessage = result.fold(
                                { "Root granted via $it" },
                                {
                                    "Root request failed: ${it.message ?: "permission denied"}"
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (rootBusy) "Requesting root…" else "Request root via su")
                }
                if (rootMessage.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(rootMessage, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { VivoLauncherManager.emergencyRestore(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Emergency restore Vivo launcher")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        settingsOpen = false
                        logsOpen = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Diagnostics and logs")
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (logsOpen) LogDialog(onDismiss = { logsOpen = false })
}

@Composable
private fun SettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AppCell(app: AppInfo, onLaunch: () -> Unit = {}) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) .86f else 1f,
        animationSpec = spring(dampingRatio = .65f, stiffness = 500f),
        label = "iconPress"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(.78f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onLaunch() }
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            },
            update = { it.setImageDrawable(app.icon) },
            modifier = Modifier.size(52.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = app.label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

@Composable
private fun LogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    fun refresh() {
        val privateLog = CrashLogger.read(context)
        val processLog = CrashLogger.readProcessLogcat()
        text = if (processLog.isBlank()) {
            privateLog
        } else {
            "$privateLog\n\n--- IMUX PROCESS LOGCAT ---\n$processLog"
        }
    }

    LaunchedEffect(Unit) { refresh() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnostics") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { refresh() }) { Text("Refresh") }
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Imux logs", text))
                    Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy")
                }
                TextButton(onClick = {
                    CrashLogger.clear(context)
                    refresh()
                }) {
                    Text("Clear")
                }
            }
        }
    )
}
