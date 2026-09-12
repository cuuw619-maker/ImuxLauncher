package com.imux.launcher

import android.app.ActivityOptions
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
        CrashLogger.log(this, "INFO", "MainActivity created; OriginWEB-inspired workspace")
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val scheme = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                ImuxHome(prefs, ::loadApps, ::requestDefaultLauncher, ::requestRoot)
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
                AppInfo(info.loadLabel(pm).toString(), info.activityInfo.packageName, info.loadIcon(pm)) {
                    CrashLogger.log(this, "INFO", "Launching ${info.activityInfo.packageName}")
                    runCatching { startActivity(launchIntent) }
                        .onFailure { CrashLogger.log(this, "ERROR", "Launch failed: ${it.stackTraceToString()}") }
                }
            }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }.onFailure { CrashLogger.log(this, "ERROR", "App scan failed: ${it.stackTraceToString()}") }
        .getOrDefault(emptyList())

    private fun requestDefaultLauncher() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val role = getSystemService<RoleManager>()
                if (role?.isRoleAvailable(RoleManager.ROLE_HOME) == true) {
                    startActivity(role.createRequestRoleIntent(RoleManager.ROLE_HOME))
                } else startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } else startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }.onFailure { CrashLogger.log(this, "ERROR", "HOME role request failed: ${it.stackTraceToString()}") }
    }

    private fun requestRoot(onResult: (Result<String>) -> Unit) {
        Thread {
            val result = RootManager.requestRoot()
            CrashLogger.log(this, if (result.isSuccess) "INFO" else "WARN", "Root: ${result.fold({ "granted via $it" }, { it.message ?: "denied" })}")
            runOnUiThread { onResult(result) }
        }.start()
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ImuxHome(
    prefs: android.content.SharedPreferences,
    loadApps: () -> List<AppInfo>,
    requestHome: () -> Unit,
    requestRoot: ((Result<String>) -> Unit) -> Unit
) {
    var apps by remember { mutableStateOf(emptyList<AppInfo>()) }
    var drawer by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf(false) }
    var drawerEnabled by remember { mutableStateOf(prefs.getBoolean("swipe_drawer", false)) }
    var protect by remember { mutableStateOf(prefs.getBoolean("protect_desktop", true)) }
    var rootBusy by remember { mutableStateOf(false) }
    var rootMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { apps = withContext(Dispatchers.Default) { loadApps() } }
    val pages = apps.chunked(28).ifEmpty { listOf(emptyList()) }
    val pager = rememberPagerState(pageCount = { pages.size })
    val filtered = if (search.isBlank()) apps else apps.filter { it.label.contains(search, true) || it.packageName.contains(search, true) }
    val workspaceScale by animateFloatAsState(if (drawer || settings) .96f else 1f, spring(dampingRatio = .86f, stiffness = 420f), label = "workspaceScale")
    val wallpaperBlur by animateFloatAsState(if (drawer || settings) 10f else 0f, label = "wallpaperBlur")
    val wallpaperDim by animateFloatAsState(if (drawer || settings) .34f else 0f, label = "wallpaperDim")

    BackHandler(enabled = drawer) { drawer = false; search = "" }
    BackHandler(enabled = !drawer && settings) { settings = false }
    BackHandler(enabled = !drawer && !settings && protect) { CrashLogger.log(LocalContext.current, "INFO", "Back pressed; protected desktop kept visible") }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(drawerEnabled, drawer) {
            if (drawerEnabled && !drawer) detectVerticalDragGestures { _, dy -> if (dy < -42f) drawer = true }
        }
    ) {
        Box(
            Modifier.fillMaxSize().blur(wallpaperBlur.dp).background(
                Brush.linearGradient(listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = .34f),
                    MaterialTheme.colorScheme.tertiary.copy(alpha = .18f),
                    MaterialTheme.colorScheme.background,
                    MaterialTheme.colorScheme.secondary.copy(alpha = .22f)
                ))
            )
        )
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = wallpaperDim)))

        Column(Modifier.fillMaxSize().scale(workspaceScale).graphicsLayer { transformOrigin = TransformOrigin.Center }) {
            Surface(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .62f),
                tonalElevation = 0.dp
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("IMUX", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("${pager.currentPage + 1} / ${pages.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton({ settings = true }) { Icon(Icons.Default.Settings, "Settings") }
                }
            }

            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp), pageSpacing = 10.dp) { page ->
                val pageApps = pages[page]
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4), modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 5.dp, end = 5.dp, top = 14.dp, bottom = 92.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(pageApps, key = { it.packageName }) { AppCell(it) }
                    items(28 - pageApps.size) { Spacer(Modifier.aspectRatio(.78f)) }
                }
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            shape = RoundedCornerShape(30.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .76f), shadowElevation = 8.dp
        ) {
            IconButton({ drawer = true }, modifier = Modifier.padding(2.dp)) { Icon(Icons.Default.Apps, "All apps") }
        }

        AnimatedVisibility(
            visible = drawer,
            enter = fadeIn(tween(220, easing = FastOutSlowInEasing)) + scaleIn(initialScale = .94f, animationSpec = spring(dampingRatio = .82f, stiffness = 380f)),
            exit = fadeOut(tween(180, easing = FastOutSlowInEasing)) + scaleOut(targetScale = .94f, animationSpec = spring(dampingRatio = .9f, stiffness = 420f)),
            modifier = Modifier.fillMaxSize()
        ) {
            Surface(color = MaterialTheme.colorScheme.background.copy(alpha = .94f), tonalElevation = 8.dp) {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("All apps", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("${filtered.size} applications", style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton({ drawer = false; search = "" }) { Icon(Icons.Default.Close, "Close") }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(22.dp), label = { Text("Search applications") })
                    Spacer(Modifier.height(12.dp))
                    LazyVerticalGrid(columns = GridCells.Fixed(4), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(filtered, key = { it.packageName }) { AppCell(it) { drawer = false } }
                    }
                }
            }
        }

        if (settings) {
            ModalBottomSheet(onDismissRequest = { settings = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Imux", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("OriginWEB-inspired visual workspace", style = MaterialTheme.typography.bodyMedium)
                    SettingRow("Swipe-up app drawer", drawerEnabled) { drawerEnabled = it; prefs.edit().putBoolean("swipe_drawer", it).apply() }
                    SettingRow("Protect desktop from Back", protect) { protect = it; prefs.edit().putBoolean("protect_desktop", it).apply() }
                    Button(requestHome, Modifier.fillMaxWidth()) { Text("Set Imux as default launcher") }
                    OutlinedButton(enabled = !rootBusy, onClick = {
                        rootBusy = true
                        rootMessage = "Requesting su permission…"
                        requestRoot { result ->
                            rootBusy = false
                            rootMessage = result.fold({ "Root granted via $it" }, { "Root request failed: ${it.message ?: "permission denied"}" })
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Shield, null); Spacer(Modifier.width(8.dp)); Text(if (rootBusy) "Waiting for su…" else "Request root via su")
                    }
                    rootMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Root granted")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                    OutlinedButton({ settings = false; logs = true }, Modifier.fillMaxWidth()) { Text("Diagnostics and logs") }
                    Text("4 × 7 workspace. Icons have no visual outline. Opening, closing and drawer transitions use spring motion.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (logs) LogDialog { logs = false }
    }
}

@Composable
private fun AppCell(app: AppInfo, onLaunch: () -> Unit = {}) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) .88f else 1f, spring(dampingRatio = .68f, stiffness = 700f), label = "iconPress")
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(.78f).graphicsLayer { scaleX = scale; scaleY = scale }.pointerInput(Unit) {
            detectTapGestures(
                onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                onTap = { onLaunch(); app.launch() }
            )
        },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context -> android.widget.ImageView(context).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; contentDescription = app.label } },
                update = { it.setImageDrawable(app.icon) }, modifier = Modifier.size(52.dp)
            )
            Spacer(Modifier.height(5.dp))
            Text(app.label, maxLines = 1, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

@Composable
private fun SettingRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
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
        onDismissRequest = onDismiss,
        title = { Text("Imux diagnostics") },
        text = { Text(log, style = MaterialTheme.typography.bodySmall) },
        confirmButton = {
            TextButton({ context.getSystemService<android.content.ClipboardManager>()?.setPrimaryClip(android.content.ClipData.newPlainText("Imux log", log)) }) { Text("Copy log") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Close") } }
    )
}
