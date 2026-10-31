package io.github.nexalloy.morphe.tiktok.shared

import android.app.Activity
import android.app.AndroidAppHelper
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast
import androidx.annotation.StringRes
import io.github.nexalloy.BuildConfig
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

internal object TikTokUi {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var module: Context? = null

    fun app(): Context? = AndroidAppHelper.currentApplication()

    fun t(@StringRes id: Int, vararg args: Any?): String = runCatching {
        val ctx = module ?: app()!!.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
            .also { module = it }
        if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)
    }.getOrDefault("")

    fun toast(text: String) {
        if (text.isEmpty()) return
        main.post { runCatching { Toast.makeText(app(), text, Toast.LENGTH_SHORT).show() } }
    }

    fun post(block: () -> Unit) = main.post { runCatching(block) }

    fun copy(label: String, text: String): Boolean = runCatching {
        val clipboard = app()!!.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(label, text)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        clipboard.setPrimaryClip(clip)
        true
    }.getOrDefault(false)

    fun activity(ctx: Context?): Activity? {
        var c = ctx
        while (c is android.content.ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}

internal object Model {
    private val fields = ConcurrentHashMap<String, java.util.Optional<Field>>()
    private val getters = ConcurrentHashMap<String, java.util.Optional<Method>>()

    fun get(obj: Any?, getter: String, field: String = ""): Any? {
        obj ?: return null
        method(obj.javaClass, getter)?.let { m -> return runCatching { m.invoke(obj) }.getOrNull() }
        return if (field.isEmpty()) null else field(obj, field)
    }

    fun string(obj: Any?, getter: String, field: String = ""): String? =
        (get(obj, getter, field) as? CharSequence)?.toString()?.takeIf { it.isNotBlank() }

    fun field(obj: Any?, name: String): Any? {
        obj ?: return null
        val key = obj.javaClass.name + "#" + name
        val f = fields.getOrPut(key) {
            var c: Class<*>? = obj.javaClass
            var found: Field? = null
            while (c != null && found == null) {
                found = c.declaredFields.firstOrNull { it.name == name && !Modifier.isStatic(it.modifiers) }
                c = c.superclass
            }
            java.util.Optional.ofNullable(found?.apply { isAccessible = true })
        }.orElse(null) ?: return null
        return runCatching { f.get(obj) }.getOrNull()
    }

    fun ofType(obj: Any?, type: Class<*>): Any? {
        obj ?: return null
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                if (!type.isAssignableFrom(f.type) && !f.type.isAssignableFrom(type)) continue
                val v = runCatching { f.isAccessible = true; f.get(obj) }.getOrNull()
                if (type.isInstance(v)) return v
            }
            c = c.superclass
        }
        return null
    }

    fun urls(model: Any?): List<String> =
        ((get(model, "getUrlList", "urlList") as? List<*>) ?: emptyList<Any>())
            .filterIsInstance<String>().filter { it.startsWith("http") }

    private fun method(type: Class<*>, name: String): Method? = getters.getOrPut(type.name + "." + name) {
        java.util.Optional.ofNullable(runCatching { type.getMethod(name).takeIf { it.parameterCount == 0 } }.getOrNull())
    }.orElse(null)
}
