package com.imux.launcher

import android.content.Context
import android.content.Intent

/**
 * Optional Vivo compatibility layer. It never disables or removes the stock launcher.
 * Force-stop is deliberately opt-in and is only attempted after the user granted root.
 */
object VivoLauncherManager {
    const val STOCK_PACKAGE = "com.bbk.launcher2"
    private const val PREFS = "launcher"
    private const val KEY_COMPAT = "vivo_compat_mode"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_COMPAT, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_COMPAT, enabled).apply()
        CrashLogger.log(context, "INFO", "Vivo compatibility mode=${if (enabled) "enabled" else "disabled"}")
    }

    fun suppressStockLauncher(): Result<String> = RootManager.exec(
        "am force-stop $STOCK_PACKAGE"
    )

    fun emergencyRestore(context: Context): Result<Unit> = runCatching {
        RootManager.exec("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
            .getOrThrow()
        val fallback = context.packageManager.getLaunchIntentForPackage(STOCK_PACKAGE)
        if (fallback != null) {
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(fallback)
        }
    }
}
