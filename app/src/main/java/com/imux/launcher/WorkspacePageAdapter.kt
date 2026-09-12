package com.imux.launcher

import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** A Launcher3-style workspace page: exactly 4 columns x 7 rows (28 cells). */
class WorkspacePageAdapter(
    private var pages: List<List<AppInfo>>
) : RecyclerView.Adapter<WorkspacePageAdapter.PageHolder>() {

    class PageHolder(val recyclerView: RecyclerView) : RecyclerView.ViewHolder(recyclerView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val recycler = RecyclerView(parent.context).apply {
            layoutManager = GridLayoutManager(parent.context, 4)
            itemAnimator = null
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            setPadding(
                dp(parent.context, 2),
                dp(parent.context, 2),
                dp(parent.context, 2),
                dp(parent.context, 2)
            )
            clipToPadding = false
        }
        return PageHolder(recycler)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        holder.recyclerView.adapter = AppAdapter(pages[position], grid = true)
    }

    override fun getItemCount(): Int = pages.size

    fun submitApps(apps: List<AppInfo>) {
        pages = if (apps.isEmpty()) {
            listOf(emptyList())
        } else {
            apps.chunked(28)
        }
        notifyDataSetChanged()
    }

    private fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
