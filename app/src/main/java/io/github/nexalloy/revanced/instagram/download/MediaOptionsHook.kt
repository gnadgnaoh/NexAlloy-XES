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

/**
 * Download / Copy link entries in the post and reel overflow menus.
 *
 * Both menus use `MediaOption.Option`. We make sure DOWNLOAD and COPY_LINK are offered (post
 * menu: allow-list + an extra row; reel menu: the option list), then intercept the option
 * dispatchers. The dispatcher's redex class name tells the surface apart:
 * `MediaOptionsOverflowHelper` = feed post, `ClipsOrganicMoreOptionsHelper` = reel.
 */
internal object MediaOptionsHook {

    private const val REEL_HELPER = "ClipsOrganicMoreOptionsHelper"

    private var optionClass: Class<*>? = null
    var downloadOption: Any? = null
        private set
    var copyLinkOption: Any? = null
        private set

    fun loadOptionEnum(cl: ClassLoader): Boolean {
        val cls = runCatching { cl.loadClass(MEDIA_OPTION_CLASS) }.getOrNull() ?: return false
        optionClass = cls
        val values = runCatching { cls.getMethod("values").invoke(null) as Array<*> }.getOrNull()
            ?: return false
        downloadOption = values.firstOrNull { it?.toString() == "DOWNLOAD" }
        copyLinkOption = values.firstOrNull { it?.toString() == "COPY_LINK" }
        ModuleLog.line("(NA|Menu) DOWNLOAD=${downloadOption != null} COPY_LINK=${copyLinkOption != null}")
        return downloadOption != null || copyLinkOption != null
    }

    // region post menu rows

    private var addRowMethod: Method? = null
    private var creatorClass: Class<*>? = null
    private var idxButtonType = 0
    private var idxOption = 1
    private var idxCreator = 2
    private var idxLabel = 3
    private var normalButtonType: Any? = null
    private val addingRow = ThreadLocal.withInitial { false }
    private val processedCreators: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())

    /** `static void addRow(ButtonType enum, Option, Creator, CharSequence, ArrayList, …)`. */
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

    /** Drops Instagram's own DOWNLOAD / COPY_LINK row; ours is added once per menu instead. */
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
        if (opt === downloadOption || opt === copyLinkOption) return
        val creator = param.args[idxCreator] ?: return
        synchronized(processedCreators) { if (!processedCreators.add(creator)) return }

        val app = AndroidAppHelper.currentApplication()
        if (wantsPostDownload()) injectRow(param, downloadOption, I18n.t(app, R.string.ig_dl_title))
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
        if (FeatureFlags.copyMediaLink) copyLinkOption?.takeIf { it !in original }?.let { add.add(it) }
        if (add.isEmpty()) return
        param.result = ArrayList<Any?>(original).apply { addAll(add) }
    }

    private fun wantsPostDownload() = FeatureFlags.enablePostDownload && downloadOption != null

    // endregion

    // region reel option list

    @Suppress("UNCHECKED_CAST")
    fun onReelOptionsBuilt(param: XC_MethodHook.MethodHookParam) {
        val options = param.result as? MutableList<Any?> ?: return
        runCatching {
            if (FeatureFlags.enableReelDownload) downloadOption?.let { if (it !in options) options.add(it) }
            if (FeatureFlags.copyMediaLink) copyLinkOption?.let { if (it !in options) options.add(it) }
        }
    }

    // endregion

    // region dispatch

    fun selectDispatchers(candidates: List<Method>): List<Method> {
        val instance = candidates.filterNot { Modifier.isStatic(it.modifiers) }
        // Private overloads are internal steps of a public dispatcher; hooking both is redundant.
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
        if (!isDownload && !isCopy) return

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

    /** Reel helper holds the Media; the post helper holds the menu creator, which holds it. */
    private fun mediaOf(helper: Any): Any? {
        MediaResolver.fieldOfType(helper, MediaResolver.mediaType)?.let { return it }
        creatorClass?.let { cc ->
            val creator = MediaResolver.fieldOfType(helper, cc)
            MediaResolver.fieldOfType(creator, MediaResolver.mediaType)?.let { return it }
        }
        return MediaResolver.findMedia(helper, 2)
    }

    // endregion
}
