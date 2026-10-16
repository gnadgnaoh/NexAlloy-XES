package io.github.nexalloy.revanced.instagram.download

import android.content.Context
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/** One downloadable file. */
internal class MediaItem(
    @JvmField val url: String,
    @JvmField val isVideo: Boolean,
)

/**
 * Resolves URLs, author and id from an Instagram `Media`.
 *
 * Everything goes through getters bound once from DexKit fingerprints (video_versions,
 * carousel_media, image_versions2, author). The reflective field scan is only a last resort,
 * reads fields (never invokes arbitrary getters, which on 451's Pando models can hit JNI) and
 * is depth-limited. Callers should run resolution off the main thread.
 */
internal object MediaResolver {

    private var mediaClass: Class<*>? = null
    private var reelItemClass: Class<*>? = null
    private var userClass: Class<*>? = null
    private var imageUrlClass: Class<*>? = null
    private var extendedImageUrlClass: Class<*>? = null

    private val videoVersionsGetters = ArrayList<Method>()
    private var carouselGetter: Method? = null
    private var isVideoMethod: Method? = null
    private val imageInfoGetters = ArrayList<Method>()
    private val screenImageGetters = ArrayList<Method>()
    private var authorGetter: Method? = null

    private var videoUrlClass: Class<*>? = null
    private var videoUrlField: Field? = null
    private var videoUrlIntFields: List<Field> = emptyList()

    // region binding

    fun bindClasses(classLoader: ClassLoader) {
        mediaClass = load(classLoader, MEDIA_CLASS)
        reelItemClass = load(classLoader, REEL_ITEM_CLASS)
        imageUrlClass = load(classLoader, IMAGE_URL_CLASS)
        extendedImageUrlClass = load(classLoader, EXTENDED_IMAGE_URL_CLASS)
        if (videoUrlClass == null) videoUrlClass = load(classLoader, VIDEO_URL_CLASS)
    }

    fun bindVideoUrlModel(modelClass: Class<*>, urlField: Field) {
        urlField.isAccessible = true
        videoUrlClass = modelClass
        videoUrlField = urlField
        val ints = ArrayList<Field>()
        var c: Class<*>? = modelClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.type == Int::class.javaPrimitiveType && !Modifier.isStatic(f.modifiers)) {
                    f.isAccessible = true
                    ints.add(f)
                }
            }
            c = c.superclass
        }
        videoUrlIntFields = ints
    }

    fun bindVideoVersionsGetters(getters: List<Method>) = bindAll(videoVersionsGetters, getters) {
        List::class.java.isAssignableFrom(it.returnType) && it.parameterCount == 0
    }

    fun bindCarouselGetter(candidates: List<Method>) {
        carouselGetter = candidates.firstOrNull {
            List::class.java.isAssignableFrom(it.returnType) && it.parameterCount == 0
        }?.apply { isAccessible = true }
    }

    fun bindIsVideoMethod(method: Method?) {
        isVideoMethod = method?.apply { isAccessible = true }
    }

    fun bindImageInfoGetters(getters: List<Method>) = bindAll(imageInfoGetters, getters) { true }

    fun bindScreenImageGetters(getters: List<Method>) = bindAll(screenImageGetters, getters) {
        Modifier.isStatic(it.modifiers) && it.parameterCount == 2
    }

    fun bindUserClass(cls: Class<*>?) {
        userClass = cls
    }

    fun bindAuthorGetter(method: Method?) {
        authorGetter = method?.apply { isAccessible = true }
    }

    private inline fun bindAll(
        target: MutableList<Method>,
        source: List<Method>,
        accept: (Method) -> Boolean,
    ) {
        target.clear()
        for (m in source) {
            if (!accept(m) || target.contains(m)) continue
            runCatching { m.isAccessible = true }
            target.add(m)
        }
    }

    fun describe(): String =
        "videoVersions=${videoVersionsGetters.size} carousel=${carouselGetter?.name} " +
            "isVideo=${isVideoMethod?.name} imageInfo=${imageInfoGetters.size} " +
            "screenImage=${screenImageGetters.size} author=${authorGetter?.name} " +
            "videoUrlField=${videoUrlField?.name}"

    // endregion

    // region public API

    fun isMedia(obj: Any?): Boolean = obj != null && mediaClass?.isInstance(obj) == true

    fun isReelItem(obj: Any?): Boolean = obj != null && reelItemClass?.isInstance(obj) == true

    val reelItemType: Class<*>? get() = reelItemClass

    val mediaType: Class<*>? get() = mediaClass

    val imageUrlType: Class<*>? get() = imageUrlClass

    /** Every slide of a carousel, or the single item of a plain post. */
    fun items(ctx: Context?, media: Any?): List<MediaItem> {
        if (media == null) return emptyList()
        val children = carouselChildren(media)
        if (children.size >= 2) {
            val out = ArrayList<MediaItem>(children.size)
            for (child in children) single(ctx, child)?.let { out.add(it) }
            if (out.isNotEmpty()) return out
        }
        return listOfNotNull(single(ctx, media))
    }

    /** The video if the media is a video, else its largest image. */
    fun single(ctx: Context?, media: Any?): MediaItem? {
        if (media == null) return null
        val video = videoUrl(media)
        if (video != null) return MediaItem(video, true)
        if (isVideo(media)) {
            ModuleLog.line("(NA|Media) video without a resolvable URL; not falling back to cover")
            return null
        }
        return imageUrl(ctx, media)?.let { MediaItem(it, false) }
    }

    fun isVideo(media: Any?): Boolean {
        val m = isVideoMethod ?: return false
        if (media == null || !m.declaringClass.isInstance(media)) return false
        return runCatching { m.invoke(media) == true }.getOrDefault(false)
    }

    /** Highest-resolution entry of `video_versions`. */
    fun videoUrl(media: Any?): String? {
        if (media == null) return null
        for (getter in videoVersionsGetters) {
            if (!getter.declaringClass.isInstance(media)) continue
            val list = runCatching { getter.invoke(media) as? List<*> }.getOrNull() ?: continue
            bestVideoVersion(list)?.let { return it }
        }
        // Photos have an empty video_versions; only scan when the model says it is a video.
        if (isVideoMethod != null && !isVideo(media)) return null
        return scanForVideoUrl(media)
    }

    /** Largest candidate of `image_versions2`, then the screen-sized url, then a field scan. */
    fun imageUrl(ctx: Context?, media: Any?): String? {
        if (media == null) return null
        var best: Candidate? = null
        for (getter in imageInfoGetters) {
            val info = invokeGetter(getter, media) ?: continue
            best = maxOf(best, bestImageCandidate(info))
            if (best != null) break
        }
        if (best == null && ctx != null) {
            for (getter in screenImageGetters) {
                val r = runCatching { getter.invoke(null, ctx, media) }.getOrNull() ?: continue
                val url = if (r is String) r else imageUrlOf(r)
                if (url != null && isUsableUrl(url)) {
                    best = Candidate(url, 0)
                    break
                }
            }
        }
        if (best == null) best = scanForImage(media)
        return best?.url
    }

    fun carouselChildren(media: Any?): List<Any> {
        val g = carouselGetter ?: return emptyList()
        if (media == null || !g.declaringClass.isInstance(media)) return emptyList()
        val list = runCatching { g.invoke(media) as? List<*> }.getOrNull() ?: return emptyList()
        return list.filterNotNull()
    }

    fun username(media: Any?): String? {
        val g = authorGetter ?: return null
        if (media == null || !g.declaringClass.isInstance(media)) return null
        val user = runCatching { g.invoke(media) }.getOrNull() ?: return null
        return UserUtils.callUsernameGetter(user)
    }

    fun usernameOfUser(user: Any?): String? =
        if (user != null && (userClass == null || userClass!!.isInstance(user))) {
            UserUtils.callUsernameGetter(user)
        } else {
            null
        }

    /** `Media.getId()` is "<pk>_<owner>"; keep the pk. */
    fun mediaId(media: Any?): String? {
        if (media == null) return null
        val id = runCatching { media.javaClass.getMethod("getId").invoke(media) }.getOrNull()
        return (id as? String)?.takeIf { it.isNotEmpty() }?.substringBefore('_')
    }

    /** Media held by a ReelItem (story frame). */
    fun mediaOfReelItem(reelItem: Any?): Any? {
        if (reelItem == null) return null
        for (m in getterCache(reelItem.javaClass, mediaClass)) {
            val v = runCatching { m.invoke(reelItem) }.getOrNull()
            if (isMedia(v)) return v
        }
        return fieldOfType(reelItem, mediaClass)
    }

    fun usernameOfReelItem(reelItem: Any?): String? {
        if (reelItem == null) return null
        for (m in getterCache(reelItem.javaClass, userClass)) {
            val name = usernameOfUser(runCatching { m.invoke(reelItem) }.getOrNull())
            if (name != null) return name
        }
        return null
    }

    /** First non-null field of exactly [type] on [obj] or its superclasses. */
    fun fieldOfType(obj: Any?, type: Class<*>?): Any? {
        if (obj == null || type == null) return null
        for (f in fieldsOf(obj.javaClass)) {
            if (!type.isAssignableFrom(f.type)) continue
            val v = runCatching { f.get(obj) }.getOrNull()
            if (v != null) return v
        }
        return null
    }

    /** Breadth-first search for a Media through app model fields, at most [maxDepth] hops. */
    fun findMedia(root: Any?, maxDepth: Int = 2): Any? {
        if (root == null) return null
        if (isMedia(root)) return root
        val visited: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        var level = listOf(root)
        for (depth in 0..maxDepth) {
            val next = ArrayList<Any>()
            for (obj in level) {
                if (!visited.add(obj)) continue
                for (f in fieldsOf(obj.javaClass)) {
                    if (f.type.isPrimitive) continue
                    val v = runCatching { f.get(obj) }.getOrNull() ?: continue
                    if (isMedia(v)) return v
                    if (depth < maxDepth && isAppModel(v.javaClass.name)) next.add(v)
                }
            }
            level = next
        }
        return null
    }

    // endregion

    // region internals

    private class Candidate(@JvmField val url: String, @JvmField val area: Long)

    private fun maxOf(a: Candidate?, b: Candidate?): Candidate? = when {
        a == null -> b
        b == null -> a
        b.area > a.area -> b
        else -> a
    }

    private fun bestVideoVersion(list: List<*>): String? {
        var bestUrl: String? = null
        var bestScore = -1L
        for (item in list) {
            if (item == null) continue
            val url = videoUrlOf(item) ?: continue
            val score = videoScore(item)
            if (score > bestScore) {
                bestScore = score
                bestUrl = url
            }
        }
        return bestUrl
    }

    private fun videoUrlOf(item: Any): String? {
        val f = videoUrlField
        if (f != null && f.declaringClass.isInstance(item)) {
            val v = runCatching { f.get(item) as? String }.getOrNull()
            if (v != null && isUsableUrl(v)) return v
        }
        return stringFieldUrl(item)
    }

    /** width + height; the small type code (101..103) is ignored. */
    private fun videoScore(item: Any): Long {
        val fields = if (videoUrlClass?.isInstance(item) == true) videoUrlIntFields else emptyList()
        var score = 0L
        for (f in fields) {
            val v = runCatching { f.getInt(item) }.getOrDefault(0)
            if (v >= 160) score += v
        }
        return score
    }

    private fun bestImageCandidate(info: Any): Candidate? {
        var best: Candidate? = null
        for (m in getterCache(info.javaClass, null)) {
            val r = runCatching { m.invoke(info) }.getOrNull() ?: continue
            if (r is List<*>) {
                for (e in r) best = maxOf(best, imageCandidate(e))
            } else if (isAppModel(r.javaClass.name)) {
                // AdditionalCandidates: a few ExtendedImageUrl getters.
                for (sub in getterCache(r.javaClass, extendedImageUrlClass ?: imageUrlClass)) {
                    best = maxOf(best, imageCandidate(runCatching { sub.invoke(r) }.getOrNull()))
                }
                best = maxOf(best, imageCandidate(r))
            }
        }
        return best
    }

    /** getUrl / getWidth / getHeight of an image-url model, looked up once per class. */
    private class ImageAccessors(val url: Method, val width: Method?, val height: Method?)

    private val imageAccessorCache = ConcurrentHashMap<Class<*>, Any>()
    private val NONE = Any()

    private fun imageAccessors(cls: Class<*>): ImageAccessors? {
        val cached = imageAccessorCache.getOrPut(cls) {
            if (!isAppModel(cls.name)) return@getOrPut NONE
            val url = runCatching { cls.getMethod("getUrl") }.getOrNull()
                ?.takeIf { it.returnType == String::class.java } ?: return@getOrPut NONE
            ImageAccessors(
                url,
                runCatching { cls.getMethod("getWidth") }.getOrNull()?.takeIf { it.returnType == Int::class.javaPrimitiveType },
                runCatching { cls.getMethod("getHeight") }.getOrNull()?.takeIf { it.returnType == Int::class.javaPrimitiveType },
            )
        }
        return cached as? ImageAccessors
    }

    private fun imageCandidate(obj: Any?): Candidate? {
        if (obj == null) return null
        if (videoUrlClass?.isInstance(obj) == true) return null
        val acc = imageAccessors(obj.javaClass) ?: return null
        val url = runCatching { acc.url.invoke(obj) as? String }.getOrNull() ?: return null
        if (!isUsableUrl(url) || MediaSaver.looksLikeVideoUrl(url)) return null
        val w = runCatching { acc.width?.invoke(obj) as? Int }.getOrNull() ?: 0
        val h = runCatching { acc.height?.invoke(obj) as? Int }.getOrNull() ?: 0
        return Candidate(url, w.toLong() * h.toLong())
    }

    fun imageUrlOf(obj: Any?): String? {
        if (obj == null) return null
        val acc = imageAccessors(obj.javaClass)
            ?: return runCatching { obj.javaClass.getMethod("getUrl").invoke(obj) as? String }.getOrNull()
        return runCatching { acc.url.invoke(obj) as? String }.getOrNull()
    }

    /** Width × height of an image-url model, 0 when unknown. */
    fun imageAreaOf(obj: Any?): Long {
        val acc = obj?.let { imageAccessors(it.javaClass) } ?: return 0
        val w = runCatching { acc.width?.invoke(obj) as? Int }.getOrNull() ?: 0
        val h = runCatching { acc.height?.invoke(obj) as? Int }.getOrNull() ?: 0
        return w.toLong() * h.toLong()
    }

    /** Video fallback: VideoUrl objects reachable through fields, depth 2. */
    private fun scanForVideoUrl(media: Any): String? {
        val type = videoUrlClass ?: return null
        val found = ArrayList<Any>()
        walkFields(media, 2) { v ->
            if (type.isInstance(v)) found.add(v)
            else if (v is List<*>) v.forEach { if (it != null && type.isInstance(it)) found.add(it) }
            found.size >= 16
        }
        return bestVideoVersion(found)
    }

    /** Image fallback: ImageUrl objects reachable through fields, depth 1. */
    private fun scanForImage(media: Any): Candidate? {
        var best: Candidate? = null
        walkFields(media, 1) { v ->
            best = maxOf(best, imageCandidate(v))
            if (v is List<*>) v.forEach { best = maxOf(best, imageCandidate(it)) }
            false
        }
        return best
    }

    private fun walkFields(root: Any, maxDepth: Int, visit: (Any) -> Boolean) {
        val visited: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        var level = listOf(root)
        for (depth in 0..maxDepth) {
            val next = ArrayList<Any>()
            for (obj in level) {
                if (!visited.add(obj)) continue
                for (f in fieldsOf(obj.javaClass)) {
                    if (f.type.isPrimitive) continue
                    val v = runCatching { f.get(obj) }.getOrNull() ?: continue
                    if (visit(v)) return
                    if (depth < maxDepth && isAppModel(v.javaClass.name)) next.add(v)
                }
            }
            level = next
        }
    }

    private fun stringFieldUrl(item: Any): String? {
        for (f in fieldsOf(item.javaClass)) {
            if (f.type != String::class.java) continue
            val v = runCatching { f.get(item) as? String }.getOrNull()
            if (v != null && isUsableUrl(v)) return v
        }
        return null
    }

    private fun invokeGetter(getter: Method, media: Any): Any? = runCatching {
        when {
            Modifier.isStatic(getter.modifiers) -> getter.invoke(null, media)
            getter.declaringClass.isInstance(media) -> getter.invoke(media)
            else -> null
        }
    }.getOrNull()

    private fun isUsableUrl(url: String): Boolean = MediaSaver.isCdnMediaUrl(url)

    private fun isAppModel(name: String): Boolean =
        name.startsWith("X.") || name.startsWith("com.instagram.") ||
            name.startsWith("com.facebook.")

    private val fieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

    private fun fieldsOf(cls: Class<*>): List<Field> = fieldCache.getOrPut(cls) {
        val out = ArrayList<Field>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            val n = c.name
            if (n.startsWith("java.") || n.startsWith("android.") || n.startsWith("kotlin.")) break
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                runCatching { f.isAccessible = true }.onSuccess { out.add(f) }
            }
            c = c.superclass
        }
        out
    }

    private val getterCache = ConcurrentHashMap<String, List<Method>>()

    /**
     * Public no-arg getters of [cls] returning [type] (or, when [type] is null, returning a List
     * or an app model). Only used on small model classes (ReelItem, ImageInfo).
     */
    private fun getterCache(cls: Class<*>, type: Class<*>?): List<Method> =
        getterCache.getOrPut(cls.name + '|' + (type?.name ?: "*")) {
            cls.methods.filter { m ->
                if (m.parameterCount != 0 || Modifier.isStatic(m.modifiers)) return@filter false
                val r = m.returnType
                if (type != null) {
                    type.isAssignableFrom(r)
                } else {
                    List::class.java.isAssignableFrom(r) ||
                        // AdditionalCandidates only; SpriteSheetInfoCandidates are scrubber tiles.
                        (!r.isPrimitive && r.name.endsWith(".AdditionalCandidates"))
                }
            }.onEach { runCatching { it.isAccessible = true } }
        }

    private fun load(cl: ClassLoader, name: String): Class<*>? =
        runCatching { cl.loadClass(name) }.getOrNull()

    // endregion
}
