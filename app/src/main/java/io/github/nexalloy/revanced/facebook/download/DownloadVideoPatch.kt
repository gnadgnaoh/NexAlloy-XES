package io.github.nexalloy.revanced.facebook.download

import android.content.Context
import android.view.Menu
import android.view.View
import io.github.nexalloy.R
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private const val MAX_SHARE_DEPTH = 4

val DownloadVideo = patch(
    name = "Download any video",
    description = "Adds Download to phone to the menu of videos in the feed and Watch, at the best quality.",
) {
    val itemField = ::postMenuItemFieldFingerprint.field.accessible()
    val attachments = ::storyAttachmentsGetterFingerprint.method.name
    val media = ::attachmentMediaGetterFingerprint.method.name
    val attachedStory = ::attachedStoryGetterFingerprint.method.name
    val menus = ::postMenuImplementationsFingerprint.dexMethodList.map { it.toMethod() }
    check(menus.isNotEmpty()) { "no post menu found" }
    val icon = appContext.resources.getIdentifier("fb_ic_download_24", "drawable", appContext.packageName)
    recordPlayerSources()

    fun postOf(item: Any?): Any? {
        if (!GraphModel.isA(item, GRAPHQL_STORY)) return null
        var story = item
        repeat(MAX_SHARE_DEPTH) { story = GraphModel.callGetter(story, attachedStory) ?: return story }
        return story
    }

    fun mediaOf(item: Any?): Any? {
        if (GraphModel.isA(item, GRAPHQL_STORY_ATTACHMENT)) return GraphModel.callGetter(item, media)
        val first = (GraphModel.callGetter(postOf(item), attachments) as? List<*>)?.firstOrNull()
        return GraphModel.callGetter(first, media)
    }

    fun addItem(menu: Menu, anchor: View?, item: Any?) {
        val video = mediaOf(item) ?: return
        val id = GraphModel.cachedString(video, "id")
        val hd = GraphModel.cachedString(video, "hd_playable_url")
        val sd = GraphModel.cachedString(video, "playable_url")
        val offered = GraphModel.typeName(video) == "Video" || Urls.isHttp(hd) || Urls.isHttp(sd) || PlayerSources.byId(id) != null
        if (!offered) return
        val context: Context = anchor?.context ?: appContext
        menu.add(MediaSaver.t(context, R.string.fb_dl_download_to_phone)).apply {
            if (icon != 0) setIcon(icon)
            setOnMenuItemClickListener {
                val source = PlayerSources.byId(id)
                val urls = listOfNotNull(source?.hd, hd, sd)
                if (!MediaSaver.saveVideo(context, id, source?.manifest, urls)) {
                    MediaSaver.toast(context, MediaSaver.t(context, R.string.fb_dl_no_video))
                }
                true
            }
        }
    }

    val depth = ThreadLocal.withInitial { 0 }
    menus.forEach { m ->
        m.hookMethod {
            before { depth.set(depth.get() + 1) }
            after { param ->
                val now = depth.get() - 1
                if (now <= 0) depth.remove() else depth.set(now)
                if (now > 0 || param.throwable != null) return@after
                runCatching {
                    val menu = param.args[0] as? Menu ?: return@runCatching
                    val props = param.args[2] ?: return@runCatching
                    addItem(menu, param.args[1] as? View, itemField.get(props))
                }
            }
        }
    }
}
