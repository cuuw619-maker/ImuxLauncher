package com.imux.launcher

import android.content.Context
import android.content.Intent

/**
 * Optional Vivo compatibility layer. It never disables or removes the stock launcher.
 * Root actions are only performed after the user explicitly enables this mode.
 */
object VivoLauncherManager {
    const val STOCK_PACKAGE = "com.bbk.launcher2"
    const val IMUX_PACKAGE = "com.imux.launcher"
    private const val PREFS = "launcher"
    private const val KEY_COMPAT = "vivo_compat_mode"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_COMPAT, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_COMPAT, enabled).apply()
        CrashLogger.log(context, "INFO", "Vivo compatibility mode=${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Re-assert Imux as Android's HOME role, then stop the Vivo launcher.
     * This is intentionally opt-in and requires an already-authorized su provider.
     */
    fun enforceImuxHome(): Result<String> = runCatching {
        val roleResult = RootManager.exec(
            "cmd role add-role-holder --user 0 android.app.role.HOME $IMUX_PACKAGE"
        ).getOrThrow()
        RootManager.exec("am force-stop $STOCK_PACKAGE").getOrThrow()
        roleResult.ifBlank { "Imux HOME role enforced" }
    }

    fun suppressStockLauncher(): Result<String> = RootManager.exec(
        "am force-stop $STOCK_PACKAGE"
    )

    fun emergencyRestore(context: Context): Result<Unit> = runCatching {
        // Always try the normal Android HOME route first; it does not depend on root.
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
            )
        }

        val fallback = context.packageManager.getLaunchIntentForPackage(STOCK_PACKAGE)
        if (fallback != null) {
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(fallback)
        }
    }
}
