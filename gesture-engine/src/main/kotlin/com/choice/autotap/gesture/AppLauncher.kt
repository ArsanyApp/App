package com.choice.autotap.gesture

import android.content.Context
import android.content.Intent

object AppLauncher {

    /** Starts the launcher activity of [packageName]. Returns false if it has none. */
    fun launch(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
