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
        init {
            id = android.R.id.content
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(if (grid) 6 else 28, if (grid) 12 else 18, if (grid) 6 else 28, if (grid) 12 else 18)
            setBackgroundColor(if (grid) Color.TRANSPARENT else Color.rgb(20, 20, 24))
            val icon = ImageView(context).apply {
                id = android.R.id.icon
                layoutParams = LayoutParams(if (grid) 56 else 64, if (grid) 56 else 64)
            }
            val text = TextView(context).apply {
                id = android.R.id.text1
                textSize = if (grid) 12f else 17f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(if (grid) 0 else 24, if (grid) 6 else 0, 0, 0)
            }
            addView(icon)
            addView(text, LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
}
