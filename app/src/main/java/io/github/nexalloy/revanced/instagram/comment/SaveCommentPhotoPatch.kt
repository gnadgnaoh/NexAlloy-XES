package io.github.nexalloy.revanced.instagram.comment

import android.content.Context
import io.github.nexalloy.R
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.instagram.download.I18n
import io.github.nexalloy.revanced.instagram.download.MediaDownloadCore
import io.github.nexalloy.revanced.instagram.download.MediaResolver
import io.github.nexalloy.revanced.instagram.download.MediaSaver
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

private const val GIPHY = "com.instagram.api.schemas.CommentGiphyMediaInfoIntf"
private const val FUNCTION0 = "kotlin.jvm.functions.Function0"
private const val SAVE_ACTION = "SaveMedia"
private const val GIF_NODES = 120

val SaveCommentPhoto = patch(
    name = "Save comment photo",
    description = "Adds Save to the menu you get when you hold a comment that has its own photo or GIF. " +
        "It saves the largest size Instagram sent; a GIF keeps its own format.",
) {
    dependsOn(MediaDownloadCore)
    val renderer = ::commentMenuRendererFingerprint.method
    val infoGetter = ::commentMediaInfoGetterFingerprint.method.apply { isAccessible = true }
    val rawType = infoGetter.declaringClass
    val gifGetter = rawType.methods.single { it.parameterCount == 0 && it.returnType.name == GIPHY }
    val mediaGetter = infoGetter.returnType.methods.single { it.parameterCount == 0 && it.returnType == MediaResolver.mediaType }
    val selectedType = renderer.parameterTypes[1]
    val rawField = selectedType.declaredFields.single { it.type == rawType }.apply { isAccessible = true }

    val save = ::menuActionSingletonsFingerprint.fieldList.firstNotNullOfOrNull { f ->
        runCatching { f.isAccessible = true; f.get(null) }.getOrNull()?.takeIf { it.toString() == SAVE_ACTION }
    } ?: error("no $SAVE_ACTION menu action")
    val res = appContext.resources
    val ids = generateSequence<Class<*>>(save.javaClass) { it.superclass }.flatMap { it.declaredFields.asSequence() }
        .filter { it.type == Int::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers) }
        .map { it.isAccessible = true; it.getInt(save) }.toList()
    fun isDrawable(id: Int) = runCatching { res.getResourceTypeName(id) == "drawable" }.getOrDefault(false) ||
        runCatching { android.util.TypedValue().also { res.getValue(id, it, true) }.string?.startsWith("res/") == true }.getOrDefault(false)
    val icon = ids.firstOrNull(::isDrawable) ?: error("no Save icon")
    val label = ids.firstOrNull { it != icon && it ushr 24 == 0x7f } ?: error("no Save label")

    val function0 = classLoader.loadClass(FUNCTION0)
    val unit = runCatching { classLoader.loadClass("kotlin.Unit").getField("INSTANCE").get(null) }.getOrNull()
    val userType = runCatching { classLoader.loadClass("com.instagram.user.model.User") }.getOrNull()

    fun target(ctx: Context, selected: Any): Target? {
        if (!selectedType.isInstance(selected)) return null
        val raw = rawField.get(selected) ?: return null
        runCatching { gifGetter.invoke(raw) }.getOrNull()?.let { gif -> return gifUrl(gif)?.let { Target(it, true) } }
        val info = runCatching { infoGetter.invoke(raw) }.getOrNull() ?: return null
        val media = runCatching { mediaGetter.invoke(info) }.getOrNull() ?: return null
        val item = MediaResolver.single(ctx, media) ?: return null
        return Target(item.url, false, item)
    }

    renderer.hookMethod {
        before { param ->
            runCatching {
                val rows = param.args[3] as? List<*> ?: return@runCatching
                val selected = param.args[1] ?: return@runCatching
                val ctx = param.args[0]?.let { f -> f.javaClass.getMethod("requireContext").invoke(f) as? Context } ?: return@runCatching
                val found = target(ctx, selected) ?: return@runCatching
                val maker = RowMaker.of(rows, function0) ?: return@runCatching
                val author = userType?.let { MediaResolver.usernameOfUser(MediaResolver.fieldOfType(selected, it)) }
                val callback = Proxy.newProxyInstance(classLoader, arrayOf(function0)) { proxy, m, args ->
                    when (m.name) {
                        "invoke" -> { saveTarget(ctx, found, author); unit }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.getOrNull(0)
                        "toString" -> "NexAlloySaveCommentPhoto"
                        else -> null
                    }
                }
                val row = maker.make(rows, icon, label, callback) ?: return@runCatching
                param.args[3] = ArrayList<Any?>(rows.size + 1).apply { addAll(rows); add(row) }
            }
        }
    }
}

private class Target(val url: String, val gif: Boolean, val item: io.github.nexalloy.revanced.instagram.download.MediaItem? = null)

private fun saveTarget(ctx: Context, target: Target, author: String?) {
    val item = target.item
    if (!target.gif && item != null) {
        MediaSaver.saveAll(
            ctx, listOf(MediaSaver.Job(item, author, "comment", null)),
            I18n.t(ctx, if (item.isVideo) R.string.ig_toast_downloading_video else R.string.ig_toast_downloading_photo),
            I18n.t(ctx, if (item.isVideo) R.string.ig_toast_video_saved else R.string.ig_toast_photo_saved),
        )
        return
    }
    MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_downloading_photo))
    MediaSaver.executor.submit {
        val name = "${author ?: "unknown"}_comment_${System.currentTimeMillis()}"
        runCatching { MediaSaver.saveAnimated(ctx, target.url, name, author) }
            .onSuccess { MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_photo_saved)) }
            .onFailure { MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_download_failed, it.message ?: it.javaClass.simpleName)) }
    }
}

private fun gifUrl(root: Any): String? {
    val urls = LinkedHashSet<String>()
    val seen = java.util.IdentityHashMap<Any, Boolean>()
    val queue = ArrayDeque<Pair<Any, Int>>().apply { add(root to 0) }
    var visited = 0
    while (queue.isNotEmpty() && visited++ < GIF_NODES) {
        val (obj, depth) = queue.removeFirst()
        if (seen.put(obj, true) != null) continue
        for (getter in modelGetters(obj.javaClass)) {
            val value = runCatching { getter.invoke(obj) }.getOrNull() ?: continue
            when {
                value is String -> if (MediaSaver.isCdnMediaUrl(value)) urls.add(value)
                depth < 3 && !value.javaClass.isPrimitiveLike() -> queue.add(value to depth + 1)
            }
        }
    }
    fun pick(ext: String) = urls.firstOrNull { it.substringBefore('?').lowercase().endsWith(ext) }
    return pick(".gif") ?: pick(".webp") ?: pick(".mp4") ?: urls.firstOrNull()
}

private val getterCache = HashMap<Class<*>, List<Method>>()

private fun modelGetters(cls: Class<*>): List<Method> = synchronized(getterCache) {
    getterCache.getOrPut(cls) {
        cls.interfaces.flatMap { it.methods.asList() }.filter { m ->
            m.parameterCount == 0 && !Modifier.isStatic(m.modifiers) && m.returnType != Void.TYPE &&
                (m.returnType == String::class.java || m.returnType.isInterface && m.returnType.name.startsWith("com.instagram."))
        }.distinctBy { it.name + it.returnType.name }
    }
}

private fun Class<*>.isPrimitiveLike() = isPrimitive || name.startsWith("java.") || name.startsWith("kotlin.")

private class RowMaker(private val base: Class<*>, private val types: Array<Class<*>>, private val fields: List<java.lang.reflect.Field>) {
    fun make(rows: List<*>, icon: Int, label: Int, callback: Any): Any? = runCatching {
        val own = rows.filterNotNull().filter { base.isInstance(it) }
        val styles = own.map { fields[0].get(it) }
        val style = styles.firstOrNull { it != null && plainStyle(it) }
            ?: styles.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val template = own.firstOrNull { fields[0].get(it) == style } ?: own.first()
        val row = allocate(template.javaClass)
        fields[0].set(row, style)
        fields[1].set(row, types[1].getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(icon))
        fields[2].set(row, types[2].getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(label))
        fields[3].set(row, callback)
        row
    }.onFailure { app.morphe.extension.shared.Logger.printException({ "Instagram: comment Save row" }, it) }.getOrNull()

    companion object {
        private val cache = HashMap<Class<*>, RowMaker?>()

        private fun plainStyle(style: Any): Boolean {
            var c: Class<*>? = style.javaClass
            val colors = ArrayList<java.lang.reflect.Field>()
            while (c != null && c != Any::class.java) {
                colors += c.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.type == Integer::class.java }
                c = c.superclass
            }
            if (colors.isEmpty()) return false
            return colors.all { f -> runCatching { f.isAccessible = true; f.get(style) == null }.getOrDefault(false) }
        }

        private val unsafe: Any by lazy {
            Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)!!
        }

        private fun allocate(type: Class<*>): Any = runCatching { type.getDeclaredConstructor().apply { isAccessible = true }.newInstance() }
            .getOrElse { unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type)!! }

        fun of(rows: List<*>, function0: Class<*>): RowMaker? {
            val sample = rows.firstOrNull { it != null } ?: return null
            return synchronized(cache) {
                cache.getOrPut(sample.javaClass) {
                    generateSequence<Class<*>>(sample.javaClass) { it.superclass }.firstNotNullOfOrNull { c ->
                        val ctor = c.declaredConstructors.firstOrNull { k ->
                            k.parameterTypes.size == 4 && k.parameterTypes[3] == function0
                        } ?: return@firstNotNullOfOrNull null
                        val types = ctor.parameterTypes
                        val own = c.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
                        val fields = types.map { t -> own.singleOrNull { it.type == t } ?: return@firstNotNullOfOrNull null }
                        fields.forEach { it.isAccessible = true }
                        RowMaker(c, types, fields)
                    }
                }
            }
        }
    }
}
