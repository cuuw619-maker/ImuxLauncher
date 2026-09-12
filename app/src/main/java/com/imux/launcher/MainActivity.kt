package com.imux.launcher

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var rootStatus: TextView
    private lateinit var rootButton: Button
    private lateinit var appCount: TextView
    private lateinit var search: EditText
    private lateinit var adapter: AppAdapter
    private var allApps: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadApps()
        requestRootAutomatically()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized && allApps.isNotEmpty()) loadApps()
    }

    private fun buildUi() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 40, 28, 20)
            setBackgroundColor(Color.rgb(12, 12, 15))
        }

        val title = TextView(this).apply {
            text = "Imux Launcher"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        root.addView(title, matchWrap())

        rootStatus = TextView(this).apply {
            text = "Root: checking…"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 6, 0, 8)
        }
        root.addView(rootStatus, matchWrap())

        rootButton = Button(this).apply {
            text = "Request root"
            isAllCaps = false
            setOnClickListener { requestRoot() }
        }
        root.addView(rootButton, matchWrap())

        search = EditText(this).apply {
            hint = "Search applications"
            textSize = 16f
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(20, 0, 20, 0)
            addTextChangedListener { editable -> filterApps(editable?.toString().orEmpty()) }
        }
        val searchParams = matchWrap().apply { topMargin = 16 }
        root.addView(search, searchParams)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val label = TextView(this@MainActivity).apply {
                text = "Applications"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }
            addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            appCount = TextView(this@MainActivity).apply {
                textSize = 14f
                setTextColor(Color.GRAY)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            addView(appCount, LinearLayout.LayoutParams(-2, -2))
        }
        root.addView(header, matchWrap().apply { topMargin = 18; bottomMargin = 10 })

        val appsView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setBackgroundColor(Color.rgb(20, 20, 24))
            itemAnimator = null
        }
        adapter = AppAdapter(emptyList())
        appsView.adapter = adapter
        root.addView(appsView, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    private fun loadApps() {
        executor.execute {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            val list = resolved
                .distinctBy { it.activityInfo.packageName }
                .mapNotNull { info ->
                    val launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName) ?: return@mapNotNull null
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
                filterApps(search.text?.toString().orEmpty())
            }
        }
    }

    private fun filterApps(query: String) {
        if (!::adapter.isInitialized) return
        val normalized = query.trim().lowercase()
        val filtered = if (normalized.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.label.lowercase().contains(normalized) || it.packageName.lowercase().contains(normalized)
            }
        }
        adapter.submitList(filtered)
        appCount.text = "${filtered.size} / ${allApps.size}"
    }

    private fun requestRootAutomatically() {
        requestRoot()
    }

    private fun requestRoot() {
        rootStatus.text = "Root: requesting permission…"
        rootButton.isEnabled = false
        executor.execute {
            val result = RootManager.requestRoot()
            runOnUiThread { applyRootResult(result) }
        }
    }

    private fun applyRootResult(result: Result<String>) {
        if (result.isSuccess) {
            rootStatus.text = "Root: granted — advanced mode ready"
            rootButton.text = "Root granted"
        } else {
            rootStatus.text = "Root: denied / unavailable"
            rootButton.text = "Request root again"
            rootButton.isEnabled = true
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
