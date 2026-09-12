package com.imux.launcher

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    private lateinit var rootStatus: TextView
    private lateinit var rootButton: Button
    private lateinit var desktopContainer: FrameLayout
    private lateinit var drawerContainer: LinearLayout
    private lateinit var drawerSearch: EditText
    private lateinit var drawerAdapter: AppAdapter
    private lateinit var drawerCount: TextView
    private var allApps: List<AppInfo> = emptyList()
    private var rootGranted = false

    private val homeRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (::rootStatus.isInitialized) rootStatus.text = if (isHomeRoleHeld()) "Default launcher: Imux" else "Default launcher: not selected"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        if (prefs.getBoolean("setup_complete", false)) showDesktop() else showSetup()
        loadApps()
        requestRootAutomatically()
    }

    override fun onResume() {
        super.onResume()
        if (::desktopContainer.isInitialized && prefs.getBoolean("launcher_protection", false)) verifyLauncherProtection()
    }

    private fun configureWindow() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun showSetup() {
        val scroll = ScrollView(this)
        val setup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 72, 32, 40); setBackgroundColor(Color.rgb(10, 10, 14)) }
        setup.addView(TextView(this).apply { text = "IMUX"; textSize = 42f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }, matchWrap())
        setup.addView(TextView(this).apply { text = "Launcher setup"; textSize = 20f; setTextColor(Color.LTGRAY); setPadding(0, 4, 0, 28) }, matchWrap())
        rootStatus = TextView(this).apply { text = "Root: checking…"; textSize = 16f; setTextColor(Color.LTGRAY); setPadding(0, 0, 0, 16) }
        setup.addView(rootStatus, matchWrap())
        rootButton = Button(this).apply { text = "Provide root"; isAllCaps = false; setOnClickListener { requestRoot() } }
        setup.addView(rootButton, matchWrap())
        setup.addView(Button(this).apply { text = "Set Imux as default launcher"; isAllCaps = false; setOnClickListener { requestDefaultLauncher() } }, matchWrap().apply { topMargin = 10 })
        setup.addView(CheckBox(this).apply { text = "Protect Imux as preferred launcher"; setTextColor(Color.WHITE); isChecked = prefs.getBoolean("launcher_protection", false); setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("launcher_protection", checked).apply() } }, matchWrap().apply { topMargin = 12 })
        setup.addView(TextView(this).apply { text = "Imux can keep checking that it is the selected HOME target. The bottom system navigation swipe is controlled by Android and cannot be intercepted by a normal APK."; textSize = 14f; setTextColor(Color.GRAY); setPadding(0, 6, 0, 22) }, matchWrap())
        setup.addView(Button(this).apply { text = "Enter desktop"; isAllCaps = false; setOnClickListener { prefs.edit().putBoolean("setup_complete", true).apply(); showDesktop() } }, matchWrap())
        scroll.addView(setup)
        setContentView(scroll)
    }

    private fun requestDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME) && !roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                homeRoleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                return
            }
        }
        runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
    }

    private fun showDesktop() {
        desktopContainer = GestureFrameLayout(this).apply { setBackgroundColor(Color.rgb(8, 9, 13)); onSwipeUp = { openDrawer() }; onSwipeDown = { closeDrawer() } }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 46, 24, 18) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val clock = TextView(this).apply { textSize = 40f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }
        refreshClock(clock)
        top.addView(clock, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(TextView(this).apply { text = "IMUX"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.GRAY) })
        content.addView(top, matchWrap())
        content.addView(TextView(this).apply { text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()); textSize = 15f; setTextColor(Color.GRAY); setPadding(2, 0, 0, 26) }, matchWrap())
        content.addView(TextView(this).apply { text = "Swipe up to open applications"; textSize = 14f; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, 0, 1f))
        val dock = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(8, 12, 8, 8); setBackgroundColor(Color.rgb(24, 25, 31)) }
        content.addView(dock, LinearLayout.LayoutParams(-1, 82))
        desktopContainer.addView(content, FrameLayout.LayoutParams(-1, -1))
        drawerContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 42, 22, 16); setBackgroundColor(Color.rgb(12, 13, 18)); visibility = View.GONE }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this@MainActivity).apply { text = "Applications"; textSize = 28f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        drawerCount = TextView(this).apply { setTextColor(Color.GRAY) }
        header.addView(drawerCount)
        drawerContainer.addView(header, matchWrap())
        drawerSearch = EditText(this).apply { hint = "Search"; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); addTextChangedListener { filterDrawer(it?.toString().orEmpty()) } }
        drawerContainer.addView(drawerSearch, matchWrap().apply { topMargin = 12; bottomMargin = 10 })
        val list = RecyclerView(this).apply { layoutManager = GridLayoutManager(this@MainActivity, 4); itemAnimator = null }
        drawerAdapter = AppAdapter(emptyList(), grid = true)
        list.adapter = drawerAdapter
        drawerContainer.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        desktopContainer.addView(drawerContainer, FrameLayout.LayoutParams(-1, -1))
        setContentView(desktopContainer)
        refreshDesktopApps()
        verifyLauncherProtection()
    }

    private fun refreshDesktopApps() {
        if (!::desktopContainer.isInitialized) return
        val content = desktopContainer.getChildAt(0) as? LinearLayout ?: return
        val dock = content.getChildAt(content.childCount - 1) as? LinearLayout ?: return
        dock.removeAllViews()
        allApps.take(4).forEach { app -> dock.addView(createShortcut(app), LinearLayout.LayoutParams(0, -1, 1f)) }
    }

    private fun createShortcut(app: AppInfo): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(ImageView(this@MainActivity).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(48, 48))
        addView(TextView(this@MainActivity).apply { text = app.label; textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 1 }, LinearLayout.LayoutParams(-1, -2))
        setOnClickListener { app.launch() }
    }

    private fun openDrawer() { if (!::drawerContainer.isInitialized) return; drawerContainer.visibility = View.VISIBLE; drawerContainer.alpha = 0f; drawerContainer.animate().alpha(1f).setDuration(180).start(); filterDrawer(drawerSearch.text?.toString().orEmpty()) }

    private fun closeDrawer() { if (!::drawerContainer.isInitialized || drawerContainer.visibility != View.VISIBLE) return; drawerContainer.animate().alpha(0f).setDuration(140).withEndAction { drawerContainer.visibility = View.GONE }.start() }

    private fun filterDrawer(query: String) {
        if (!::drawerAdapter.isInitialized) return
        val normalized = query.trim().lowercase()
        val filtered = if (normalized.isEmpty()) allApps else allApps.filter { it.label.lowercase().contains(normalized) || it.packageName.lowercase().contains(normalized) }
        drawerAdapter.submitList(filtered)
        drawerCount.text = "${filtered.size}"
    }

    private fun loadApps() {
        executor.execute {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            val list = resolved.distinctBy { it.activityInfo.packageName }.mapNotNull { info ->
                val launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName) ?: return@mapNotNull null
                AppInfo(info.loadLabel(pm).toString(), info.activityInfo.packageName, info.loadIcon(pm)) { startActivity(launchIntent) }
            }.sortedBy { it.label.lowercase() }
            runOnUiThread { allApps = list; refreshDesktopApps(); if (::drawerAdapter.isInitialized) filterDrawer(drawerSearch.text?.toString().orEmpty()) }
        }
    }

    private fun requestRootAutomatically() { if (!rootGranted) requestRoot() }

    private fun requestRoot() {
        if (::rootButton.isInitialized) rootButton.isEnabled = false
        if (::rootStatus.isInitialized) rootStatus.text = "Root: requesting permission…"
        executor.execute {
            val result = RootManager.requestRoot()
            runOnUiThread {
                rootGranted = result.isSuccess
                if (::rootStatus.isInitialized) rootStatus.text = if (rootGranted) "Root: granted" else "Root: denied / unavailable"
                if (::rootButton.isInitialized) { rootButton.isEnabled = !rootGranted; rootButton.text = if (rootGranted) "Root granted" else "Provide root again" }
                if (rootGranted) verifyLauncherProtection()
            }
        }
    }

    private fun verifyLauncherProtection() {
        if (!prefs.getBoolean("launcher_protection", false) || !rootGranted) return
        if (isHomeRoleHeld()) return
        executor.execute { RootManager.exec("cmd role add-role-holder --user current android.app.role.HOME com.imux.launcher") }
    }

    private fun isHomeRoleHeld(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = getSystemService(RoleManager::class.java)
        return roleManager.isRoleAvailable(RoleManager.ROLE_HOME) && roleManager.isRoleHeld(RoleManager.ROLE_HOME)
    }

    private fun refreshClock(view: TextView) { view.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()); view.postDelayed({ if (!isFinishing) refreshClock(view) }, 30_000) }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
