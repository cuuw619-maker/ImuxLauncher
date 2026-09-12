package com.imux.launcher

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val GRID_CELLS = 28
private const val GRID_COLUMNS = 4
private const val DOCK_CELLS = 4

private class ImuxAnimationEngine {
    enum class Phase { IDLE, OPENING, READY, CLOSING }
    var phase by mutableStateOf(Phase.IDLE); private set
    val progress = Animatable(0f)
    suspend fun open(): Boolean {
        if (phase != Phase.IDLE) return false
        phase = Phase.OPENING; progress.snapTo(0f)
        progress.animateTo(.8f, tween(260, easing = FastOutSlowInEasing))
        phase = Phase.READY; return true
    }
    suspend fun close() {
        if (phase == Phase.IDLE) return
        phase = Phase.CLOSING
        progress.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
        phase = Phase.IDLE
    }
}

private class WorkspaceStore(private val prefs: android.content.SharedPreferences) {
    private val key = "workspace_order_v2"; private val dockKey = "workspace_dock_v2"
    private fun read(k: String) = prefs.getString(k, "")!!.split('|').filter { it.isNotBlank() }
    private fun write(k: String, value: List<String>) = prefs.edit().putString(k, value.joinToString("|")).apply()
    fun load(all: List<AppInfo>): Pair<List<String>, List<String>> {
        val valid = all.map { it.packageName }.toSet()
        val oldDock = read(dockKey).filter(valid::contains).distinct()
        val dock = (if (oldDock.isNotEmpty()) oldDock else all.take(DOCK_CELLS).map { it.packageName }).take(DOCK_CELLS)
        val order = read(key).filter(valid::contains).filterNot { it in dock }.toMutableList()
        all.forEach { if (it.packageName !in order && it.packageName !in dock) order += it.packageName }
        write(key, order); write(dockKey, dock); return order to dock
    }
    fun save(order: List<String>, dock: List<String>) { write(key, order.filter { it.isNotBlank() }); write(dockKey, dock.filter { it.isNotBlank() }.take(DOCK_CELLS)) }
}

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    private var launching = false
    private var resumeGeneration by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); WindowCompat.setDecorFitsSystemWindows(window, false)
        CrashLogger.log(this, "INFO", "MainActivity created - custom launcher engine")
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val scheme = if (Build.VERSION.SDK_INT >= 31) { if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this) } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) { ImuxHome(resumeGeneration) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (launching) { launching = false; resumeGeneration++ }
        else if (prefs.getBoolean("vivo_compat_mode", false) && prefs.getBoolean("root_granted_session", false)) Thread { VivoLauncherManager.enforceImuxHome() }.start()
    }

    private fun launch(app: AppInfo) { launching = true; app.launch() }
    private fun loadApps(): List<AppInfo> = runCatching {
        val pm = packageManager; val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(query, PackageManager.MATCH_DEFAULT_ONLY).distinctBy { it.activityInfo.packageName }.map { info ->
            AppInfo(info.loadLabel(pm).toString(), info.activityInfo.packageName, { info.loadIcon(pm) }) { pm.getLaunchIntentForPackage(info.activityInfo.packageName)?.let { startActivity(it) } }
        }.sortedBy { it.label.lowercase() }
    }.getOrElse { emptyList() }

    private fun requestDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { val rm = getSystemService(RoleManager::class.java); if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) { startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 1001); return } }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    @OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
    @Composable private fun ImuxHome(resumeGeneration: Int) {
        val context = LocalContext.current; val scope = rememberCoroutineScope(); val engine = remember { ImuxAnimationEngine() }; val store = remember { WorkspaceStore(prefs) }
        var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }; var workspace by remember { mutableStateOf<List<String>>(emptyList()) }; var dock by remember { mutableStateOf<List<String>>(emptyList()) }
        var settings by remember { mutableStateOf(false) }; var logs by remember { mutableStateOf(false) }; var swipeDrawer by remember { mutableStateOf(prefs.getBoolean("swipe_drawer", false)) }
        LaunchedEffect(Unit) { apps = withContext(Dispatchers.Default) { loadApps() } }
        LaunchedEffect(apps) { if (apps.isNotEmpty()) { val p = store.load(apps); workspace = p.first; dock = p.second } }
        LaunchedEffect(resumeGeneration) { if (resumeGeneration > 0 && engine.phase == ImuxAnimationEngine.Phase.READY) engine.close() }
        val pages = remember(workspace) { workspace.chunked(GRID_CELLS).ifEmpty { listOf(List(GRID_CELLS) { "" }) } }; val pager = rememberPagerState(pageCount = { pages.size }); val appMap = remember(apps) { apps.associateBy { it.packageName } }
        BackHandler(enabled = settings || logs || engine.phase != ImuxAnimationEngine.Phase.IDLE) { when { logs -> logs = false; settings -> settings = false; else -> scope.launch { engine.close() } } }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.background)))) {
            Column(Modifier.fillMaxSize().padding(top = 26.dp, start = 8.dp, end = 8.dp, bottom = 6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { IconButton({ settings = true }) { Icon(Icons.Default.Settings, "Settings") } }
                HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page -> WorkspaceGrid(pages[page], appMap, { from, to ->
                    val base = workspace.toMutableList(); val a = page * GRID_CELLS + from; val b = page * GRID_CELLS + to
                    if (a in base.indices && b in base.indices && a != b) { val x = base[a]; base[a] = base[b]; base[b] = x; workspace = base; store.save(base, dock) }
                }) { app -> scope.launch { if (engine.open()) launch(app) } } }
                Dock(dock, appMap) { app -> scope.launch { if (engine.open()) launch(app) } }
                if (pages.size > 1) Text("${pager.currentPage + 1} / ${pages.size}", Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelSmall)
            }
            if (engine.phase != ImuxAnimationEngine.Phase.IDLE) LinearProgressIndicator(progress = { engine.progress.value / .8f }, Modifier.fillMaxWidth().align(Alignment.BottomCenter))
        }
        if (settings) ModalBottomSheet(onDismissRequest = { settings = false }) { Column(Modifier.fillMaxWidth().padding(20.dp).navigationBarsPadding()) {
            Text("Imux Launcher", style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.height(12.dp))
            SettingRow("Swipe up for app drawer", swipeDrawer) { swipeDrawer = it; prefs.edit().putBoolean("swipe_drawer", it).apply() }
            SettingRow("Vivo launcher compatibility", prefs.getBoolean("vivo_compat_mode", false)) { VivoLauncherManager.setEnabled(context, it) }
            OutlinedButton(::requestDefaultLauncher, Modifier.fillMaxWidth()) { Text("Set Imux as default launcher") }; Spacer(Modifier.height(8.dp))
            OutlinedButton({ settings = false; logs = true }, Modifier.fillMaxWidth()) { Text("Diagnostics and logs") }; Spacer(Modifier.height(20.dp))
        } }
        if (logs) LogDialog { logs = false }
    }
}

@Composable private fun WorkspaceGrid(items: List<String>, appMap: Map<String, AppInfo>, onMove: (Int, Int) -> Unit, onLaunch: (AppInfo) -> Unit) {
    var dragging by remember { mutableStateOf(-1) }; var dx by remember { mutableStateOf(0f) }; var dy by remember { mutableStateOf(0f)
    }
    Column(Modifier.fillMaxSize()) { repeat(7) { row -> Row(Modifier.weight(1f).fillMaxWidth()) { repeat(GRID_COLUMNS) { col ->
        val index = row * GRID_COLUMNS + col; val app = items.getOrNull(index)?.let(appMap::get)
        Box(Modifier.weight(1f).fillMaxHeight().padding(2.dp).graphicsLayer { if (dragging == index) { translationX = dx; translationY = dy; scaleX = 1.08f; scaleY = 1.08f } }
            .pointerInput(app?.packageName) { detectDragGestures(onDragStart = { if (app != null) { dragging = index; dx = 0f; dy = 0f } }, onDrag = { c, amount -> c.consume(); dx += amount.x; dy += amount.y; val tx = (index + (dx / 88f).toInt() + (dy / 72f).toInt()).coerceIn(0, GRID_CELLS - 1); if (tx != index) { onMove(index, tx); dragging = tx; dx = 0f; dy = 0f } }, onDragEnd = { dragging = -1 }, onDragCancel = { dragging = -1 }) }
            .pointerInput(app?.packageName) { detectTapGestures(onTap = { app?.let(onLaunch) }) }) { if (app != null) AppIcon(app) }
    } } } }
}

@Composable private fun Dock(dock: List<String>, appMap: Map<String, AppInfo>, onLaunch: (AppInfo) -> Unit) { Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 8.dp, vertical = 4.dp)) { Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { repeat(DOCK_CELLS) { i -> dock.getOrNull(i)?.let { appMap[it]?.let { AppIcon(it, Modifier.size(52.dp), onLaunch) } } } } } }

@Composable private fun AppIcon(app: AppInfo, modifier: Modifier = Modifier.size(48.dp), onTap: ((AppInfo) -> Unit)? = null) { Canvas(modifier.pointerInput(app.packageName) { if (onTap != null) detectTapGestures(onTap = { onTap(app) }) }) { val d: Drawable = app.icon; val w = d.intrinsicWidth.coerceAtLeast(1); val h = d.intrinsicHeight.coerceAtLeast(1); val s = minOf(size.width / w, size.height / h); val dw = w * s; val dh = h * s; val l = ((size.width - dw) / 2).toInt(); val t = ((size.height - dh) / 2).toInt(); d.setBounds(l, t, (l + dw).toInt(), (t + dh).toInt()); drawIntoCanvas { c -> d.draw(c.nativeCanvas) } } }

@Composable private fun SettingRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f)); Switch(checked, onCheckedChange) } }
@Composable private fun LogDialog(onDismiss: () -> Unit) { val context = LocalContext.current; var text by remember { mutableStateOf(CrashLogger.read(context)) }; AlertDialog(onDismissRequest = onDismiss, title = { Text("Diagnostics") }, text = { Text(text, Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), style = MaterialTheme.typography.bodySmall) }, confirmButton = { TextButton({ text = CrashLogger.read(context) }) { Text("Refresh") }; TextButton(onDismiss) { Text("Close") } }) }
