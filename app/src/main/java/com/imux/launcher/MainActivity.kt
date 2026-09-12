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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }

    private lateinit var rootStatus: TextView
    private lateinit var rootButton: Button
    private lateinit var desktop: GestureFrameLayout
    private lateinit var desktopApps: RecyclerView
    private lateinit var drawer: LinearLayout
    private lateinit var drawerSearch: EditText
    private lateinit var drawerAdapter: AppAdapter
    private lateinit var drawerCount: TextView
    private lateinit var desktopAdapter: AppAdapter

    private var allApps: List<AppInfo> = emptyList()
    private var rootGranted = false

    private val homeRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateHomeStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        if (prefs.getBoolean("setup_complete", false)) showDesktop() else showSetup()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        if (::desktop.isInitialized) loadApps()
    }

    private fun configureWindow() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun showSetup() {
        val scroll = ScrollView(this)
        val setup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 64, 30, 40)
            setBackgroundColor(Color.rgb(10, 10, 14))
        }

        setup.addView(TextView(this).apply {
            text = "IMUX"
            textSize = 42f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, matchWrap())

        setup.addView(TextView(this).apply {
            text = "Launcher application"
            textSize = 20f
            setTextColor(Color.LTGRAY)
            setPadding(0, 4, 0, 30)
        }, matchWrap())

        setup.addView(TextView(this).apply {
            text = "Imux is a normal Android application. Root is optional and is requested only when you press the button. Imux does not silently make itself the system launcher."
            textSize = 15f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 20)
        }, matchWrap())

        rootStatus = TextView(this).apply {
            text = "Root: not requested"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 12)
        }
        setup.addView(rootStatus, matchWrap())

        rootButton = Button(this).apply {
            text = "Provide root"
            isAllCaps = false
            setOnClickListener { requestRoot() }
        }
        setup.addView(rootButton, matchWrap())

        val swipeToggle = CheckBox(this).apply {
            text = "Enable swipe-up app drawer"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("swipe_drawer", false)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("swipe_drawer", checked).apply() }
        }
        setup.addView(swipeToggle, matchWrap().apply { topMargin = 16 })

        setup.addView(TextView(this).apply {
            text = "Default desktop: 4 columns × 7 rows. The app drawer gesture is disabled by default, so the initial desktop behaves like a normal Android/iOS-style icon grid."
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, 4, 0, 18)
        }, matchWrap())

        setup.addView(Button(this).apply {
            text = "Set Imux as default launcher"
            isAllCaps = false
            setOnClickListener { requestDefaultLauncher() }
        }, matchWrap())

        setup.addView(Button(this).apply {
            text = "Enter desktop"
            isAllCaps = false
            setOnClickListener {
                prefs.edit().putBoolean("setup_complete", true).apply()
                showDesktop()
            }
        }, matchWrap().apply { topMargin = 10 })

        scroll.addView(setup)
        setContentView(scroll)
        updateHomeStatus()
    }

    private fun updateHomeStatus() {
        if (!::rootStatus.isInitialized) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                rootStatus.text = if (roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                    if (rootGranted) "Root: granted · Imux is default launcher"
                    else "Root: not requested · Imux is default launcher"
                } else {
                    if (rootGranted) "Root: granted · Imux is not default launcher"
                    else "Root: not requested · Imux is not default launcher"
                }
            }
        }
    }

    private fun requestDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                homeRoleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                return
            }
        }
        runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
    }

    private fun showDesktop() {
        desktop = GestureFrameLayout(this).apply {
            setBackgroundColor(Color.rgb(8, 9, 13))
            onSwipeUp = {
                if (prefs.getBoolean("swipe_drawer", false)) openDrawer()
            }
            onSwipeDown = {
                if (drawer.visibility == View.VISIBLE) closeDrawer()
            }
        }

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 34, 16, 12)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 0, 8, 12)
        }
        header.addView(TextView(this).apply {
            text = "IMUX"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "${allApps.size} apps"
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        shell.addView(header, matchWrap())

        desktopApps = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(2, 4, 2, 4)
            clipToPadding = false
        }
        desktopAdapter = AppAdapter(emptyList(), grid = true)
        desktopApps.adapter = desktopAdapter
        shell.addView(desktopApps, LinearLayout.LayoutParams(-1, 0, 1f))

        val hint = TextView(this).apply {
            text = if (prefs.getBoolean("swipe_drawer", false)) "Swipe up for all apps" else ""
            textSize = 12f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 4)
        }
        shell.addView(hint, matchWrap())

        desktop.addView(shell, FrameLayout.LayoutParams(-1, -1))
        buildDrawer()
        setContentView(desktop)
        refreshDesktop()
    }

    private fun buildDrawer() {
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 34, 18, 12)
            setBackgroundColor(Color.rgb(12, 13, 18))
            visibility = View.GONE
            alpha = 0f
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this@MainActivity).apply {
            text = "All apps"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        drawerCount = TextView(this).apply { setTextColor(Color.GRAY) }
        header.addView(drawerCount)
        drawer.addView(header, matchWrap())

        drawerSearch = EditText(this).apply {
            hint = "Search applications"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            addTextChangedListener { filterDrawer(it?.toString().orEmpty()) }
        }
        drawer.addView(drawerSearch, matchWrap().apply { topMargin = 10; bottomMargin = 8 })

        val list = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        drawerAdapter = AppAdapter(emptyList(), grid = true)
        list.adapter = drawerAdapter
        drawer.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        desktop.addView(drawer, FrameLayout.LayoutParams(-1, -1))
    }

    private fun refreshDesktop() {
        if (!::desktopAdapter.isInitialized) return
        desktopAdapter.submitList(allApps.take(28))
        filterDrawer(drawerSearch.text?.toString().orEmpty())
    }

    private fun openDrawer() {
        if (!::drawer.isInitialized || drawer.visibility == View.VISIBLE) return
        filterDrawer(drawerSearch.text?.toString().orEmpty())
        drawer.visibility = View.VISIBLE
        drawer.translationY = drawer.height.toFloat().coerceAtLeast(resources.displayMetrics.heightPixels.toFloat())
        drawer.alpha = 0f
        drawer.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun closeDrawer() {
        if (!::drawer.isInitialized || drawer.visibility != View.VISIBLE) return
        drawer.animate()
            .translationY(drawer.height.toFloat())
            .alpha(0f)
            .setDuration(210)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                drawer.visibility = View.GONE
                drawer.translationY = 0f
            }
            .start()
    }

    private fun filterDrawer(query: String) {
        if (!::drawerAdapter.isInitialized) return
        val normalized = query.trim().lowercase()
        val filtered = if (normalized.isEmpty()) allApps else allApps.filter {
            it.label.lowercase().contains(normalized) || it.packageName.lowercase().contains(normalized)
        }
        drawerAdapter.submitList(filtered)
        drawerCount.text = "${filtered.size}"
    }

    private fun loadApps() {
        executor.execute {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            val list = resolved
                .distinctBy { it.activityInfo.packageName }
                .mapNotNull { info ->
                    val launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                        ?: return@mapNotNull null
                    AppInfo(
                        label = info.loadLabel(pm).toString(),
                        packageName = info.activityInfo.packageName,
                        icon = info.loadIcon(pm),
                        launch = {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent)
                        }
                    )
                }
                .sortedBy { it.label.lowercase() }

            runOnUiThread {
                allApps = list
                refreshDesktop()
                if (::rootStatus.isInitialized) updateHomeStatus()
            }
        }
    }

    private fun requestRoot() {
        rootButton.isEnabled = false
        rootStatus.text = "Root: requesting permission…"
        executor.execute {
            val result = RootManager.requestRoot()
            runOnUiThread {
                rootGranted = result.isSuccess
                rootStatus.text = if (rootGranted) "Root: granted" else "Root: denied / unavailable"
                rootButton.text = if (rootGranted) "Root granted" else "Provide root again"
                rootButton.isEnabled = !rootGranted
            }
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
