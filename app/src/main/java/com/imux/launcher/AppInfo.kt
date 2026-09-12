package com.imux.launcher

import android.graphics.drawable.Drawable

/** Lightweight app metadata. The icon is resolved only when a cell actually needs it. */
data class AppInfo(
    val label: String,
    val packageName: String,
    private val iconLoader: () -> Drawable,
    val launch: () -> Unit
) {
    val icon: Drawable by lazy(LazyThreadSafetyMode.NONE) { iconLoader() }
}
