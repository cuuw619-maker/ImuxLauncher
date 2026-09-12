package com.imux.launcher

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var rootStatus: TextView
    private lateinit var rootButton: Button
    private lateinit var apps: RecyclerView
    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadApps()
        requestRootAutomatically()
    }

    private fun buildUi() {
        window.statusBarColor = Color.TRANSPARENT
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 48, 28, 24)
            setBackgroundColor(Color.rgb(12, 12, 15))
        }

        val title = TextView(this).apply {
            text = "Imux Launcher"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))

        rootStatus = TextView(this).apply {
            text = "Root: checking…"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, 8, 0, 12)
        }
        root.addView(rootStatus)

        rootButton = Button(this).apply {
            text = "Request root"
            setOnClickListener { requestRoot() }
        }
        root.addView(rootButton, LinearLayout.LayoutParams(-1, -2))

        val appsTitle = TextView(this).apply {
            text = "Applications"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 22, 0, 10)
        }
        root.addView(appsTitle)

        apps = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setBackgroundColor(Color.rgb(20, 20, 24))
        }
        adapter = AppAdapter(emptyList())
        apps.adapter = adapter
        root.addView(apps, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    private fun loadApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val list = resolved
            .filter { it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0 || true }
            .distinctBy { it.activityInfo.packageName }
            .map { info ->
                AppInfo(
                    label = info.loadLabel(pm).toString(),
                    packageName = info.activityInfo.packageName,
                    icon = info.loadIcon(pm),
                    launch = {
                        val launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                        launchIntent?.let { startActivity(it) }
                    }
                )
            }
            .sortedBy { it.label.lowercase() }
        adapter.submitList(list)
    }

    private fun requestRootAutomatically() {
        rootStatus.text = "Root: requesting permission…"
        rootButton.isEnabled = false
        executor.execute {
            val result = RootManager.requestRoot()
            runOnUiThread { applyRootResult(result) }
        }
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

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
