package com.imux.launcher

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLogger.log(this, "INFO", "MainActivity created; desktop mode")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                CrashLogger.log(this@MainActivity, "INFO", "Back pressed; launcher remains active")
            }
        })
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val scheme = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                ImuxHome(prefs = prefs, loadApps = ::loadApps, requestHome = ::requestDefaultLauncher, requestRoot = ::requestRoot)
            }
        }
    }

    private fun loadApps(): List<AppInfo> = runCatching {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(query, PackageManager.MATCH_ALL)
            .distinctBy { it.activityInfo.packageName }
            .mapNotNull { info ->
                val launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName) ?: return@mapNotNull null
                AppInfo(
                    info.loadLabel(pm).toString(), info.activityInfo.packageName, info.loadIcon(pm),
                    launch = {
                        CrashLogger.log(this, "INFO", "Launching ${info.activityInfo.packageName}")
                        runCatching { startActivity(launchIntent) }.onFailure {
                            CrashLogger.log(this, "ERROR", "Launch failed: ${it.stackTraceToString()}")
                        }
                    }
                )
            }.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }.onFailure { CrashLogger.log(this, "ERROR", "App scan failed: ${it.stackTraceToString()}") }
        .getOrDefault(emptyList())

    private fun requestDefaultLauncher() = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val role = getSystemService<RoleManager>()
            if (role?.isRoleAvailable(RoleManager.ROLE_HOME) == true) {
                startActivity(role.createRequestRoleIntent(RoleManager.ROLE_HOME)); return
            }
        }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }.onFailure { CrashLogger.log(this, "ERROR", "HOME role request failed: ${it.stackTraceToString()}") }

    private fun requestRoot() {
        Thread {
            val result = RootManager.requestRoot()
            CrashLogger.log(this, if (result.isSuccess) "INFO" else "WARN", "Root: ${result.fold({ "granted via $it" }, { it.message ?: "denied" })}")
        }.start()
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ImuxHome(
    prefs: android.content.SharedPreferences,
    loadApps: () -> List<AppInfo>,
    requestHome: () -> Unit,
    requestRoot: () -> Unit
) {
    var apps by remember { mutableStateOf(emptyList<AppInfo>()) }
    var drawer by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf(false) }
    var drawerEnabled by remember { mutableStateOf(prefs.getBoolean("swipe_drawer", false)) }
    var protect by remember { mutableStateOf(prefs.getBoolean("protect_desktop", true)) }

    LaunchedEffect(Unit) { apps = withContext(Dispatchers.Default) { loadApps() } }
    val pages = apps.chunked(28).ifEmpty { listOf(emptyList()) }
    val pager = rememberPagerState(pageCount = { pages.size })
    val filtered = if (search.isBlank()) apps else apps.filter { it.label.contains(search, true) || it.packageName.contains(search, true) }

    Box(
        Modifier.fillMaxSize().pointerInput(drawerEnabled, drawer) {
            if (drawerEnabled && !drawer) detectVerticalDragGestures { _, dy -> if (dy < -36f) drawer = true }
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Surface(tonalElevation = 2.dp) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("IMUX", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("${pager.currentPage + 1} / ${pages.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton({ settings = true }) { Icon(Icons.Default.Settings, "Settings") }
                    }
                }
            },
            floatingActionButton = { FloatingActionButton({ drawer = true }) { Icon(Icons.Default.Apps, "All apps") } }
        ) { pad ->
            HorizontalPager(
                state = pager, modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(horizontal = 10.dp), pageSpacing = 8.dp
            ) { page ->
                val pageApps = pages[page]
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4), modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp, 10.dp, 4.dp, 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(pageApps, key = { it.packageName }) { AppCell(it) }
                    items(28 - pageApps.size) { Spacer(Modifier.aspectRatio(.78f)) }
                }
            }
        }

        AnimatedVisibility(drawer, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 8.dp) {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("All apps", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("${filtered.size} applications", style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton({ drawer = false; search = "" }) { Icon(Icons.Default.Close, "Close") }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search applications") })
                    Spacer(Modifier.height(10.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4), contentPadding = PaddingValues(bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) { items(filtered, key = { it.packageName }) { AppCell(it) { drawer = false } } }
                }
            }
        }

        if (settings) {
            ModalBottomSheet({ settings = false }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Imux settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    SettingRow("Swipe-up app drawer", drawerEnabled) { drawerEnabled = it; prefs.edit().putBoolean("swipe_drawer", it).apply() }
                    SettingRow("Protect desktop from Back", protect) { protect = it; prefs.edit().putBoolean("protect_desktop", it).apply() }
                    Button(requestHome, Modifier.fillMaxWidth()) { Text("Set Imux as default launcher") }
                    OutlinedButton(requestRoot, Modifier.fillMaxWidth()) { Icon(Icons.Default.Shield, null); Spacer(Modifier.width(8.dp)); Text("Request root via su") }
                    OutlinedButton({ settings = false; logs = true }, Modifier.fillMaxWidth()) { Text("Diagnostics and logs") }
                    Text("Material You / Material 3 dynamic color is used where Android supports it. The workspace remains 4 × 7 cells per page.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (logs) LogDialog { logs = false }
    }
}

@Composable
private fun AppCell(app: AppInfo, onLaunch: () -> Unit = {}) {
    Card(
        onClick = { onLaunch(); app.launch() }, modifier = Modifier.fillMaxWidth().aspectRatio(.78f),
        shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AndroidView({ context -> android.widget.ImageView(context).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER } }, update = { it.setImageDrawable(app.icon) }, modifier = Modifier.size(50.dp))
            Spacer(Modifier.height(5.dp)); Text(app.label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SettingRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f)); Switch(checked, onChange)
        }
    }
}

@Composable
private fun LogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val log = remember { CrashLogger.read(context) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Imux diagnostics") },
        text = { Text(log, style = MaterialTheme.typography.bodySmall) },
        confirmButton = {
            TextButton({
                context.getSystemService<android.content.ClipboardManager>()?.setPrimaryClip(
                    android.content.ClipData.newPlainText("Imux log", log)
                )
            }) { Text("Copy log") }
        }, dismissButton = { TextButton(onDismiss) { Text("Close") } }
    )
}
