package io.github.nexalloy.revanced.instagram.download

import android.app.AndroidAppHelper
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import io.github.nexalloy.R
import java.lang.reflect.Method

internal object StoryDownloadHook {

    private fun label(): String =
        I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_dl_title)

    @Volatile
    private var downloadIcon = 0

    fun resolveDownloadIcon(cl: ClassLoader) {
        downloadIcon = runCatching {
            val option = cl.loadClass(MEDIA_OPTION_CLASS)
            val download = (option.getMethod("values").invoke(null) as Array<*>)
                .first { it.toString() == "DOWNLOAD" }
            option.getField("iconDrawable").getInt(download)
        }.getOrDefault(0)
        ModuleLog.line("(NA|Story) download icon=$downloadIcon")
    }

    fun onIconResolved(param: XC_MethodHook.MethodHookParam) {
        if (!FeatureFlags.enableStoryDownload || downloadIcon == 0) return
        if (param.result != 0) return
        val text = param.args.lastOrNull { it is CharSequence } as CharSequence? ?: return
        if (label().contentEquals(text)) param.result = downloadIcon
    }

    fun buildsOptionLabels(builder: Method): Boolean {
        val type = builder.returnType
        if (type.isArray) {
            val component = type.componentType
            return component != null && CharSequence::class.java.isAssignableFrom(component)
        }
        return java.util.List::class.java.isAssignableFrom(type)
    }

    @Suppress("UNCHECKED_CAST")
    fun onStoryOptionsBuilt(param: XC_MethodHook.MethodHookParam) {
        if (!FeatureFlags.enableStoryDownload) return
        val dl = label()
        if (dl.isEmpty()) return
        when (val original = param.result) {
            is Array<*> -> {
                if (original.any { it != null && it !is CharSequence }) return
                if (original.any { it is CharSequence && dl.contentEquals(it) }) return
                val extended = arrayOfNulls<CharSequence>(original.size + 1)
                for (i in original.indices) extended[i] = original[i] as CharSequence?
                extended[original.size] = dl
                param.result = extended
            }

            is MutableList<*> -> {
                if (original.any { it != null && it !is CharSequence }) return
                if (original.any { it is CharSequence && dl.contentEquals(it) }) return
                runCatching { (original as MutableList<Any?>).add(dl) }
            }
        }
    }

    fun onStoryOptionClick(param: XC_MethodHook.MethodHookParam) {
        if (!FeatureFlags.enableStoryDownload) return
        val tapped = param.args.lastOrNull { it is CharSequence } as CharSequence? ?: return
        if (!label().contentEquals(tapped)) return
        param.result = null

        val reelItem = findReelItem(param)
        val ctx = findContext(param)
        if (ctx == null) {
            ModuleLog.line("(NA|Story) context not found")
            return
        }
        val media = MediaResolver.mediaOfReelItem(reelItem)
        if (media == null) {
            ModuleLog.line("(NA|Story) media not found (reelItem=${reelItem != null})")
            MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_story_url_not_found))
            return
        }

        MediaSaver.executor.submit {
            val video = MediaResolver.videoUrl(media)
            val image = MediaResolver.imageUrl(ctx, media)
            val modelSaysVideo = MediaResolver.isVideo(media)
            val username = MediaResolver.username(media) ?: MediaResolver.usernameOfReelItem(reelItem)
            val mediaId = MediaResolver.mediaId(media) ?: storyId(reelItem)
            ModuleLog.line("(NA|Story) video=${video != null} image=${image != null} isVideo=$modelSaysVideo user=$username")

            MediaSaver.ui {
                when (StoryDownloadChoicePolicy.decide(image != null, video != null, modelSaysVideo)) {
                    StoryDownloadChoicePolicy.Decision.DOWNLOAD_PHOTO ->
                        DownloadUi.saveOne(ctx, MediaItem(image!!, false), MediaKind.STORY, username, mediaId)

                    StoryDownloadChoicePolicy.Decision.DOWNLOAD_VIDEO ->
                        DownloadUi.saveOne(ctx, MediaItem(video!!, true), MediaKind.STORY, username, mediaId)

                    StoryDownloadChoicePolicy.Decision.ASK ->
                        DownloadUi.offerStoryChoice(ctx, image, video, username, mediaId)

                    StoryDownloadChoicePolicy.Decision.NOT_FOUND ->
                        MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_story_url_not_found))
                }
            }
        }
    }

    private fun findReelItem(param: XC_MethodHook.MethodHookParam): Any? {
        val type = MediaResolver.reelItemType ?: return null
        for (c in helperFirst(param)) {
            if (type.isInstance(c)) return c
            MediaResolver.fieldOfType(c, type)?.let { return it }
        }
        return null
    }

    private fun helperFirst(param: XC_MethodHook.MethodHookParam): List<Any> {
        val helperClass = param.method.declaringClass
        val out = ArrayList<Any>()
        param.thisObject?.let { out.add(it) }
        param.args.forEach { if (it != null && helperClass.isInstance(it)) out.add(it) }
        param.args.forEach {
            if (it != null && !helperClass.isInstance(it) && it !is CharSequence) out.add(it)
        }
        return out
    }

    private fun findContext(param: XC_MethodHook.MethodHookParam): Context? {
        param.args.firstOrNull { it is Context }?.let { return it as Context }
        for (c in helperFirst(param)) {
            (MediaResolver.fieldOfType(c, Context::class.java) as? Context)?.let { return it }
        }
        return AndroidAppHelper.currentApplication()
    }

    private fun storyId(reelItem: Any?): String? = runCatching {
        (reelItem?.javaClass?.getMethod("getId")?.invoke(reelItem) as? String)
            ?.takeIf { it.isNotEmpty() }?.substringBefore('_')
    }.getOrNull()
}
