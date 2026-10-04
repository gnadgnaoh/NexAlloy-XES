package io.github.nexalloy

import android.os.Handler
import android.os.Looper
import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean

internal class KatanaDexGate(private val executor: PatchExecutor) {

    private companion object {
        val PROBE_CLASSES = listOf(
            "com.facebook.graphql.model.GraphQLFeedUnitEdge",
            "com.facebook.auth.usersession.FbUserSession",
        )

        val RETRY_DELAYS_MS = longArrayOf(0, 400, 1_500, 4_000, 10_000, 25_000)

        const val DEX_LOADER_CLASS = "com.facebook.common.dextricks.MultiDexClassLoaderJava"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val loaderHooks = mutableListOf<XC_MethodHook.Unhook>()

    fun start() {
        hookDexLoader()
        RETRY_DELAYS_MS.forEachIndexed { index, delay ->
            val isLast = index == RETRY_DELAYS_MS.lastIndex
            handler.postDelayed({ attempt(finalAttempt = isLast) }, delay)
        }
    }

    private fun hookDexLoader() = runCatching {
        val loaderClass = XposedHelpers.findClassIfExists(DEX_LOADER_CLASS, executor.classLoader)
            ?: return@runCatching
        loaderClass.declaredMethods
            .filter { it.name == "configure" && it.parameterCount == 1 }
            .forEach { method ->
                method.isAccessible = true
                loaderHooks += XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (finished.get()) return
                        handler.post { attempt(finalAttempt = false) }
                    }
                })
            }
    }.onFailure { XposedBridge.log(it) }

    private fun attempt(finalAttempt: Boolean) {
        if (finished.get()) return
        if (!running.compareAndSet(false, true)) return
        try {
            val done = executor.runDeferredAttempt(finalAttempt) { isDexReady() }
            if (done || finalAttempt) {
                finished.set(true)
                handler.removeCallbacksAndMessages(null)
                loaderHooks.forEach { runCatching { it.unhook() } }
                loaderHooks.clear()
                Logger.printDebug {
                    "KatanaDexGate: settled (complete=$done, outstanding=${executor.outstandingPatchCount})"
                }
            }
        } catch (err: Throwable) {
            XposedBridge.log(err)
        } finally {
            running.set(false)
        }
    }

    private fun isDexReady(): Boolean = PROBE_CLASSES.all { name ->
        XposedHelpers.findClassIfExists(name, executor.classLoader) != null
    }
}
