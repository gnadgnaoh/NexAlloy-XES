package io.github.nexalloy.revanced.instagram.time

import io.github.nexalloy.revanced.shared.time.TimeFormat
import android.graphics.Color
import android.view.View
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.instagram.download.MediaDownloadCore
import io.github.nexalloy.revanced.instagram.download.MediaResolver
import java.lang.reflect.Field
import java.lang.reflect.Modifier

private const val VIEWER_CONFIG = "com.instagram.clips.intf.ClipsViewerConfig"
private const val USER_SESSION = "com.instagram.common.session.UserSession"

val ShowReelTime = patch(
    name = "Show a reel's exact time",
    description = "Adds the date and time a reel was posted under the author's name, like Oct 2, 3:45 PM.",
) {
    dependsOn(MediaDownloadCore)
    val render = ::reelTitleRenderFingerprint.method
    val title = render.declaringClass
    val textClass = ::reelSubtitleTextClassFingerprint.clazz
    val takenAt = ::mediaTakenAtGetterFingerprint.method.apply { isAccessible = true }
    val ctor = textClass.declaredConstructors.single().apply { isAccessible = true }
    val takes = ctor.parameterTypes
    check(takes.size == 9 && takes[0] == View.OnClickListener::class.java && takes[1].name == VIEWER_CONFIG &&
        takes[2].name == USER_SESSION && takes[4] == CharSequence::class.java && takes[7] == Int::class.javaPrimitiveType) {
        "reel subtitle text changed shape: ${takes.map { it.name }}"
    }
    val colorsCtor = takes[3].declaredConstructors.firstOrNull { c ->
        c.parameterTypes.size == 2 && c.parameterTypes[0].name == USER_SESSION && c.parameterTypes[1] == Int::class.javaPrimitiveType
    }?.apply { isAccessible = true } ?: error("no reel subtitle colors constructor")
    val config = title.only(takes[1])
    val session = title.only(takes[2])
    val ctx = appContext
    val noClick = View.OnClickListener { }
    val colorId = ctx.resources.getIdentifier("bds_black", "color", ctx.packageName)
    val color = runCatching { ctx.getColor(colorId) }.getOrDefault(Color.BLACK)

    render.hookMethod {
        after { param ->
            runCatching {
                val parts = param.result?.let(::partsOf) ?: return@runCatching
                val media = reelMedia(param.thisObject, setOf(takes[1], takes[2])) ?: return@runCatching
                val seconds = takenAt.invoke(media) as? Long ?: return@runCatching
                if (seconds <= 0) return@runCatching
                val s = session.get(param.thisObject)
                val line = ctor.newInstance(
                    noClick, config.get(param.thisObject), s, colorsCtor.newInstance(s, color),
                    TimeFormat.exact(ctx, seconds * 1000), null, null, 0, false,
                )
                parts.add(line)
            }
        }
    }
}

@Volatile
private var mediaPath: List<Field>? = null

private fun reelMedia(title: Any, skip: Set<Class<*>>): Any? {
    mediaPath?.let { path -> return path.fold(title as Any?) { obj, f -> obj?.let { f.get(it) } } }
    val mediaType = MediaResolver.mediaType ?: return null
    fun fields(c: Class<*>) = c.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive && it.type !in skip }
        .onEach { it.isAccessible = true }
    for (a in fields(title.javaClass)) {
        val reel = runCatching { a.get(title) }.getOrNull() ?: continue
        if (!reel.javaClass.name.startsWith("X.")) continue
        for (b in fields(reel.javaClass)) {
            val item = runCatching { b.get(reel) }.getOrNull() ?: continue
            val medias = fields(item.javaClass).filter { it.type == mediaType }
            if (medias.size != 1) continue
            val media = runCatching { medias[0].get(item) }.getOrNull() ?: continue
            mediaPath = listOf(a, b, medias[0])
            return media
        }
    }
    return null
}

private fun Class<*>.only(type: Class<*>): Field =
    declaredFields.single { it.type == type && !Modifier.isStatic(it.modifiers) }.apply { isAccessible = true }

@Suppress("UNCHECKED_CAST")
private fun partsOf(column: Any): MutableList<Any?>? {
    var c: Class<*>? = column.javaClass
    while (c != null && c != Any::class.java) {
        val lists = c.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.type == List::class.java }
        if (lists.size == 1) {
            val list = runCatching { lists[0].isAccessible = true; lists[0].get(column) }.getOrNull()
            return (list as? java.util.ArrayList<Any?>)
        }
        c = c.superclass
    }
    return null
}
