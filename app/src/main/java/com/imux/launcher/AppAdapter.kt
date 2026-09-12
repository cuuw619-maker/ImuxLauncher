package com.imux.launcher

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(private var items: List<AppInfo>, private val grid: Boolean = false) : RecyclerView.Adapter<AppAdapter.Holder>() {
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(android.R.id.icon)
        val label: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(AppRow(parent.context, grid))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = items[position]
        holder.icon.setImageDrawable(app.icon)
        holder.label.text = app.label
        holder.itemView.setOnClickListener { app.launch() }
    }

    override fun getItemCount() = items.size

    fun submitList(newItems: List<AppInfo>) {
        items = newItems
        notifyDataSetChanged()
    }

    private class AppRow(context: android.content.Context, grid: Boolean) : LinearLayout(context) {
        private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

        init {
            id = android.R.id.content
            orientation = VERTICAL
            gravity = Gravity.CENTER
            if (grid) {
                // Launcher3 uses an icon image size around 48–56dp on phone profiles.
                // Keep the same physical scale instead of accidentally using raw pixels.
                setPadding(dp(3f), dp(4f), dp(3f), dp(4f))
            } else {
                setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
            }
            setBackgroundColor(if (grid) Color.TRANSPARENT else Color.rgb(20, 20, 24))

            val iconSize = if (grid) dp(54f) else dp(64f)
            val icon = ImageView(context).apply {
                id = android.R.id.icon
                layoutParams = LayoutParams(iconSize, iconSize)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            }
            val text = TextView(context).apply {
                id = android.R.id.text1
                textSize = if (grid) 13f else 17f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(if (grid) 0 else dp(16f), if (grid) dp(3f) else 0, 0, 0)
            }
            addView(icon)
            addView(text, LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
}
