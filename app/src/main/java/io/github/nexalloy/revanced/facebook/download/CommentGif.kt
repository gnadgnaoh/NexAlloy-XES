package io.github.nexalloy.revanced.facebook.download

import android.annotation.SuppressLint
import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.R
import io.github.nexalloy.hookMethod
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

private const val GRAPHQL_COMMENT = "com.facebook.graphql.model.GraphQLComment"
private const val FUNCTION1 = "kotlin.jvm.functions.Function1"
private val ICONS = listOf("fb_ic_download_outline_24", "fb_ic_gif_outline_24", "fb_ic_photo_outline_24", "fb_ic_download_24")
private val ICON_TYPES = listOf("drawable", "drawable.2", "drawable.3", "drawable.4", "drawable.5")

internal fun PatchExecutor.hookCommentGif() {
    val render = ::commentSheetRenderFingerprint.method
    val row = ::commentSheetRowFingerprint.method.apply { isAccessible = true }
    val click = ::commentSheetRowClickFingerprint.constructor.apply { isAccessible = true }
    val build = ::commentSheetBuildFingerprint.member
    val listAt = (build as java.lang.reflect.Executable).parameterTypes.indexOfLast { it == List::class.java }
    check(listAt >= 0) { "comment sheet build takes no List" }
    val function1 = classLoader.loadClass(FUNCTION1)
    val commentClass = classLoader.loadClass(GRAPHQL_COMMENT)
    val current = ThreadLocal<Array<Any?>?>()

    render.hookMethod {
        before { param ->
            val comment = findOfType(param.thisObject, commentClass, 2) ?: return@before
            param.setObjectExtra("na_gif_outer", current.get())
            current.set(arrayOf(comment, param.args.firstOrNull()))
        }
        after { param -> current.set(param.getObjectExtra("na_gif_outer") as? Array<Any?>) }
    }

    build.hookMethod {
        before { param ->
            val state = current.get() ?: return@before
            current.set(null)
            val comment = state[0] ?: return@before
            val gif = gifOf(comment) ?: return@before
            val ctx = appContext
            val title = MediaSaver.t(ctx, R.string.fb_dl_save_gif).ifEmpty { return@before }
            @SuppressLint("DiscouragedApi")
            val icon = ICONS.firstNotNullOfOrNull { name ->
                ICON_TYPES.firstNotNullOfOrNull { type ->
                    ctx.resources.getIdentifier(name, type, ctx.packageName).takeIf { it != 0 }
                }
            } ?: 0
            val action = Proxy.newProxyInstance(function1.classLoader, arrayOf(function1)) { proxy, method, args ->
                when (method.name) {
                    "invoke" -> { MediaSaver.saveGif(ctx, gif.first, gif.second); null }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "NexAlloySaveGif"
                    else -> null
                }
            }
            val onClick = click.newInstance(null, null, action)
            val item = row.invoke(null, onClick, state[1], title, null, icon) ?: return@before
            @Suppress("UNCHECKED_CAST")
            val rows = param.args[listAt] as? MutableList<Any?> ?: return@before
            runCatching { rows.add(item) }.onFailure {
                param.args[listAt] = ArrayList(rows).apply { add(item) }
            }
        }
    }
}

private fun gifOf(comment: Any): Pair<String?, String>? {
    val attachments = GraphModel.trees(comment, "attachments") ?: return null
    for (attachment in attachments) {
        val media = GraphModel.tree(attachment, "media") ?: continue
        val url = GraphModel.string(GraphModel.tree(media, "animated_image"), "uri")
        if (Urls.isHttp(url)) return GraphModel.string(media, "id") to url!!
    }
    return null
}

private fun findOfType(host: Any?, type: Class<*>, depth: Int): Any? {
    if (host == null) return null
    if (type.isInstance(host)) return host
    if (depth == 0) return null
    var cls: Class<*>? = host.javaClass
    while (cls != null && cls != Any::class.java) {
        for (f in cls.declaredFields) {
            if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
            val value = runCatching { f.isAccessible = true; f.get(host) }.getOrNull() ?: continue
            if (type.isInstance(value)) return value
            val name = value.javaClass.name
            if (name.startsWith("java.") || name.startsWith("android.") || name.startsWith("kotlin.")) continue
            findOfType(value, type, depth - 1)?.let { return it }
        }
        cls = cls.superclass
    }
    return null
}
