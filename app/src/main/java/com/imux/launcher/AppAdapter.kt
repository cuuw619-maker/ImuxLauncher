package com.imux.launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(private var items: List<AppInfo>) : RecyclerView.Adapter<AppAdapter.Holder>() {
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(android.R.id.icon)
        val label: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val row = LinearRow(parent.context)
        return Holder(row)
    }

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

    private class LinearRow(context: android.content.Context) : android.widget.LinearLayout(context) {
        init {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(28, 18, 28, 18)
            val icon = ImageView(context).apply {
                id = android.R.id.icon
                layoutParams = LayoutParams(64, 64)
            }
            val text = TextView(context).apply {
                id = android.R.id.text1
                textSize = 17f
                setPadding(24, 0, 0, 0)
            }
            addView(icon)
            addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
    }
}
