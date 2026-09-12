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
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.viewpager2.widget.ViewPager2
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }

    private lateinit var rootStatus: TextView
    private lateinit var rootButton: Button
    private lateinit var desktop: GestureFrameLayout
    private lateinit var desktopPager: ViewPager2
    private lateinit var pageIndicator: TextView
    private lateinit var drawer: LinearLayout
    private lateinit var drawerSearch: EditText
    private lateinit var drawerAdapter: AppAdapter
    private lateinit var drawerCount: TextView
    private lateinit var workspaceAdapter: WorkspacePageAdapter

    private var allApps: List<AppInfo> = emptyList()
    private var rootGranted = false
    private var launchingApp = false

    private val homeRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateHomeStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        configureBackProtection()
        if (prefs.getBoolean("setup_complete", false)) showDesktop() else showSetup()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        if (::desktopPager.isInitialized) {
            launchingApp = false
            loadApps()
        }
    }

    /**
     * A launcher cannot consume Android's system navigation gesture itself.
     * When Imux owns ROLE_HOME, however, Android should return to this task
     * instead of treating the gesture as an ordinary app exit. This callback
     * only brings Imux back for an actual HOME-role handoff; normal app launches
     * set launchingApp first and are therefore not interrupted.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!prefs.getBoolean("protect_desktop", true) || launchingApp) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                desktop.postDelayed({
                    if (!isFinishing && !launchingApp) {
                        moveTaskToFront(true)
                    }
                }, 80L)
            }
        }
    }

    private fun configureWindow() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun configureBackProtection() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::drawer.isInitialized && drawer.visibility == View.VISIBLE) {
                    closeDrawer()
                    return
                }
                if (prefs.getBoolean("protect_desktop", true) && prefs.getBoolean("setup_complete", false)) {
                    Toast.makeText(
                        this@MainActivity,
                        "Imux desktop is protected. Disable protection in setup to exit with Back.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun showSetup() {
        val scroll = ScrollView(this)
        val setup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(64), dp(30), dp(40))
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
            setPadding(0, dp(4), 0, dp(30))
        }, matchWrap())

        setup.addView(TextView(this).apply {
            text = "Imux is a normal Android application. It appears in the regular app list and can optionally be assigned the HOME role. Root is requested only through the installed su provider, including SukiSU-Ultra."
            textSize = 15f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(20))
        }, matchWrap())

        rootStatus = TextView(this).apply {
            text = "Root: not requested"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(12))
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
        setup.addView(swipeToggle, matchWrap().apply { topMargin = dp(16) })

        val protectionToggle = CheckBox(this).apply {
            text = "Protect desktop from Back / HOME-role exit"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("protect_desktop", true)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("protect_desktop", checked).apply() }
        }
        setup.addView(protectionToggle, matchWrap().apply { topMargin = dp(8) })

        setup.addView(TextView(this).apply {
            text = "Workspace: unlimited pages of 4 columns × 7 rows (28 icons per page). Swipe horizontally between pages. The initial swipe-up drawer remains disabled."
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, dp(4), 0, dp(18))
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
        }, matchWrap().apply { topMargin = dp(10) })

        scroll.addView(setup)
        setContentView(scroll)
        updateHomeStatus()
    }

    private fun updateHomeStatus() {
        if (!::rootStatus.isInitialized) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                rootStatus.text = when {
                    roleManager.isRoleHeld(RoleManager.ROLE_HOME) && rootGranted -> "Root: granted · Imux is default launcher"
                    roleManager.isRoleHeld(RoleManager.ROLE_HOME) -> "Root: not requested · Imux is default launcher"
                    rootGranted -> "Root: granted · Imux is not default launcher"
                    else -> "Root: not requested · Imux is not default launcher"
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
                if (::drawer.isInitialized && drawer.visibility == View.VISIBLE) closeDrawer()
            }
        }

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(28), dp(10), dp(8))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        header.addView(TextView(this).apply {
            text = "IMUX"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        pageIndicator = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
        }
        header.addView(pageIndicator, LinearLayout.LayoutParams(dp(64), -2))
        shell.addView(header, matchWrap())

        desktopPager = ViewPager2(this).apply {
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            offscreenPageLimit = 1
            isUserInputEnabled = true
            setPageTransformer { page, position ->
                page.alpha = 0.78f + (1f - kotlin.math.abs(position)).coerceIn(0f, 1f) * 0.22f
                page.translationX = -position * dp(8)
            }
        }
        workspaceAdapter = WorkspacePageAdapter(emptyList())
        desktopPager.adapter = workspaceAdapter
        desktopPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageIndicator(position)
            }
        })
        shell.addView(desktopPager, LinearLayout.LayoutParams(-1, 0, 1f))

        val hint = TextView(this).apply {
            text = if (prefs.getBoolean("swipe_drawer", false)) "Swipe up for all apps" else ""
            textSize = 12f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
        }
        shell.addView(hint, matchWrap())

        desktop.addView(shell, FrameLayout.LayoutParams(-1, -1))
        buildDrawer()
        setContentView(desktop)
        refreshDesktop()
    }

    private fun updatePageIndicator(position: Int) {
        if (!::pageIndicator.isInitialized) return
        val count = workspaceAdapter.itemCount.coerceAtLeast(1)
        pageIndicator.text = if (count == 1) "1 / 1" else "${position + 1} / $count"
    }

    private fun buildDrawer() {
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(30), dp(18), dp(10))
            setBackgroundColor(Color.rgb(12, 13, 18))
            visibility = View.GONE
            alpha = 0f
            scaleX = 0.985f
            scaleY = 0.985f
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
        drawerCount = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
        }
        header.addView(drawerCount)
        drawer.addView(header, matchWrap())

        drawerSearch = EditText(this).apply {
            hint = "Search applications"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            addTextChangedListener { filterDrawer(it?.toString().orEmpty()) }
        }
        drawer.addView(drawerSearch, matchWrap().apply { topMargin = dp(10); bottomMargin = dp(8) })

        val list = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@MainActivity, 4)
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        drawerAdapter = AppAdapter(emptyList(), grid = true)
        list.adapter = drawerAdapter
        drawer.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        desktop.addView(drawer, FrameLayout.LayoutParams(-1, -1))
    }

    private fun refreshDesktop() {
        if (!::workspaceAdapter.isInitialized) return
        workspaceAdapter.submitApps(allApps)
        updatePageIndicator(desktopPager.currentItem)
        if (::drawerSearch.isInitialized) filterDrawer(drawerSearch.text?.toString().orEmpty())
    }

    private fun openDrawer() {
        if (!::drawer.isInitialized || drawer.visibility == View.VISIBLE) return
        filterDrawer(drawerSearch.text?.toString().orEmpty())
        drawer.animate().cancel()
        drawer.visibility = View.VISIBLE
        drawer.translationY = resources.displayMetrics.heightPixels * 0.16f
        drawer.alpha = 0f
        drawer.scaleX = 0.985f
        drawer.scaleY = 0.985f
        drawer.animate()
            .translationY(0f)
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    private fun closeDrawer() {
        if (!::drawer.isInitialized || drawer.visibility != View.VISIBLE) return
        drawer.animate().cancel()
        drawer.animate()
            .translationY(resources.displayMetrics.heightPixels * 0.12f)
            .alpha(0f)
            .scaleX(0.985f)
            .scaleY(0.985f)
            .setDuration(220)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                drawer.visibility = View.GONE
                drawer.translationY = 0f
                drawer.alpha = 0f
                drawer.scaleX = 0.985f
                drawer.scaleY = 0.985f
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
                            launchingApp = true
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { startActivity(launchIntent) }
                            desktop.postDelayed({ launchingApp = false }, 1200L)
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
        rootStatus.text = "Root: requesting through su…"
        executor.execute {
            val result = RootManager.requestRoot()
            runOnUiThread {
                rootGranted = result.isSuccess
                rootStatus.text = if (rootGranted) {
                    "Root: granted through ${result.getOrNull() ?: "su"}"
                } else {
                    "Root: denied / unavailable"
                }
                rootButton.text = if (rootGranted) "Root granted" else "Provide root again"
                rootButton.isEnabled = !rootGranted
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
