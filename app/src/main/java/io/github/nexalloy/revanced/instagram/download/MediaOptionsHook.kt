package io.github.nexalloy.revanced.instagram.download

import android.app.AndroidAppHelper
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import io.github.nexalloy.R
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

internal object MediaOptionsHook {

    private const val REEL_HELPER = "ClipsOrganicMoreOptionsHelper"

    private var optionClass: Class<*>? = null
    var downloadOption: Any? = null
        private set
    var copyLinkOption: Any? = null
        private set

    private val AUDIO_CARRIERS = listOf("DEBUG_MEDIA", "MEDIA_LOGGING", "DEBUG_AD")
    var audioOption: Any? = null
        private set
    private var downloadIcon: Int? = null

    fun loadOptionEnum(cl: ClassLoader): Boolean {
        val cls = cl.loadClass(MEDIA_OPTION_CLASS)
        optionClass = cls
        val values = cls.getMethod("values").invoke(null) as Array<*>
        downloadOption = values.firstOrNull { it?.toString() == "DOWNLOAD" }
        copyLinkOption = values.firstOrNull { it?.toString() == "COPY_LINK" }
        audioOption = AUDIO_CARRIERS.firstNotNullOfOrNull { name -> values.firstOrNull { it?.toString() == name } }
        downloadIcon = runCatching { cls.getMethod("getIconDrawable").invoke(downloadOption) as? Int }.getOrNull()
        ModuleLog.line("(NA|Menu) DOWNLOAD=${downloadOption != null} COPY_LINK=${copyLinkOption != null}")
        return downloadOption != null || copyLinkOption != null
    }

    private var addRowMethod: Method? = null
    private var creatorClass: Class<*>? = null
    private var idxButtonType = 0
    private var idxOption = 1
    private var idxCreator = 2
    private var idxLabel = 3
    private var normalButtonType: Any? = null
    private val addingRow = ThreadLocal.withInitial { false }
    private val processedCreators: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())

    fun selectAddRowMethod(candidates: List<Method>): Method? {
        val option = optionClass ?: return null
        for (m in candidates) {
            if (!Modifier.isStatic(m.modifiers)) continue
            val p = m.parameterTypes
            if (p.size < 4) continue
            var o = -1
            var list = -1
            var self = -1
            var label = -1
            var type = -1
            for (i in p.indices) when {
                p[i] == option -> o = i
                ArrayList::class.java.isAssignableFrom(p[i]) -> list = i
                p[i] == m.declaringClass -> self = i
                CharSequence::class.java.isAssignableFrom(p[i]) -> label = i
                p[i].isEnum && type < 0 && o < 0 -> type = i
            }
            if (o < 0 || list < 0 || self < 0 || label < 0 || type < 0) continue
            idxOption = o
            idxCreator = self
            idxLabel = label
            idxButtonType = type
            normalButtonType = pickNormalButtonType(p[type])
            if (normalButtonType == null) continue
            m.isAccessible = true
            addRowMethod = m
            creatorClass = m.declaringClass
            ModuleLog.line("(NA|Menu) post add-row: ${m.declaringClass.name}.${m.name}")
            return m
        }
        ModuleLog.line("(NA|Menu) post add-row not found among ${candidates.size}")
        return null
    }

    private fun pickNormalButtonType(enumClass: Class<*>): Any? {
        val values = runCatching { enumClass.getMethod("values").invoke(null) as Array<*> }.getOrNull()
            ?: return null
        return values.firstOrNull { it.toString().equals("normal", true) }
            ?: values.firstOrNull { it.toString().equals("action", true) }
            ?: values.firstOrNull()
    }

    fun beforeAddRow(param: XC_MethodHook.MethodHookParam) {
        if (addingRow.get() == true) return
        val opt = param.args[idxOption]
        if ((wantsPostDownload() && opt === downloadOption) || (FeatureFlags.copyMediaLink && opt === copyLinkOption)) {
            param.result = null
        }
    }

    fun afterAddRow(param: XC_MethodHook.MethodHookParam) {
        if (addingRow.get() == true) return
        val opt = param.args[idxOption]
        if (opt === downloadOption || opt === copyLinkOption || opt === audioOption) return
        val creator = param.args[idxCreator] ?: return
        synchronized(processedCreators) { if (!processedCreators.add(creator)) return }

        val app = AndroidAppHelper.currentApplication()
        if (wantsPostDownload()) injectRow(param, downloadOption, I18n.t(app, R.string.ig_dl_title))
        if (wantsPostDownload() && hasAudio(MediaResolver.fieldOfType(creator, MediaResolver.mediaType))) {
            injectRow(param, audioOption, I18n.t(app, R.string.ig_dl_audio_title))
        }
        if (FeatureFlags.copyMediaLink) injectRow(param, copyLinkOption, I18n.t(app, R.string.ig_copy_link_title))
    }

    private fun injectRow(param: XC_MethodHook.MethodHookParam, option: Any?, label: String) {
        val m = addRowMethod ?: return
        if (option == null || label.isEmpty()) return
        val args = param.args.copyOf()
        args[idxButtonType] = normalButtonType
        args[idxOption] = option
        args[idxLabel] = label
        addingRow.set(true)
        try {
            m.invoke(null, *args)
        } catch (t: Throwable) {
            ModuleLog.line("(NA|Menu) add-row invoke failed: $t")
        } finally {
            addingRow.set(false)
        }
    }

    fun onAllowlistBuilt(param: XC_MethodHook.MethodHookParam) {
        val original = param.result as? List<*> ?: return
        val add = ArrayList<Any>(2)
        if (wantsPostDownload()) downloadOption?.takeIf { it !in original }?.let { add.add(it) }
        if (wantsPostDownload()) audioOption?.takeIf { it !in original }?.let { add.add(it) }
        if (FeatureFlags.copyMediaLink) copyLinkOption?.takeIf { it !in original }?.let { add.add(it) }
        if (add.isEmpty()) return
        param.result = ArrayList<Any?>(original).apply { addAll(add) }
    }

    private fun wantsPostDownload() = FeatureFlags.enablePostDownload && downloadOption != null

    @Suppress("UNCHECKED_CAST")
    fun onReelOptionsBuilt(param: XC_MethodHook.MethodHookParam) {
        val options = param.result as? MutableList<Any?> ?: return
        runCatching {
            if (FeatureFlags.enableReelDownload) downloadOption?.let { if (it !in options) options.add(it) }
            if (FeatureFlags.copyMediaLink) copyLinkOption?.let { if (it !in options) options.add(it) }
        }
    }

    private var reelRowAdder: Method? = null
    private val addingReelRow = ThreadLocal.withInitial { false }

    fun bindReelRowAdder(method: Method) {
        reelRowAdder = method.apply { isAccessible = true }
    }

    fun afterReelRowAdded(param: XC_MethodHook.MethodHookParam) {
        if (addingReelRow.get() == true || !FeatureFlags.enableReelDownload) return
        if (param.args[1] !== downloadOption) return
        val carrier = audioOption ?: return
        val adder = reelRowAdder ?: return
        val helper = param.thisObject ?: return
        if (!hasAudio(mediaOf(helper))) return
        val ctx = param.args[0] as? Context ?: return
        addingReelRow.set(true)
        try {
            adder.invoke(helper, ctx, carrier, param.args[2], param.args[3], downloadIcon, I18n.t(ctx, R.string.ig_dl_audio_title))
        } catch (t: Throwable) {
            ModuleLog.line("(NA|Menu) reel audio row failed: $t")
        } finally {
            addingReelRow.set(false)
        }
    }

    private fun hasAudio(media: Any?): Boolean = media != null && runCatching {
        val children = MediaResolver.carouselChildren(media)
        if (children.size >= 2) children.any { AudioSource.of(it, media) != null } else AudioSource.of(media) != null
    }.getOrDefault(false)

    fun selectDispatchers(candidates: List<Method>): List<Method> {
        val instance = candidates.filterNot { Modifier.isStatic(it.modifiers) }
        val hasPublic = instance.any { Modifier.isPublic(it.modifiers) }
        return instance.filter { !hasPublic || !Modifier.isPrivate(it.modifiers) }
            .onEach { it.isAccessible = true }
    }

    private val surfaceCache = ConcurrentHashMap<Class<*>, MediaKind>()

    private fun surfaceOf(cls: Class<*>): MediaKind = surfaceCache.getOrPut(cls) {
        val redexName = runCatching {
            cls.getDeclaredField("__redex_internal_original_name").apply { isAccessible = true }.get(null) as? String
        }.getOrNull()
        if (redexName == REEL_HELPER) MediaKind.REEL else MediaKind.POST
    }

    fun onOptionSelected(param: XC_MethodHook.MethodHookParam) {
        if (addingRow.get() == true) return
        val option = param.args.firstOrNull { it != null && optionClass?.isInstance(it) == true } ?: return
        val isDownload = option === downloadOption
        val isCopy = option === copyLinkOption
        val isAudio = option === audioOption && option != null
        if (!isDownload && !isCopy && !isAudio) return

        val helper = param.thisObject ?: return
        val surface = surfaceOf(helper.javaClass)
        val enabled = when {
            isCopy -> FeatureFlags.copyMediaLink
            surface == MediaKind.REEL -> FeatureFlags.enableReelDownload
            else -> FeatureFlags.enablePostDownload
        }
        if (!enabled) return
        param.result = null

        val ctx = MediaResolver.fieldOfType(helper, Context::class.java) as? Context
            ?: AndroidAppHelper.currentApplication()
        val media = mediaOf(helper)
        if (ctx == null || media == null) {
            ModuleLog.line("(NA|Menu) ${surface.tag}: context=${ctx != null} media=${media != null}")
            ctx?.let { MediaSaver.toast(it, I18n.t(it, R.string.ig_toast_no_media_for_post)) }
            return
        }

        if (isAudio) return saveAudio(ctx, helper, media, surface)

        MediaSaver.executor.submit {
            val items = MediaResolver.items(ctx, media)
            val username = MediaResolver.username(media)
            val mediaId = MediaResolver.mediaId(media)
            ModuleLog.line("(NA|Menu) ${surface.tag}: ${items.size} item(s) user=$username")
            MediaSaver.ui {
                if (isCopy) {
                    DownloadUi.offerCopyLinks(ctx, items.map { it.url })
                } else {
                    val fromView = CarouselIndex.fromWindow(ctx, items.size)
                    val index = if (fromView >= 0) fromView else CarouselIndex.fromFields(helper, items.size)
                    DownloadUi.offerItems(ctx, items, surface, username, mediaId, index)
                }
            }
        }
    }

    private fun saveAudio(ctx: Context, helper: Any, media: Any, surface: MediaKind) {
        val children = MediaResolver.carouselChildren(media)
        val index = if (children.size >= 2) {
            CarouselIndex.fromWindow(ctx, children.size).takeIf { it >= 0 } ?: CarouselIndex.fromFields(helper, children.size)
        } else -1
        val target = children.getOrNull(index) ?: media
        MediaSaver.executor.submit {
            val audio = AudioSource.of(target, media)
            if (audio == null) {
                MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_no_audio))
                return@submit
            }
            MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_downloading_audio))
            val username = MediaResolver.username(media)
            val name = MediaSaver.buildFilename(username, "${surface.tag}_audio", MediaResolver.mediaId(media), false, index)
                .substringBeforeLast('.') + ".m4a"
            runCatching { MediaSaver.saveAudio(ctx, audio.url, name, username, audio.fromMs, audio.lengthMs) }
                .onSuccess { MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_audio_saved)) }
                .onFailure {
                    ModuleLog.line("(NA|DL) audio failed: $it")
                    MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_download_failed, it.message ?: it.javaClass.simpleName))
                }
        }
    }

    private fun mediaOf(helper: Any): Any? {
        MediaResolver.fieldOfType(helper, MediaResolver.mediaType)?.let { return it }
        creatorClass?.let { cc ->
            val creator = MediaResolver.fieldOfType(helper, cc)
            MediaResolver.fieldOfType(creator, MediaResolver.mediaType)?.let { return it }
        }
        return MediaResolver.findMedia(helper, 2)
    }

}
