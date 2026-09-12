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

/** Small, deterministic animation state machine. 0..0.8 is the opening/closing progress. */
private class ImuxAnimationEngine {
    enum class Phase { IDLE, OPENING, READY, CLOSING }
    var phase by mutableStateOf(Phase.IDLE)
        private set
    val progress = Animatable(0f)

    suspend fun open() {
        phase = Phase.OPENING
        progress.snapTo(0f)
        progress.animateTo(.8f, tween(260, easing = FastOutSlowInEasing))
        phase = Phase.READY
    }

    suspend fun close() {
        phase = Phase.CLOSING
        progress.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
        phase = Phase.IDLE
    }
}

/** Persistent workspace order. New packages are appended; removed packages disappear. */
private class WorkspaceStore(private val prefs: android.content.SharedPreferences) {
    private val key = "workspace_order_v2"
    private val dockKey = "workspace_dock_v2"

    private fun read(key: String) = prefs.getString(key, "")!!.split('|').filter { it.isNotBlank() }
    private fun write(key: String, value: List<String>) = prefs.edit().putString(key, value.joinToString("|")).apply()

    fun load(all: List<AppInfo>): Pair<List<String>, List<String>> {
        val valid = all.map { it.packageName }.toSet()
        var order = read(key).filter(valid::contains).toMutableList()
        all.forEach { if (it.packageName !in order) order += it.packageName }
        var dock = read(dockKey).filter(valid::contains).distinct().take(DOCK_CELLS).toMutableList()
        if (dock.isEmpty()) dock = order.take(DOCK_CELLS).toMutableList()
        order = order.filterNot { it in dock }.toMutableList()
        while (order.size < GRID_CELLS) order += ""
        write(key, order.filter { it.isNotBlank() })
        write(dockKey, dock)
        return order.chunked(GRID_CELLS).flatten() to dock
    }

    fun save(order: List<String>, dock: List<String>) {
        write(key, order.filter { it.isNotBlank() })
        write(dockKey, dock.filter { it.isNotBlank() }.take(DOCK_CELLS))
    }
}

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    private var launching = false
    private var returnedFromApp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        CrashLogger.log(this, "INFO", "MainActivity created - workspace engine")
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val scheme = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) { ImuxHome() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (returnedFromApp) {
            returnedFromApp = false
            // The composable owns the visual closing phase; this only records lifecycle return.
            CrashLogger.log(this, "INFO", "Returned from external application")
        }
        if (!launching && prefs.getBoolean("vivo_compat_mode", false) && prefs.getBoolean("root_granted_session", false)) {
            Thread { VivoLauncherManager.enforceImuxHome() }.start()
        }
        launching = false
    }

    private fun launch(app: AppInfo, onStarted: () -> Unit) {
        launching = true
        returnedFromApp = true
        app.launch()
        onStarted()
    }

    private fun loadApps(): List<AppInfo> = runCatching {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(query, PackageManager.MATCH_DEFAULT_ONLY)
            .distinctBy { it.activityInfo.packageName }
            .map { info ->
                AppInfo(
                    label = info.loadLabel(pm).toString(),
                    packageName = info.activityInfo.packageName,
                    iconLoader = { info.loadIcon(pm) },
                    launch = {
                        pm.getLaunchIntentForPackage(info.activityInfo.packageName)?.let {
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(it)
                        }
                    }
                )
            }.sortedBy { it.label.lowercase() }
    }.getOrElse { emptyList() }

    private fun requestDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 1001); return
            }
        }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    @OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
    @Composable private fun ImuxHome() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
        var workspace by remember { mutableStateOf<List<String>>(emptyList()) }
        var dock by remember { mutableStateOf<List<String>>(emptyList()) }
        var settings by remember { mutableStateOf(false) }
        var logs by remember { mutableStateOf(false) }
        var swipeDrawer by remember { mutableStateOf(prefs.getBoolean("swipe_drawer", false)) }
        val engine = remember { ImuxAnimationEngine() }
        val store = remember { WorkspaceStore(prefs) }

        LaunchedEffect(Unit) {
            apps = withContext(Dispatchers.Default) { loadApps() }
        }
        LaunchedEffect(apps) {
            if (apps.isNotEmpty()) {
                val loaded = store.load(apps)
                workspace = loaded.first
                dock = loaded.second
            }
        }
        val pages = remember(workspace) { if (workspace.isEmpty()) listOf(List(GRID_CELLS) { "" }) else workspace.chunked(GRID_CELLS) }
        val pager = rememberPagerState(pageCount = { pages.size })
        val appMap = remember(apps) { apps.associateBy { it.packageName } }

        BackHandler(enabled = engine.phase != ImuxAnimationEngine.Phase.IDLE || settings || logs) {
            when {
                logs -> logs = false
                settings -> settings = false
                engine.phase != ImuxAnimationEngine.Phase.IDLE -> scope.launch { engine.close() }
            }
        }

        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.background)))) {
            Column(Modifier.fillMaxSize().padding(top = 26.dp, start = 8.dp, end = 8.dp, bottom = 6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Settings") }
                }
                HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                    WorkspaceGrid(
                        items = pages[page],
                        appMap = appMap,
                        onMove = { from, to ->
                            val base = workspace.toMutableList()
                            val a = page * GRID_CELLS + from
                            val b = page * GRID_CELLS + to
                            if (a in base.indices && b in base.indices) {
                                val x = base[a]; base[a] = base[b]; base[b] = x
                                workspace = base
                                store.save(base, dock)
                            }
                        },
                        onLaunch = { app ->
                            scope.launch {
                                engine.open()
                                if (engine.phase == ImuxAnimationEngine.Phase.READY) launch(app) { }
                            }
                        }
                    )
                }
                Dock(dock, appMap) { app -> scope.launch { engine.open(); if (engine.phase == ImuxAnimationEngine.Phase.READY) launch(app) {} } }
                if (pages.size > 1) Text("${pager.currentPage + 1} / ${pages.size}", Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelSmall)
            }

            if (engine.phase != ImuxAnimationEngine.Phase.IDLE) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    val p = engine.progress.value
                    scaleX = 1f - p * .035f; scaleY = 1f - p * .035f
                    alpha = 1f - p * .12f
                })
            }
        }

        if (settings) {
            ModalBottomSheet(onDismissRequest = { settings = false }) {
                Column(Modifier.fillMaxWidth().padding(20.dp).navigationBarsPadding()) {
                    Text("Imux Launcher", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    SettingRow("Swipe up for app drawer", swipeDrawer) { swipeDrawer = it; prefs.edit().putBoolean("swipe_drawer", it).apply() }
                    SettingRow("Vivo launcher compatibility", prefs.getBoolean("vivo_compat_mode", false)) { VivoLauncherManager.setEnabled(context, it) }
                    OutlinedButton(onClick = ::requestDefaultLauncher, Modifier.fillMaxWidth()) { Text("Set Imux as default launcher") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { settings = false; logs = true }, Modifier.fillMaxWidth()) { Text("Diagnostics and logs") }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
        if (logs) LogDialog { logs = false }
    }
}

@Composable private fun WorkspaceGrid(
    items: List<String>, appMap: Map<String, AppInfo>, onMove: (Int, Int) -> Unit, onLaunch: (AppInfo) -> Unit
) {
    var dragging by remember { mutableStateOf(-1) }
    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) }
    Column(Modifier.fillMaxSize()) {
        repeat(7) { row ->
            Row(Modifier.weight(1f).fillMaxWidth()) {
                repeat(4) { col ->
                    val index = row * 4 + col
                    val app = items.getOrNull(index)?.let(appMap::get)
                    Box(Modifier.weight(1f).fillMaxHeight().padding(2.dp).graphicsLayer {
                        if (dragging == index) { translationX = dragX; translationY = dragY; scaleX = 1.08f; scaleY = 1.08f }
                    }.pointerInput(app?.packageName) {
                        detectDragGestures(
                            onDragStart = { if (app != null) { dragging = index; dragX = 0f; dragY = 0f } },
                            onDrag = { change, amount ->
                                change.consume(); dragX += amount.x; dragY += amount.y
                                val dx = (dragX / 90f).toInt(); val dy = (dragY / 76f).toInt()
                                val target = (index + dx + dy * 4).coerceIn(0, 27)
                                if (target != index) { onMove(index, target); dragging = target; dragX = 0f; dragY = 0f }
                            },
                            onDragEnd = { dragging = -1; dragX = 0f; dragY = 0f },
                            onDragCancel = { dragging = -1 }
                        )
                    }.pointerInput(app?.packageName) {
                        detectTapGestures(onTap = { app?.let(onLaunch) })
                    }) {
                        if (app != null) AppIcon(app)
                    }
                }
            }
        }
    }
}

@Composable private fun Dock(dock: List<String>, appMap: Map<String, AppInfo>, onLaunch: (AppInfo) -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            repeat(4) { i -> dock.getOrNull(i)?.let { appMap[it]?.let { AppIcon(it, Modifier.size(52.dp), onLaunch) } } }
        }
    }
}

@Composable private fun AppIcon(app: AppInfo, modifier: Modifier = Modifier.size(48.dp), onTap: ((AppInfo) -> Unit)? = null) {
    Canvas(modifier.pointerInput(app.packageName) { if (onTap != null) detectTapGestures(onTap = { onTap(app) }) }) {
        val d: Drawable = app.icon
        val w = d.intrinsicWidth.coerceAtLeast(1); val h = d.intrinsicHeight.coerceAtLeast(1)
        val s = minOf(size.width / w, size.height / h); val dw = w * s; val dh = h * s
        val l = ((size.width - dw) / 2).toInt(); val t = ((size.height - dh) / 2).toInt()
        d.setBounds(l, t, (l + dw).toInt(), (t + dh).toInt())
        drawIntoCanvas { c -> d.draw(c.nativeCanvas) }
    }
}

@Composable private fun SettingRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f)); Switch(checked, onCheckedChange) }
}

@Composable private fun LogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(CrashLogger.read(context)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Diagnostics") }, text = { Text(text, Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), style = MaterialTheme.typography.bodySmall) }, confirmButton = { TextButton({ text = CrashLogger.read(context) }) { Text("Refresh") }; TextButton({ onDismiss() }) { Text("Close") } })
}
