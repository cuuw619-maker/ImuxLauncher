package com.imux.launcher

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import kotlin.math.max

private const val GRID_CELLS = 28
private const val GRID_COLUMNS = 4
private const val DOCK_CELLS = 4

/**
 * Owns the launcher transition independently from the workspace UI.
 * 0..0.8 is the visible launch transition; reaching 0.8 is the launch gate.
 */
private class ImuxAnimationEngine {
    enum class Phase { IDLE, OPENING, READY, CLOSING }

    var phase by mutableStateOf(Phase.IDLE)
        private set
    val progress = Animatable(0f)

    var originX by mutableFloatStateOf(.5f)
        private set
    var originY by mutableFloatStateOf(.5f)
        private set

    fun setOrigin(x: Float, y: Float) {
        originX = x.coerceIn(0f, 1f)
        originY = y.coerceIn(0f, 1f)
    }

    suspend fun open(): Boolean {
        if (phase != Phase.IDLE) return false
        phase = Phase.OPENING
        progress.snapTo(0f)
        progress.animateTo(.8f, tween(420, easing = FastOutSlowInEasing))
        phase = Phase.READY
        return true
    }

    suspend fun close() {
        if (phase == Phase.IDLE) return
        phase = Phase.CLOSING
        progress.animateTo(0f, tween(360, easing = LinearOutSlowInEasing))
        phase = Phase.IDLE
    }
}

private class WorkspaceStore(private val prefs: android.content.SharedPreferences) {
    private val key = "workspace_order_v2"
    private val dockKey = "workspace_dock_v2"

    private fun read(key: String): List<String> =
        prefs.getString(key, "").orEmpty().split('|').filter { it.isNotBlank() }

    private fun write(key: String, value: List<String>) {
        prefs.edit().putString(key, value.joinToString("|")).apply()
    }

    fun load(all: List<AppInfo>): Pair<List<String>, List<String>> {
        val valid = all.map { it.packageName }.toSet()
        val oldDock = read(dockKey).filter(valid::contains).distinct()
        val dock = (if (oldDock.isNotEmpty()) oldDock else all.take(DOCK_CELLS).map { it.packageName })
            .take(DOCK_CELLS)
        val order = read(key).filter(valid::contains).filterNot { it in dock }.toMutableList()
        all.forEach { app ->
            if (app.packageName !in order && app.packageName !in dock) order += app.packageName
        }
        write(key, order)
        write(dockKey, dock)
        return order to dock
    }

    fun save(order: List<String>, dock: List<String>) {
        write(key, order.filter { it.isNotBlank() })
        write(dockKey, dock.filter { it.isNotBlank() }.take(DOCK_CELLS))
    }
}

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    private var launching = false
    private var resumeGeneration by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        CrashLogger.log(this, "INFO", "MainActivity created - custom launcher engine")

        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val scheme = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (dark) {
                darkColorScheme()
            } else {
                lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                ImuxHome(resumeGeneration)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (launching) {
            launching = false
            resumeGeneration++
        } else if (prefs.getBoolean("vivo_compat_mode", false) &&
            prefs.getBoolean("root_granted_session", false)
        ) {
            Thread { VivoLauncherManager.enforceImuxHome() }.start()
        }
    }

    private fun launch(app: AppInfo) {
        launching = true
        app.launch()
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
                        pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                            ?.let(::startActivity)
                    }
                )
            }
            .sortedBy { it.label.lowercase() }
    }.getOrElse { emptyList() }

    private fun requestDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 1001)
                return
            }
        }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    @OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
    @Composable
    private fun ImuxHome(resumeGeneration: Int) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val engine = remember { ImuxAnimationEngine() }
        val store = remember { WorkspaceStore(prefs) }

        var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
        var workspace by remember { mutableStateOf<List<String>>(emptyList()) }
        var dock by remember { mutableStateOf<List<String>>(emptyList()) }
        var settings by remember { mutableStateOf(false) }
        var logs by remember { mutableStateOf(false) }
        var swipeDrawer by remember { mutableStateOf(prefs.getBoolean("swipe_drawer", false)) }
        var activeApp by remember { mutableStateOf<AppInfo?>(null) }

        LaunchedEffect(Unit) {
            apps = withContext(Dispatchers.Default) { loadApps() }
        }

        LaunchedEffect(apps) {
            if (apps.isNotEmpty()) {
                val saved = store.load(apps)
                workspace = saved.first
                dock = saved.second
            }
        }

        LaunchedEffect(resumeGeneration) {
            if (resumeGeneration > 0 && engine.phase == ImuxAnimationEngine.Phase.READY) {
                engine.close()
                activeApp = null
            }
        }

        val pages = remember(workspace) {
            workspace.chunked(GRID_CELLS).ifEmpty {
                listOf(List(GRID_CELLS) { "" })
            }
        }
        val pager = rememberPagerState(pageCount = { pages.size })
        val appMap = remember(apps) { apps.associateBy { it.packageName } }

        BackHandler(enabled = settings || logs || engine.phase != ImuxAnimationEngine.Phase.IDLE) {
            when {
                logs -> logs = false
                settings -> settings = false
                else -> scope.launch {
                    engine.close()
                    activeApp = null
                }
            }
        }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        ) {
            val overlayProgress = (engine.progress.value / .8f).coerceIn(0f, 1f)

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = 26.dp, start = 8.dp, end = 8.dp, bottom = 6.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { settings = true }) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }

                HorizontalPager(
                    state = pager,
                    modifier = Modifier.weight(1f)
                ) { page ->
                    WorkspaceGrid(
                        items = pages[page],
                        appMap = appMap,
                        onMove = { from, to ->
                            val base = workspace.toMutableList()
                            val a = page * GRID_CELLS + from
                            val b = page * GRID_CELLS + to
                            if (a in base.indices && b in base.indices && a != b) {
                                val value = base[a]
                                base[a] = base[b]
                                base[b] = value
                                workspace = base
                                store.save(base, dock)
                            }
                        },
                        onLaunch = { app, x, y ->
                            if (engine.phase == ImuxAnimationEngine.Phase.IDLE) {
                                engine.setOrigin(x, y)
                                activeApp = app
                                scope.launch {
                                    if (engine.open()) launch(app)
                                }
                            }
                        }
                    )
                }

                Dock(dock, appMap) { app, x, y ->
                    if (engine.phase == ImuxAnimationEngine.Phase.IDLE) {
                        engine.setOrigin(x, y)
                        activeApp = app
                        scope.launch {
                            if (engine.open()) launch(app)
                        }
                    }
                }

                if (pages.size > 1) {
                    Text(
                        "${pager.currentPage + 1} / ${pages.size}",
                        Modifier.align(Alignment.CenterHorizontally),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            if (engine.phase != ImuxAnimationEngine.Phase.IDLE && activeApp != null) {
                LaunchExpansionOverlay(
                    app = activeApp!!,
                    progress = overlayProgress,
                    originX = engine.originX,
                    originY = engine.originY,
                    closing = engine.phase == ImuxAnimationEngine.Phase.CLOSING,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (engine.phase != ImuxAnimationEngine.Phase.IDLE) {
                LinearProgressIndicator(
                    progress = { overlayProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .alpha(if (engine.phase == ImuxAnimationEngine.Phase.OPENING) 1f else 0f)
                )
            }
        }

        if (settings) {
            ModalBottomSheet(onDismissRequest = { settings = false }) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .navigationBarsPadding()
                ) {
                    Text("Imux Launcher", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    SettingRow("Swipe up for app drawer", swipeDrawer) {
                        swipeDrawer = it
                        prefs.edit().putBoolean("swipe_drawer", it).apply()
                    }
                    SettingRow(
                        "Vivo launcher compatibility",
                        prefs.getBoolean("vivo_compat_mode", false)
                    ) {
                        VivoLauncherManager.setEnabled(context, it)
                    }
                    OutlinedButton(::requestDefaultLauncher, Modifier.fillMaxWidth()) {
                        Text("Set Imux as default launcher")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton({ settings = false; logs = true }, Modifier.fillMaxWidth()) {
                        Text("Diagnostics and logs")
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }

        if (logs) LogDialog { logs = false }
    }
}

/**
 * Full-screen icon morph. The icon starts at the tapped cell and expands as a real surface,
 * progressively covering the launcher. The final surface is intentionally larger than the
 * viewport so no edge is exposed during the transition.
 */
@Composable
private fun LaunchExpansionOverlay(
    app: AppInfo,
    progress: Float,
    originX: Float,
    originY: Float,
    closing: Boolean,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val heightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val iconSize = with(density) { 52.dp.toPx() }
        val p = if (closing) 1f - progress else progress
        val eased = FastOutSlowInEasing.transform(p.coerceIn(0f, 1f))

        val startCenterX = originX * widthPx
        val startCenterY = originY * heightPx
        val endWidth = widthPx * 1.16f
        val endHeight = heightPx * 1.16f
        val currentWidth = iconSize + (endWidth - iconSize) * eased
        val currentHeight = iconSize + (endHeight - iconSize) * eased
        val left = startCenterX - currentWidth / 2f
        val top = startCenterY - currentHeight / 2f
        val rect = RectF(left, top, left + currentWidth, top + currentHeight)
        val radius = iconSize * .34f * (1f - eased).coerceAtLeast(0f)
        val edgeFade = ((eased - .72f) / .28f).coerceIn(0f, 1f)
        val scrimAlpha = (.18f * eased + .10f * edgeFade).coerceIn(0f, .28f)

        Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()

                if (scrimAlpha > 0f) {
                    native.drawColor(
                        android.graphics.Color.argb(
                            (scrimAlpha * 255f).toInt(),
                            0,
                            0,
                            0
                        )
                    )
                }

                native.save()
                native.clipPath(
                    android.graphics.Path().apply {
                        addRoundRect(
                            rect,
                            radius,
                            radius,
                            android.graphics.Path.Direction.CW
                        )
                    }
                )

                val drawable: Drawable = app.icon
                val bounds = android.graphics.Rect(
                    rect.left.toInt(),
                    rect.top.toInt(),
                    rect.right.toInt(),
                    rect.bottom.toInt()
                )
                drawable.setBounds(bounds)
                drawable.draw(native)
                native.restore()
                native.restore()
            }
        }

        if (edgeFade > 0f) {
            Canvas(Modifier.fillMaxSize().alpha(edgeFade * .18f)) {
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = .55f)),
                        center = androidx.compose.ui.geometry.Offset(startCenterX, startCenterY),
                        radius = max(widthPx, heightPx) * .78f
                    )
                )
            }
        }
    }
}

@Composable
private fun WorkspaceGrid(
    items: List<String>,
    appMap: Map<String, AppInfo>,
    onMove: (Int, Int) -> Unit,
    onLaunch: (AppInfo, Float, Float) -> Unit
) {
    var dragging by remember { mutableStateOf(-1) }
    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) }
    var currentIndex by remember { mutableStateOf(-1) }

    Column(Modifier.fillMaxSize()) {
        repeat(7) { row ->
            Row(Modifier.weight(1f).fillMaxWidth()) {
                repeat(GRID_COLUMNS) { col ->
                    val index = row * GRID_COLUMNS + col
                    val app = items.getOrNull(index)?.let(appMap::get)

                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(2.dp)
                            .graphicsLayer {
                                if (dragging == index) {
                                    translationX = dragX
                                    translationY = dragY
                                    scaleX = 1.08f
                                    scaleY = 1.08f
                                }
                            }
                            .pointerInput(app?.packageName) {
                                detectDragGestures(
                                    onDragStart = {
                                        if (app != null) {
                                            dragging = index
                                            currentIndex = index
                                            dragX = 0f
                                            dragY = 0f
                                        }
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        if (dragging >= 0) {
                                            dragX += amount.x
                                            dragY += amount.y
                                            val columnShift = (dragX / 88f).toInt()
                                            val rowShift = (dragY / 72f).toInt()
                                            val target = (
                                                currentIndex +
                                                    columnShift +
                                                    rowShift * GRID_COLUMNS
                                                ).coerceIn(0, GRID_CELLS - 1)
                                            if (target != currentIndex) {
                                                onMove(currentIndex, target)
                                                currentIndex = target
                                                dragX = 0f
                                                dragY = 0f
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        dragging = -1
                                        currentIndex = -1
                                        dragX = 0f
                                        dragY = 0f
                                    },
                                    onDragCancel = {
                                        dragging = -1
                                        currentIndex = -1
                                        dragX = 0f
                                        dragY = 0f
                                    }
                                )
                            }
                            .pointerInput(app?.packageName) {
                                detectTapGestures(
                                    onTap = {
                                        if (app != null) {
                                            onLaunch(
                                                app,
                                                (col + .5f) / GRID_COLUMNS,
                                                .16f + (row + .5f) * .70f / 7f
                                            )
                                        }
                                    }
                                )
                            }
                    ) {
                        if (app != null) AppIcon(app)
                    }
                }
            }
        }
    }
}

@Composable
private fun Dock(
    dock: List<String>,
    appMap: Map<String, AppInfo>,
    onLaunch: (AppInfo, Float, Float) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(DOCK_CELLS) { i ->
                dock.getOrNull(i)?.let { packageName ->
                    appMap[packageName]?.let { app ->
                        Box(
                            Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            AppIcon(app, Modifier.size(52.dp))
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .pointerInput(app.packageName) {
                                        detectTapGestures {
                                            onLaunch(app, (i + .5f) / DOCK_CELLS, .91f)
                                        }
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(app: AppInfo, modifier: Modifier = Modifier.size(48.dp)) {
    Canvas(modifier) {
        val drawable: Drawable = app.icon
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val scale = minOf(size.width / w, size.height / h)
        val dw = w * scale
        val dh = h * scale
        val left = ((size.width - dw) / 2f).toInt()
        val top = ((size.height - dh) / 2f).toInt()
        drawable.setBounds(left, top, (left + dw).toInt(), (top + dh).toInt())
        drawIntoCanvas { canvas -> drawable.draw(canvas.nativeCanvas) }
    }
}

@Composable
private fun SettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(CrashLogger.read(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnostics") },
        text = {
            Text(
                text,
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                style = MaterialTheme.typography.bodySmall
            )
        },
        confirmButton = {
            TextButton(onClick = { text = CrashLogger.read(context) }) { Text("Refresh") }
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
