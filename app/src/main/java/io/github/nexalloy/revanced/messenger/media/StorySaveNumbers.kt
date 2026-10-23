package io.github.nexalloy.revanced.messenger.media

import android.content.Context
import app.morphe.extension.shared.Logger
import org.luckypray.dexkit.DexKitBridge
import kotlin.concurrent.thread

internal class StorySaveNumbers(val id: Int, val label: Int, val saveable: List<Class<*>>) {
    companion object {
        private const val PREFS = "nexalloy_messenger"

        @Volatile
        private var loaded: StorySaveNumbers? = null

        fun get(): StorySaveNumbers? = loaded

        fun load(context: Context, classLoader: ClassLoader) {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val key = "story_save:${info.versionName}:${info.lastUpdateTime}"
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(key, null)?.let { parse(it, classLoader)?.let { n -> loaded = n; return } }
            thread(name = "nexalloy-story-save", isDaemon = true) {
                runCatching {
                    val value = DexKitBridge.create(context.applicationInfo.sourceDir).use { it.storySaveNumbersValue() }
                    prefs.edit().clear().putString(key, value).apply()
                    loaded = parse(value, classLoader)
                    Logger.printInfo { "Messenger: story Save numbers $value" }
                }.onFailure { Logger.printInfo { "Messenger: story Save numbers not found: $it" } }
            }
        }

        private fun parse(value: String, classLoader: ClassLoader): StorySaveNumbers? = runCatching {
            val (id, label, types) = value.split(';')
            StorySaveNumbers(id.toInt(), label.toInt(), types.split(',').map { classLoader.loadClass(it) })
        }.getOrNull()
    }
}
