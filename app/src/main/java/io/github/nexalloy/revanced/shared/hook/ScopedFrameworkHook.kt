package io.github.nexalloy.revanced.shared.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.hookMethod
import java.lang.reflect.Member

class ScopedFrameworkHook(
    private val target: Member,
    private val before: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
    private val after: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
) {
    private val depth = ThreadLocal.withInitial { 0 }
    private val lock = Any()
    private var inside = 0
    private var unhook: XC_MethodHook.Unhook? = null

    private val callback = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (depth.get() > 0) before?.let { runCatching { it(param) } }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            if (depth.get() > 0) after?.let { runCatching { it(param) } }
        }
    }

    fun installOn(scope: Member) {
        scope.hookMethod {
            before { enter() }
            after { leave() }
        }
    }

    private fun enter() {
        depth.set(depth.get() + 1)
        synchronized(lock) {
            if (inside++ == 0 && unhook == null) unhook = XposedBridge.hookMethod(target, callback)
        }
    }

    private fun leave() {
        val now = depth.get() - 1
        if (now <= 0) depth.remove() else depth.set(now)
        synchronized(lock) {
            if (--inside <= 0) {
                inside = 0
                unhook?.unhook()
                unhook = null
            }
        }
    }
}
