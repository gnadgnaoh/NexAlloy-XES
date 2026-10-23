package io.github.nexalloy.revanced.threads.download

import android.app.Activity
import android.content.Context
import app.morphe.extension.shared.Logger
import io.github.nexalloy.R
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.instagram.download.DownloadUi
import io.github.nexalloy.revanced.threads.tracking.mediaCodeFingerprint
import io.github.nexalloy.revanced.threads.tracking.mediaUserFingerprint
import io.github.nexalloy.revanced.threads.tracking.userNameFingerprint
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

val SaveVideos = patch(
    name = "Save videos",
    description = "Adds Save video to a post's menu when the post has a video, and to the menu of a video you open. " +
        "A post with several videos lets you pick one or save them all. Videos go to Movies/Threads at the best quality.",
) {
    val mediaClass = classLoader.loadClass(MEDIA)
    val row = ::menuRowFingerprint.method.apply { isAccessible = true }
    val click = ::menuClickModifierFingerprint.method.apply { isAccessible = true }
    val role = ::menuRoleFingerprint.method.apply { isAccessible = true }
    val base = ::menuBaseModifierFingerprint.field.apply { isAccessible = true }
    val postIcon = ::menuRowIconFingerprint.method.apply { isAccessible = true }
    val mediaIcon = ::mediaMenuIconFingerprint.method.apply { isAccessible = true }
    val close = runCatching { ::postMenuCloseFingerprint.method.apply { isAccessible = true } }.getOrNull()
    val function0 = classLoader.loadClass("kotlin.jvm.functions.Function0")

    val reader = ThreadsMedia(
        videoVersions = ::mediaVideoVersionsFingerprint.method,
        manifest = ::mediaDashManifestFingerprint.method,
        carousel = ::mediaCarouselFingerprint.method,
        url = ::versionUrlFingerprint.method,
        width = ::versionWidthFingerprint.method,
        height = ::versionHeightFingerprint.method,
        code = runCatching { ::mediaCodeFingerprint.method }.getOrNull(),
        user = runCatching { ::mediaUserFingerprint.method }.getOrNull(),
        username = runCatching { ::userNameFingerprint.method }.getOrNull(),
    )

    val res = appContext.resources
    val pkg = appContext.packageName
    val linkIcon = res.getIdentifier("threads_icons_cotton_link_outline_24", "drawable", pkg)
    val postSaveIcon = res.getIdentifier("threads_icons_cotton_arrow_download_outline_24", "drawable", pkg)
    val mediaSaveIcon = res.getIdentifier("instagram_download_outline_24", "drawable", pkg)
    check(linkIcon != 0 && postSaveIcon != 0 && mediaSaveIcon != 0) { "Threads menu icons not found" }

    class Scope(val owner: Any, val post: Boolean) {
        var done = false
    }

    val scope = ThreadLocal<Scope?>()
    val icons = Collections.synchronizedMap(WeakHashMap<Any, Int>())

    ::postMenuRowsFingerprint.hookMethod {
        before { scope.set(Scope(it.thisObject, true)) }
        after { scope.remove() }
    }
    ::mediaMenuRowsFingerprint.hookMethod {
        before { scope.set(Scope(it.thisObject, false)) }
        after { scope.remove() }
    }
    listOf(::menuRowIconFingerprint, ::mediaMenuIconFingerprint).forEach { icon ->
        icon.hookMethod {
            after { param ->
                if (scope.get() == null) return@after
                val result = param.result ?: return@after
                (param.args[1] as? Int)?.let { icons[result] = it }
            }
        }
    }

    fun clickOf(action: () -> Unit): Any = Proxy.newProxyInstance(classLoader, arrayOf(function0)) { proxy, method, args ->
        when (method.name) {
            "invoke" -> {
                runCatching(action).onFailure { Logger.printException({ "Threads: save click failed" }, it) }
                null
            }
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "NexAlloySaveVideo"
            else -> null
        }
    }

    fun drawRow(composer: Any, style: Any?, icon: Any?, label: String, action: () -> Unit) {
        val modifier = click.invoke(null, base.get(null), role.invoke(null, 0), clickOf(action), null, true)
        row.invoke(null, composer, modifier, style, icon, label)
    }

    val mediaCache = classLoader.loadClass("com.instagram.feed.media.MediaCache")
    val cacheGet = mediaCache.getMethod("get", String::class.java)
    val stateClass = classLoader.loadClass("androidx.compose.runtime.MutableState")
    runCatching {
        ::mediaViewerTopBarInitFingerprint.hookMethod {
            before { param ->
                if (param.args[9] == true) return@before
                val open = param.args[2] ?: return@before
                runCatching {
                    val postId = fieldOfType(open, String::class.java) as? String ?: return@runCatching
                    val cache = open.javaClass.declaredFields.asSequence()
                        .filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
                        .mapNotNull { f -> f.isAccessible = true; f.get(open)?.let { fieldOfType(it, mediaCache) } }
                        .firstOrNull() ?: return@runCatching
                    val post = cacheGet.invoke(cache, postId) ?: return@runCatching
                    val index = (fieldOfType(open, stateClass)?.let { stateClass.getMethod("getValue").invoke(it) } as? Number)?.toInt() ?: 0
                    val pages = reader.pages(post)
                    val page = pages.getOrNull(index) ?: pages.first()
                    if (reader.videoPages(page).isEmpty()) return@runCatching
                    val activity = fieldOfType(open, Activity::class.java) as? Activity
                    val number = if (pages.size > 1) pages.indexOf(page) + 1 else null
                    param.args[9] = true
                    param.args[2] = clickOf {
                        val ctx = activity ?: appContext
                        DownloadUi.showSheet(
                            ctx, ThreadsSaver.t(ctx, R.string.th_save_video), null,
                            listOf(DownloadUi.Row(ThreadsSaver.t(ctx, R.string.th_save_video), primary = true) {
                                ThreadsSaver.save(ctx, listOf(reader.video(page, post, number)))
                            }),
                            cancel = true,
                        )
                    }
                }.onFailure { Logger.printException({ "Threads: couldn't add the video menu" }, it) }
            }
        }
    }.onFailure { Logger.printInfo { "Threads: video viewer menu not found: $it" } }

    ::menuRowFingerprint.hookMethod {
        after { param ->
            val current = scope.get() ?: return@after
            if (current.done) return@after
            val composer = param.args[0] ?: return@after
            val iconId = param.args[3]?.let { icons[it] }
            runCatching {
                if (current.post) {
                    if (iconId != linkIcon) return@runCatching
                    current.done = true
                    val media = fieldOfType(current.owner, mediaClass) ?: return@runCatching
                    if (reader.videoPages(media).isEmpty()) return@runCatching
                    val activity = fieldOfType(current.owner, Activity::class.java) as? Activity
                    val label = ThreadsSaver.t(appContext, R.string.th_save_video)
                    val icon = postIcon.invoke(null, composer, postSaveIcon, 2, false)
                    drawRow(composer, param.args[2], icon, label) {
                        close?.let { m -> fieldOfType(current.owner, m.declaringClass)?.let { m.invoke(it) } }
                        offer(activity ?: appContext, reader, media)
                    }
                } else {
                    current.done = true
                    val media = deepMedia(current.owner, mediaClass) ?: return@runCatching
                    if (reader.videoPages(media).isEmpty()) return@runCatching
                    val context = fieldOfType(current.owner, Context::class.java) as? Context ?: appContext
                    val label = ThreadsSaver.t(appContext, R.string.th_save_video)
                    val icon = mediaIcon.invoke(null, composer, mediaSaveIcon)
                    drawRow(composer, param.args[2], icon, label) { offer(context, reader, media) }
                }
            }.onFailure { Logger.printException({ "Threads: couldn't add Save video" }, it) }
        }
    }
}

private fun offer(ctx: Context, reader: ThreadsMedia, media: Any) {
    val pages = reader.pages(media)
    val videos = reader.videoPages(media)
    if (videos.isEmpty()) return ThreadsSaver.toast(ctx, ThreadsSaver.t(ctx, R.string.th_no_video))
    val single = pages.size == 1
    fun video(page: IndexedValue<Any>) = reader.video(page.value, media, if (single) null else page.index + 1)
    if (videos.size == 1) return ThreadsSaver.save(ctx, listOf(video(videos[0])))
    val rows = listOf(
        DownloadUi.Row(ThreadsSaver.t(ctx, R.string.th_sheet_all, videos.size), primary = true) {
            ThreadsSaver.save(ctx, videos.map(::video))
        },
    ) + videos.map { page ->
        DownloadUi.Row(ThreadsSaver.t(ctx, R.string.th_sheet_page, page.index + 1, pages.size)) {
            ThreadsSaver.save(ctx, listOf(video(page)))
        }
    }
    DownloadUi.showSheet(
        ctx, ThreadsSaver.t(ctx, R.string.th_save_video), ThreadsSaver.t(ctx, R.string.th_sheet_subtitle, videos.size),
        rows, cancel = true, scrollable = true,
    )
}

internal class ThreadsMedia(
    private val videoVersions: Method,
    private val manifest: Method,
    private val carousel: Method,
    private val url: Method,
    private val width: Method,
    private val height: Method,
    private val code: Method?,
    private val user: Method?,
    private val username: Method?,
) {
    private val versionMethods = java.util.concurrent.ConcurrentHashMap<Class<*>, java.util.Optional<Triple<Method, Method, Method>>>()

    private fun call(m: Method?, target: Any?): Any? = if (m == null || target == null) null else runCatching {
        m.isAccessible = true
        m.invoke(target)
    }.getOrNull()

    fun pages(media: Any): List<Any> =
        (call(carousel, media) as? List<*>)?.filterNotNull()?.takeIf { it.isNotEmpty() } ?: listOf(media)

    private fun versions(page: Any): List<ThreadsSaver.Rendition> {
        val list = call(videoVersions, page) as? List<*> ?: return emptyList()
        return list.filterNotNull().mapNotNull { v ->
            val (u, w, h) = getters(v.javaClass) ?: return@mapNotNull null
            val address = call(u, v) as? String ?: return@mapNotNull null
            ThreadsSaver.Rendition(address, (call(w, v) as? Number)?.toInt() ?: 0, (call(h, v) as? Number)?.toInt() ?: 0)
        }
    }

    private fun getters(type: Class<*>): Triple<Method, Method, Method>? = versionMethods.getOrPut(type) {
        java.util.Optional.ofNullable(runCatching {
            fun find(m: Method) = if (m.declaringClass.isAssignableFrom(type)) m
            else type.methods.first { it.name == m.name && it.parameterCount == 0 && it.returnType == m.returnType }
            Triple(find(url), find(width), find(height))
        }.getOrNull())
    }.orElse(null)

    private fun hasVideo(page: Any) = versions(page).isNotEmpty() || !(call(manifest, page) as? String).isNullOrEmpty()

    fun videoPages(media: Any): List<IndexedValue<Any>> = pages(media).withIndex().filter { hasVideo(it.value) }

    fun video(page: Any, post: Any, number: Int?): ThreadsSaver.Video {
        val owner = call(user, post) ?: call(user, page)
        val name = ThreadsSaver.fileName(call(username, owner) as? String, call(code, post) as? String, number)
        return ThreadsSaver.Video(versions(page), call(manifest, page) as? String, name)
    }
}

private val fieldCache = java.util.concurrent.ConcurrentHashMap<Pair<Class<*>, Class<*>>, java.util.Optional<Field>>()

internal fun fieldOfType(owner: Any, type: Class<*>): Any? {
    val field = fieldCache.getOrPut(owner.javaClass to type) {
        java.util.Optional.ofNullable(
            generateSequence(owner.javaClass) { it.superclass }.flatMap { it.declaredFields.asSequence() }
                .firstOrNull { !Modifier.isStatic(it.modifiers) && type.isAssignableFrom(it.type) }
                ?.apply { isAccessible = true },
        )
    }.orElse(null) ?: return null
    return field.get(owner)
}

private fun deepMedia(owner: Any, mediaClass: Class<*>): Any? {
    fieldOfType(owner, mediaClass)?.let { return it }
    owner.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }.forEach { f ->
        f.isAccessible = true
        val value = runCatching { f.get(owner) }.getOrNull() ?: return@forEach
        if (value is String || value is Context) return@forEach
        fieldOfType(value, mediaClass)?.let { return it }
    }
    return null
}
