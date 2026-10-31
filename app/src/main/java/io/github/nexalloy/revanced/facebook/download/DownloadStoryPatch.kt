package io.github.nexalloy.revanced.facebook.download

import android.content.Context
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.scopedHook

val DownloadStory = patch(
    name = "Download any story",
    description = "Adds Save to the menu of any story, including stories with music, at the best quality.",
) {
    val builder = ::storyMenuBuilderFingerprint.method
    val capability = ::storySaveCapabilityFingerprint.method
    val tap = ::storySaveTapFingerprint.method
    val contextField = tap.declaringClass.declaredFields.single { it.type == Context::class.java }.accessible()
    val cardField = tap.declaringClass.declaredFields.single { it.type.name == STORY_CARD }.accessible()
    recordPlayerSources()
    useCdnResize()

    deoptimize(builder)
    builder.hookMethod(scopedHook(capability) {
        after { param -> param.result = true }
    })

    val cache = ::storySaveCapabilityCacheFieldFingerprint.field.accessible()
    val capabilityOwner = cache.declaringClass
    builder.hookMethod {
        before { param ->
            val holder = param.args.firstOrNull { arg -> arg != null && capabilityGetter(arg.javaClass, capabilityOwner) != null }
                ?: return@before
            val target = runCatching { capabilityGetter(holder.javaClass, capabilityOwner)!!.invoke(holder) }.getOrNull() ?: return@before
            param.setObjectExtra("na_cap", target)
            param.setObjectExtra("na_old", cache.get(target))
            cache.set(target, java.lang.Boolean.TRUE)
        }
        after { param ->
            val target = param.getObjectExtra("na_cap") ?: return@after
            runCatching { cache.set(target, param.getObjectExtra("na_old")) }
        }
    }

    addSaveToEveryStoryMenu(builder, ::storyMoreMenuFingerprint.method, ::storyMenuRowClassFingerprint.clazz)

    tap.hookMethod {
        before { param ->
            val context = contextField.get(param.thisObject) as? Context ?: return@before
            val card = cardField.get(param.thisObject) ?: return@before
            if (runCatching { saveStory(context, card) }.getOrDefault(false)) param.result = null
        }
    }
}

private fun saveStory(context: Context, card: Any): Boolean {
    val media = GraphModel.callGetter(card, "getMedia")
    val from = media ?: card
    val depth = if (media != null) 1 else 2
    val urls = Reflect.walk(from, depth) { Urls.isHttp(it) && Urls.isFacebookCdn(it) }
    val source = PlayerSources.find(card)
    val manifest = source?.manifest ?: Reflect.walk(from, depth) { it.contains("<MPD") && !Urls.isHttp(it) }.firstOrNull()
    val videos = listOfNotNull(source?.hd) + urls.filter { Urls.isVideo(it) }
    if (manifest != null || videos.isNotEmpty()) return MediaSaver.saveVideo(context, source?.videoId, manifest, videos)
    resharedReel(card)?.let { reel -> return MediaSaver.saveVideo(context, reel.videoId, reel.manifest, listOfNotNull(reel.url)) }

    val photo = GraphModel.tree(media, "image")?.let { PhotoSize.story(it) ?: GraphModel.string(it, "uri") }
        ?: urls.firstOrNull { Urls.isImage(it) }
    return MediaSaver.savePhoto(context, GraphModel.string(media, "id"), photo)
}

private class ResharedReel(val manifest: String?, val url: String?, val videoId: String?)

private fun resharedReel(card: Any): ResharedReel? {
    for (tree in GraphModel.treesIn(card)) {
        val overlays = GraphModel.trees(tree, "story_overlays") ?: continue
        for (overlay in overlays) reelOf(overlay)?.let { return it }
    }
    return null
}

private fun reelOf(overlay: Any?): ResharedReel? {
    val reel = GraphModel.tree(overlay, "fb_shorts_story") ?: return null
    var url = GraphModel.string(overlay, "playable_url")?.takeIf { Urls.isHttp(it) }
    val video = GraphModel.tree(GraphModel.tree(reel, "short_form_video_context"), "video")
    var manifest = GraphModel.string(video, "playlist")?.takeIf { it.contains("<MPD") }
    var videoId = GraphModel.string(video, "id")
    GraphModel.trees(reel, "attachments")?.forEach { attachment ->
        val media = GraphModel.tree(attachment, "media")
        if (manifest == null) manifest = GraphModel.string(media, "playlist")?.takeIf { it.contains("<MPD") }
        if (url == null) url = GraphModel.string(media, "playable_url")?.takeIf { Urls.isHttp(it) }
        if (videoId == null) videoId = GraphModel.string(media, "id")
    }
    if (manifest == null && url == null) return null
    return ResharedReel(manifest, url, videoId)
}

private val capabilityGetters = HashMap<Class<*>, java.lang.reflect.Method?>()

private fun capabilityGetter(type: Class<*>, owner: Class<*>): java.lang.reflect.Method? = synchronized(capabilityGetters) {
    capabilityGetters.getOrPut(type) {
        type.methods.firstOrNull { it.parameterCount == 0 && it.returnType == owner }?.apply { isAccessible = true }
    }
}

private class MenuRun {
    var builderCalled = false
    var rows: Any? = null
}

private fun addSaveToEveryStoryMenu(builder: java.lang.reflect.Method, menu: java.lang.reflect.Method, rowClass: Class<*>) {
    builder.isAccessible = true
    val listBuilder = builder.declaringClass.classLoader!!.loadClass("com.google.common.collect.ImmutableList\$Builder")
    val add = listBuilder.declaredMethods.first { it.name == "add" && it.parameterTypes.contentEquals(arrayOf(Any::class.java)) }
    val build = listBuilder.declaredMethods.first { it.name == "build" && it.parameterCount == 0 }
    val run = ThreadLocal<MenuRun?>()
    val callbackType = builder.declaringClass
    val takes = builder.parameterTypes

    menu.hookMethod {
        before { param -> param.setObjectExtra("na_outer", run.get()); run.set(MenuRun()) }
        after { param -> run.set(param.getObjectExtra("na_outer") as? MenuRun) }
    }
    builder.hookMethod { before { run.get()?.builderCalled = true } }

    callbackType.declaredMethods.filter { m ->
        m != builder && !java.lang.reflect.Modifier.isStatic(m.modifiers) && listBuilder in m.parameterTypes
    }.forEach { adder ->
        val at = adder.parameterTypes.indexOf(listBuilder)
        adder.hookMethod { before { param -> run.get()?.let { if (it.rows == null) it.rows = param.args[at] } } }
    }
    io.github.nexalloy.revanced.shared.hook.ScopedFrameworkHook(add, before = { param ->
        val state = run.get()
        if (state != null && state.rows == null && rowClass.isInstance(param.args[0])) state.rows = param.thisObject
    }).installOn(menu)

    io.github.nexalloy.revanced.shared.hook.ScopedFrameworkHook(build, before = { param ->
        val state = run.get() ?: return@ScopedFrameworkHook
        if (state.builderCalled || state.rows !== param.thisObject) return@ScopedFrameworkHook
        state.builderCalled = true
        val outer = currentMenuArgs.get() ?: return@ScopedFrameworkHook
        val callback = outer.firstOrNull { callbackType.isInstance(it) } ?: return@ScopedFrameworkHook
        val session = outer.firstOrNull { takes[0].isInstance(it) }
        val source = outer.firstOrNull { takes[2].isInstance(it) } ?: return@ScopedFrameworkHook
        runCatching { builder.invoke(callback, session, null, source, param.thisObject) }
    }).installOn(menu)

    menu.hookMethod {
        before { param -> param.setObjectExtra("na_args", currentMenuArgs.get()); currentMenuArgs.set(param.args) }
        after { param -> currentMenuArgs.set(param.getObjectExtra("na_args") as? Array<Any?>) }
    }
}

private val currentMenuArgs = ThreadLocal<Array<Any?>?>()
