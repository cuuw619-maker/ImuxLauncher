package com.imux.launcher

import android.app.Application

class ImuxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.init(this)
    }
}
