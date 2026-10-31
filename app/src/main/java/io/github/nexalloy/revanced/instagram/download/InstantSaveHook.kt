package io.github.nexalloy.revanced.instagram.download

import android.app.AndroidAppHelper
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import io.github.nexalloy.R
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Member
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

internal object InstantSaveHook {

    @Volatile
    private var currentMedia: Any? = null

    @Volatile
    private var lastSaveMs = 0L

    fun hookViewer(constructors: List<Member>) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!FeatureFlags.saveInstants) return
                for (arg in param.args) {
                    val media = if (MediaResolver.isMedia(arg)) arg else MediaResolver.fieldOfType(arg, MediaResolver.mediaType)
                    if (media != null) {
                        currentMedia = media
                        return
                    }
                }
            }
        }
        constructors.forEach { de.robv.android.xposed.XposedBridge.hookMethod(it, hook) }
        ModuleLog.line("(NA|Instant) viewer constructors hooked: ${constructors.size}")
    }

    fun hookLongPress(constructors: List<Member>, cl: ClassLoader) {
        val function1 = cl.loadClass("kotlin.jvm.functions.Function1")
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!FeatureFlags.saveInstants) return
                val self = param.thisObject ?: return
                for (f in function1Fields(self.javaClass, function1)) wrap(self, f, function1, cl)
            }
        }
        constructors.forEach { de.robv.android.xposed.XposedBridge.hookMethod(it, hook) }
        ModuleLog.line("(NA|Instant) long-press constructors hooked: ${constructors.size}")
    }

    private val fieldCache = java.util.concurrent.ConcurrentHashMap<Class<*>, List<Field>>()

    private fun function1Fields(cls: Class<*>, function1: Class<*>): List<Field> = fieldCache.getOrPut(cls) {
        cls.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.type == function1 }
            .onEach { it.isAccessible = true }
    }

    private fun wrap(owner: Any, field: Field, function1: Class<*>, cl: ClassLoader) {
        try {
            val original = field.get(owner) ?: return
            if (Proxy.isProxyClass(original.javaClass)) return
            val proxy = Proxy.newProxyInstance(cl, arrayOf(function1), InvocationHandler { _, method, args ->
                if (method.name == "invoke" && args?.firstOrNull() == true) onLongPress()
                method.invoke(original, *(args ?: emptyArray()))
            })
            field.set(owner, proxy)
        } catch (t: Throwable) {
            ModuleLog.line("(NA|Instant) wrap ${field.name}: $t")
        }
    }

    private fun onLongPress() {
        val now = SystemClock.uptimeMillis()
        if (now - lastSaveMs < 1000) return
        lastSaveMs = now
        val ctx = AndroidAppHelper.currentApplication() ?: return
        val media = currentMedia
        if (media == null) {
            MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_instant_save_none))
            return
        }
        MediaSaver.executor.submit {
            val item = MediaResolver.single(ctx, media)
            if (item == null) {
                MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_instant_save_none))
                return@submit
            }
            DownloadUi.saveOne(ctx, item, MediaKind.INSTANT, MediaResolver.username(media), MediaResolver.mediaId(media))
        }
    }
}
