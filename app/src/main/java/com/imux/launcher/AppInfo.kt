package com.imux.launcher

import android.graphics.drawable.Drawable

 data class AppInfo(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val launch: () -> Unit
)
