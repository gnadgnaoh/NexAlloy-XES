package io.github.nexalloy.morphe.tiktok.downloads

import android.app.Activity
import android.app.Application
import android.os.Bundle
import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.R
import io.github.nexalloy.callStaticMethod
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.morphe.tiktok.interaction.BASE_SHARE_PACKAGE
import io.github.nexalloy.morphe.tiktok.interaction.shareSnapshotFingerprint
import io.github.nexalloy.morphe.tiktok.shared.Model
import io.github.nexalloy.morphe.tiktok.shared.TikTokUi
import io.github.nexalloy.patch
import java.lang.ref.WeakReference
import java.lang.reflect.Member
import java.lang.reflect.Modifier
import java.util.WeakHashMap

val DownloadBestQuality = patch(
    name = "Download videos in best quality",
    description = "TikTok's own Save video downloads the sharpest version of the video the server has, instead of the smaller download copy.",
) {
    val video = "com.ss.android.ugc.aweme.feed.model.Video".findClassOrNull(classLoader) ?: error("Video class not found")
    val getter = video.getMethod("getDownloadNoWatermarkAddr")
    getter.hookMethod {
        after { param ->
            val best = runCatching { TikTokSaver.bestGearAddress(param.thisObject) }.getOrNull() ?: return@after
            if (Model.urls(best).isEmpty()) return@after
            if (TikTokSaver.gearHeight(best) > TikTokSaver.gearHeight(param.result)) param.result = best
        }
    }
    ::downloadAddressReadersFingerprint.dexMethodList.filterNot { it.isStaticInitializer }.forEach {
        runCatching { XposedBridge::class.java.callStaticMethod("deoptimizeMethod", it.toMember() as Member) }
    }
}

val ShareSheetSaves = patch(
    name = "Save sound, cover and stories from the share sheet",
    description = "Adds Save sound and Save cover to a video's share sheet, and Save to a story's share sheet.",
) {
    var resumed = WeakReference<Activity?>(null)
    (appContext as Application).registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) { resumed = WeakReference(activity) }
        override fun onActivityCreated(activity: Activity, state: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    })

    val ctor = ::nativeSaveActionConstructorFingerprint.constructor.apply { isAccessible = true }
    val action = ctor.declaringClass
    val enable = action.getMethod("enable")
    val label = ::nativeSaveActionLabelFingerprint.method
    val click = ::nativeSaveActionClickFingerprint.method
    val pkgClass = BASE_SHARE_PACKAGE.findClassOrNull(classLoader) ?: error("BaseSharePackage not found")

    label.hookMethod { before { param -> ShareSaves.owned(param.thisObject)?.let { param.result = it.label() } } }
    enable.hookMethod { before { param -> if (ShareSaves.owned(param.thisObject) != null) param.result = true } }
    click.hookMethod {
        before { param ->
            val mine = ShareSaves.owned(param.thisObject) ?: return@before
            param.result = null
            runCatching { mine.run() }.onFailure { Logger.printException({ "TikTok: share sheet save failed" }, it) }
        }
    }

    ::shareSnapshotFingerprint.hookMethod {
        after { param ->
            runCatching {
                val snapshot = param.thisObject
                val aweme = Model.field(Model.ofType(snapshot, pkgClass), "aweme") ?: return@runCatching
                val (field, row) = actionRow(snapshot, action) ?: return@runCatching
                val activity = resumed.get() ?: return@runCatching
                val added = ShareSaves.build(row, aweme) { ctor.newInstance(activity, aweme, "", "", "", 0) }
                if (added !== row) field.set(snapshot, added)
            }.onFailure { Logger.printException({ "TikTok: could not add share sheet saves" }, it) }
        }
    }
}

private fun actionRow(snapshot: Any, action: Class<*>): Pair<java.lang.reflect.Field, List<Any?>>? {
    val iface = action.interfaces.firstOrNull { i -> i.methods.any { it.name == "enable" } } ?: return null
    var c: Class<*>? = snapshot.javaClass
    while (c != null && c != Any::class.java) {
        for (f in c.declaredFields) {
            if (Modifier.isStatic(f.modifiers) || !List::class.java.isAssignableFrom(f.type)) continue
            f.isAccessible = true
            val list = f.get(snapshot) as? List<*> ?: continue
            if (list.isNotEmpty() && list.all { iface.isInstance(it) }) return f to list
        }
        c = c.superclass
    }
    return null
}

private object ShareSaves {
    enum class Kind { STORY, SOUND, COVER }

    class Mine(val kind: Kind, aweme: Any) {
        private val aweme = WeakReference(aweme)
        fun label(): String = when (kind) {
            Kind.STORY -> ""
            Kind.SOUND -> TikTokUi.t(R.string.tt_save_sound)
            Kind.COVER -> TikTokUi.t(R.string.tt_save_cover)
        }
        fun run() {
            val post = aweme.get() ?: return
            when (kind) {
                Kind.STORY -> TikTokSaver.saveStory(post)
                Kind.SOUND -> TikTokSaver.saveSound(post)
                Kind.COVER -> TikTokSaver.saveCover(post)
            }
        }
    }

    private val mine = WeakHashMap<Any, Mine>()

    fun owned(action: Any?): Mine? = action?.let { synchronized(mine) { mine[it] } }

    private fun keyOf(item: Any?) = runCatching { item!!.javaClass.getMethod("key").invoke(item) as? String }.getOrNull()

    fun build(row: List<Any?>, aweme: Any, newAction: () -> Any): List<Any?> {
        if (row.any { owned(it) != null }) return row
        val isSave = { item: Any? -> keyOf(item)?.startsWith("save") == true }
        val hasSave = row.any(isSave)
        val adds = ArrayList<Pair<Kind, Any>>()
        if (TikTokSaver.isStory(aweme)) {
            if (hasSave) return row
            adds += Kind.STORY to newAction()
        } else {
            if (TikTokSaver.hasSound(aweme)) adds += Kind.SOUND to newAction()
            if (TikTokSaver.hasCover(aweme)) adds += Kind.COVER to newAction()
        }
        if (adds.isEmpty()) return row
        synchronized(mine) { adds.forEach { (kind, item) -> mine[item] = Mine(kind, aweme) } }
        val out = ArrayList<Any?>(row.size + adds.size)
        val at = if (hasSave) row.indexOfFirst(isSave) + 1 else 0
        out.addAll(row.subList(0, at))
        out.addAll(adds.map { it.second })
        out.addAll(row.subList(at, row.size))
        return out
    }
}

val SaveProfilePictures = patch(
    name = "Save profile pictures",
    description = "Hold the profile picture on someone else's profile to save it at full size. A tap still opens it.",
) {
    val userClass = "com.ss.android.ugc.aweme.profile.model.User".findClassOrNull(classLoader) ?: error("User class not found")
    val assemClass = "com.ss.android.ugc.profile.business.avatar.ProfileAvatarAssem".findClassOrNull(classLoader)
        ?: error("ProfileAvatarAssem not found")
    val userGetter = assemClass.declaredMethods.single {
        it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) && it.returnType == userClass
    }.apply { isAccessible = true }

    ::otherProfileAvatarLongPressFingerprint.hookMethod {
        before { param ->
            val holder = if (Modifier.isStatic((param.method as java.lang.reflect.Method).modifiers)) param.args[0] else param.thisObject
            val assem = (holder?.takeIf { assemClass.isInstance(it) } ?: Model.ofType(holder, assemClass)) ?: return@before
            val user = runCatching { userGetter.invoke(assem) }.getOrNull() ?: return@before
            if (TikTokSaver.avatarUrls(user).isEmpty()) return@before
            param.result = null
            TikTokSaver.saveAvatar(user)
        }
    }
}

val SaveCommentPhotos = patch(
    name = "Save comment photos without watermark",
    description = "Save photo on a picture in the comments keeps the original picture, without the TikTok watermark.",
) {
    val urlModel = classLoader.loadClass(URL_MODEL)
    val function1 = classLoader.loadClass("kotlin.jvm.functions.Function1")
    val invoke = function1.getMethod("invoke", Any::class.java)
    ::commentPhotoSaverFingerprint.hookMethod {
        before { param ->
            val urls = Model.urls(Model.ofType(param.thisObject, urlModel))
            if (urls.isEmpty()) return@before
            val callback = Model.ofType(param.thisObject, function1)
            param.result = null
            TikTokSaver.saveCommentPhoto(urls) { ok -> callback?.let { runCatching { invoke.invoke(it, ok) } } }
        }
    }
}

