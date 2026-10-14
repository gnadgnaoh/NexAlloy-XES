package io.github.nexalloy.revanced.instagram.download

import android.content.Context
import androidx.annotation.StringRes
import io.github.nexalloy.BuildConfig

object I18n {

    @Volatile
    private var moduleContext: Context? = null

    @JvmStatic
    fun t(hostContext: Context?, @StringRes resId: Int, vararg args: Any?): String {
        return try {
            val module = moduleContext(hostContext) ?: return ""
            if (args.isEmpty()) module.getString(resId)
            else module.getString(resId, *args)
        } catch (e: Exception) {
            ""
        }
    }

    private fun moduleContext(hostContext: Context?): Context? {
        moduleContext?.let { return it }
        synchronized(I18n::class.java) {
            if (moduleContext == null) {
                try {
                    moduleContext = hostContext?.createPackageContext(
                        BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY
                    )
                } catch (e: Exception) {
                    ModuleLog.line("(NA|I18n) cannot reach module resources: $e")
                }
            }
            return moduleContext
        }
    }
}
