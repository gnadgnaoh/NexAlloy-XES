package io.github.nexalloy.revanced.instagram.profile

import android.content.Context
import app.morphe.extension.shared.Logger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.concurrent.thread

internal class ScreenAnswer private constructor(
    private val answer: Field,
    private val value: Method,
    private val tree: Field,
    private val reinterpret: Method,
    private val retype: Method,
    private val subtree: Method,
    private val flag: Method,
    private val type: Int,
    private val client: String,
) {
    fun friendship(viewModel: Any): Any? = runCatching {
        val holder = value.invoke(answer.get(viewModel) ?: return null) ?: return null
        if (!tree.declaringClass.isInstance(holder)) return null
        val root = tree.get(holder) ?: return null
        val user = reinterpret.invoke(root, type) ?: return null
        val typed = retype.invoke(user, client, type) ?: return null
        subtree.invoke(typed, FRIENDSHIP_STATUS)
    }.getOrNull()

    fun flag(status: Any, key: Int): Boolean? = runCatching { flag.invoke(status, key) as? Boolean }.getOrNull()

    companion object {
        private const val PREFS = "nexalloy_instagram"
        private val FRIENDSHIP_STATUS = "friendship_status".hashCode()
        val FOLLOWED_BY = "followed_by".hashCode()
        val FOLLOWING = "following".hashCode()

        @Volatile
        private var loaded: ScreenAnswer? = null

        fun get(): ScreenAnswer? = loaded

        fun load(context: Context, classLoader: ClassLoader) {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val key = "screen_answer:${info.versionName}:${info.lastUpdateTime}"
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(key, null)?.let { parse(it, classLoader)?.let { a -> loaded = a; return } }
            thread(name = "nexalloy-screen-answer", isDaemon = true) {
                runCatching {
                    val value = DexKitBridge.create(context.applicationInfo.sourceDir).use { it.screenAnswerValue() }
                    prefs.edit().apply { prefs.all.keys.filter { it.startsWith("screen_answer:") }.forEach { remove(it) } }
                        .putString(key, value).apply()
                    loaded = parse(value, classLoader)
                    Logger.printInfo { "Instagram: profile screen answer $value" }
                }.onFailure { Logger.printInfo { "Instagram: profile screen answer not found: $it" } }
            }
        }

        private fun parse(value: String, classLoader: ClassLoader): ScreenAnswer? = runCatching {
            val p = value.split(';')
            val viewModel = classLoader.loadClass("com.instagram.profile.fragment.UserDetailViewModel")
            val answer = viewModel.getDeclaredField(p[0]).apply { isAccessible = true }
            val getValue = answer.type.getMethod("getValue").apply { isAccessible = true }
            val tree = classLoader.loadClass(p[1]).getDeclaredField(p[2]).apply { isAccessible = true }
            val t = tree.type
            val int = Int::class.javaPrimitiveType!!
            ScreenAnswer(
                answer, getValue, tree,
                t.getMethod(p[3], int), t.getMethod(p[4], String::class.java, int), t.getMethod(p[5], int), t.getMethod(p[6], int),
                p[7].toInt(), p[8],
            )
        }.onFailure { Logger.printInfo { "Instagram: profile screen answer unusable: $it" } }.getOrNull()
    }
}
