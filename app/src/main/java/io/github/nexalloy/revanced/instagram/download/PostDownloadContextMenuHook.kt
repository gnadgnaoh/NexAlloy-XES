package io.github.nexalloy.revanced.instagram.download

import android.app.AndroidAppHelper
import android.content.Context
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import io.github.nexalloy.R
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * Injects a "Download" entry into the feed-post three-dots (⋮) menu.
 *
 * Hook A — static add-button method on MediaOptionsOverflowMenuCreator
 *   Found via DexKit: findClass("MediaOptionsOverflowMenuCreator") → findMethod(declaredClass, void).
 *   Hooks every addButton call; injects our Download entry once per menu popup.
 *
 * Hook B — options click handler
 *   Found via DexKit: void method with sole param MediaOption$Option (stable, unobfuscated type).
 *   Fires on every option tap; we handle DOWNLOAD and trigger the download.
 *
 * Carousel index: read from the first int field on thisObject whose value fits [0, urlCount).
 *   Tries a known field name first (fast path), then falls back to scanning all int fields
 *   so a rename in a future build doesn't break it.
 */
object PostDownloadContextMenuHook {

    // ── Resolved at install time ──────────────────────────────────────────────

    // com.instagram.feed.media.mediaoption.MediaOption$Option — stable public enum
    private var mediaOptionEnumClass: Class<*>? = null

    /** MediaOption$Option.DOWNLOAD */
    private var downloadOptionValue: Any? = null

    /** MediaOption$Option.COPY_LINK (#117) */
    private var copyLinkOptionValue: Any? = null

    // Obfuscated creator class found via "MediaOptionsOverflowMenuCreator" string
    private var menuCreatorClass: Class<*>? = null

    // Static "add one button to list" method on menuCreatorClass — no hardcoded name
    private var addButtonMethod: Method? = null
    private var enumNormalValue: Any? = null

    // Param indices — resolved once when addButtonMethod is found
    private var idxEnum = 0
    private var idxOption = 1
    private var idxSelf = 2
    private var idxText = 3
    private var idxList = 4

    // ── Guards ────────────────────────────────────────────────────────────────

    private val sAddingDownload: ThreadLocal<Boolean> =
        ThreadLocal.withInitial { false }

    private val processedCreators: MutableSet<Any> =
        Collections.newSetFromMap(WeakHashMap())

    // ── Step 1: MediaOption$Option.DOWNLOAD ──────────────────────────────────

    fun loadMediaOptionEnum(cl: ClassLoader) {
        try {
            val enumClass = cl.loadClass(
                "com.instagram.feed.media.mediaoption.MediaOption\$Option"
            )
            mediaOptionEnumClass = enumClass
            val values = enumClass.getMethod("values").invoke(null) as Array<*>
            for (v in values) {
                if (v == null) continue
                val name = v.toString()
                if (downloadOptionValue == null && name == "DOWNLOAD") {
                    downloadOptionValue = v
                } else if (copyLinkOptionValue == null && name == "COPY_LINK") {
                    copyLinkOptionValue = v
                }
            }
            if (downloadOptionValue == null) {
                ModuleLog.line("(NA|Post) ❌ DOWNLOAD enum value not found")
            }
            if (copyLinkOptionValue == null) {
                ModuleLog.line("(NA|Post) ❌ COPY_LINK enum value not found")
            }
        } catch (t: Throwable) {
            ModuleLog.line("(NA|Post) ❌ loadMediaOptionEnum: $t")
        }
    }

    // ── Step 2: pick the add-button method out of the menu creator's void methods ─
    //
    // The creator class is located by string and its void methods listed by the patch. The
    // right one is static and takes the option enum plus the ArrayList to add to; the other
    // parameters (button-type enum, the creator itself, the label) are identified by type so
    // the indices survive a reordering of the signature.

    /**
     * Selects the add-button method from `candidates` and records the parameter layout.
     *
     * @return the method the patch should hook, or `null` when none of the candidates fit
     */
    fun resolveAddButtonMethod(candidates: List<Method>): Method? {
        for (m in candidates) {
            try {
                if (!Modifier.isStatic(m.modifiers)) continue

                val p = m.parameterTypes
                if (p.size < 4) continue

                var eIdx = -1
                var oIdx = -1
                var sIdx = -1
                var tIdx = -1
                var lIdx = -1
                for (i in p.indices) {
                    if (mediaOptionEnumClass != null && p[i] == mediaOptionEnumClass) {
                        oIdx = i
                    } else if (ArrayList::class.java.isAssignableFrom(p[i])) {
                        lIdx = i
                    } else if (p[i] == m.declaringClass) {
                        sIdx = i
                    } else if (CharSequence::class.java.isAssignableFrom(p[i])) {
                        tIdx = i
                    } else if (p[i].isEnum && eIdx < 0 && oIdx < 0) {
                        eIdx = i
                    }
                }

                if (oIdx < 0 || lIdx < 0) continue

                m.isAccessible = true
                addButtonMethod = m
                menuCreatorClass = m.declaringClass
                idxEnum = if (eIdx >= 0) eIdx else 0
                idxOption = oIdx
                idxSelf = if (sIdx >= 0) sIdx else 2
                idxText = if (tIdx >= 0) tIdx else 3
                idxList = lIdx
                resolveNormalButtonType()
                ModuleLog.line(
                    "(NA|Post) add-button: " + m.declaringClass.name + "." + m.name
                )
                return m
            } catch (ignored: Throwable) {
            }
        }
        ModuleLog.line(
            "(NA|Post) ❌ add-button method not found among " +
                candidates.size + " candidate(s)"
        )
        return null
    }

    /** Picks the "normal" value of the button-type enum, falling back to "action", then first. */
    private fun resolveNormalButtonType() {
        try {
            val btnTypeEnumClass = addButtonMethod?.parameterTypes?.get(idxEnum) ?: return
            val btnVals = btnTypeEnumClass.getMethod("values").invoke(null) as Array<*>
            var firstVal: Any? = null
            for (v in btnVals) {
                if (v == null) continue
                if (firstVal == null) firstVal = v
                if (enumNormalValue == null && v.toString().equals("normal", ignoreCase = true)) {
                    enumNormalValue = v
                }
            }
            if (enumNormalValue == null) {
                for (v in btnVals) {
                    if (v != null && v.toString().equals("action", ignoreCase = true)) {
                        enumNormalValue = v
                        break
                    }
                }
            }
            if (enumNormalValue == null) enumNormalValue = firstVal
        } catch (ignored: Throwable) {
        }
    }

    /** True once [resolveAddButtonMethod] found everything the injection needs. */
    fun canInjectRows(): Boolean {
        val ready = addButtonMethod != null && enumNormalValue != null &&
            (downloadOptionValue != null || copyLinkOptionValue != null)
        if (!ready) {
            ModuleLog.line("(NA|Post) ❌ cannot inject rows — prerequisites missing")
        }
        return ready
    }

    // ── Hook A: intercept every addButton call, inject Download once per menu ─

    /** Suppresses Instagram's own Download / Copy link rows; ours are injected instead. */
    fun onAddButtonBefore(param: XC_MethodHook.MethodHookParam) {
        if (sAddingDownload.get() == true) return
        val opt = param.args[idxOption]
        if (FeatureFlags.enablePostDownload && opt === downloadOptionValue) {
            param.result = null
        } else if (FeatureFlags.copyMediaLink && opt === copyLinkOptionValue) {
            param.result = null
        }
    }

    /** Injects our rows once per menu, on the first option the menu adds. */
    fun onAddButtonAfter(param: XC_MethodHook.MethodHookParam) {
        if (sAddingDownload.get() == true) return

        val wantDownload = FeatureFlags.enablePostDownload && downloadOptionValue != null
        val wantCopyLink = FeatureFlags.copyMediaLink && copyLinkOptionValue != null
        if (!wantDownload && !wantCopyLink) return

        // Don't react to our own target option types (suppressed in onAddButtonBefore)
        val opt = param.args[idxOption]
        if (opt === downloadOptionValue || opt === copyLinkOptionValue) return

        val self = param.args[idxSelf] ?: return
        val alreadyProcessed: Boolean
        synchronized(processedCreators) {
            alreadyProcessed = processedCreators.contains(self)
            if (!alreadyProcessed) processedCreators.add(self)
        }
        if (alreadyProcessed) return

        if (wantDownload) injectRow(param, downloadOptionValue, R.string.ig_dl_title)
        if (wantCopyLink) injectRow(param, copyLinkOptionValue, R.string.ig_copy_link_title)
    }

    /** Adds one button (Download / Copy Media Link) to the menu currently being built. */
    private fun injectRow(
        param: XC_MethodHook.MethodHookParam,
        optionValue: Any?,
        labelResId: Int,
    ) {
        val method = addButtonMethod ?: return
        val callArgs = arrayOfNulls<Any>(method.parameterCount)
        System.arraycopy(param.args, 0, callArgs, 0, callArgs.size)
        callArgs[idxEnum] = enumNormalValue
        callArgs[idxOption] = optionValue
        callArgs[idxText] = I18n.t(AndroidAppHelper.currentApplication(), labelResId)

        sAddingDownload.set(true)
        try {
            // Java passed the array straight through to the varargs parameter; Kotlin needs
            // the spread operator, otherwise the whole array arrives as a single argument.
            method.invoke(null, *callArgs)
        } catch (t: Throwable) {
            ModuleLog.line("(NA|Post) ❌ addButton invoke failed: $t")
        } finally {
            sAddingDownload.set(false)
        }
    }

    // ── Hook B: click handler ─────────────────────────────────────────────────

    /**
     * Narrows the click-dispatcher candidates to the ones worth hooking.
     *
     * Statics are dropped, and so are private methods — those are consistently internal
     * analytics helpers that also receive the tapped option for telemetry, and hooking them
     * fired the download twice. The private exclusion only applies when a public candidate
     * exists too: on a build where the real dispatcher is private with no public sibling,
     * skipping it would break the feature outright, which is worse than the duplicate.
     */
    fun selectClickDispatchers(candidates: List<Method>): List<Method> {
        val instanceMethods = candidates.filterNot { Modifier.isStatic(it.modifiers) }
        val hasPublicCandidate = instanceMethods.any { Modifier.isPublic(it.modifiers) }

        val selected = ArrayList<Method>()
        for (m in instanceMethods) {
            if (hasPublicCandidate && Modifier.isPrivate(m.modifiers)) continue
            m.isAccessible = true
            selected.add(m)
        }
        return selected
    }

    /** Click dispatch entry point for the patch's hook. */
    fun onOptionClick(param: XC_MethodHook.MethodHookParam) {
        if (!FeatureFlags.enablePostDownload && !FeatureFlags.copyMediaLink) return
        onOptionClicked(param)
    }

    // ── Hook C: allowlist patch ─────────────────────────────────────────────────
    //
    // IG's newer "SimplifiedMediaOverflowBottomSheet" menu filters the button list
    // down to a hardcoded allowlist of MediaOption$Option values before rendering
    // (DOWNLOAD isn't one of them), silently dropping our injected entry even though
    // Hook A added it successfully. Found via DexKit field-usage matching — the
    // allowlist-builder method is the unique static (boolean)->List method that
    // references these three particular MediaOption$Option constants together.
    // We patch its return value to also include DOWNLOAD so our entry survives.

    /** Adds our options to the allowlist the simplified menu filters against. */
    fun onAllowlistBuilt(param: XC_MethodHook.MethodHookParam) {
        try {
            val original = param.result as? List<*> ?: return

            val addDownload = FeatureFlags.enablePostDownload && downloadOptionValue != null &&
                !original.contains(downloadOptionValue)
            val addCopyLink = FeatureFlags.copyMediaLink && copyLinkOptionValue != null &&
                !original.contains(copyLinkOptionValue)
            if (!addDownload && !addCopyLink) return

            val patched = ArrayList<Any?>(original)
            if (addDownload) patched.add(downloadOptionValue)
            if (addCopyLink) patched.add(copyLinkOptionValue)
            param.result = patched
        } catch (t: Throwable) {
            ModuleLog.line("(NA|Post) ❌ allowlist patch failed: $t")
        }
    }

    // ── Click dispatch ────────────────────────────────────────────────────────

    private fun onOptionClicked(param: XC_MethodHook.MethodHookParam) {
        try {
            if (sAddingDownload.get() == true) return

            // Find MediaOption$Option argument
            var clicked: Any? = null
            for (a in param.args) {
                val enumClass = mediaOptionEnumClass
                if (a != null && enumClass != null && enumClass.isInstance(a)) {
                    clicked = a
                    break
                }
            }
            if (clicked == null) {
                for (a in param.args) {
                    if (a != null && a.javaClass.isEnum &&
                        (
                            a.toString().contains("DOWNLOAD") ||
                                a.toString().contains("COPY_LINK")
                            )
                    ) {
                        clicked = a
                        break
                    }
                }
            }
            if (clicked == null) return

            val option = clicked.toString()
            val isDownload = option == "DOWNLOAD" && FeatureFlags.enablePostDownload
            val isCopyLink = option == "COPY_LINK" && FeatureFlags.copyMediaLink
            if (!isDownload && !isCopyLink) return

            param.result = null // consume the event

            val thisObj = param.thisObject

            val ctx = findContext(thisObj)
            if (ctx == null) {
                ModuleLog.line("(NA|Post) ❌ Context not found in click handler")
                return
            }

            var media = findMediaViaMenuCreator(thisObj)
            if (media == null) media = findMedia(thisObj)
            if (media == null) {
                ModuleLog.line("(NA|Post) ❌ Media not found in click handler")
                Toast.makeText(
                    ctx, I18n.t(ctx, R.string.ig_toast_no_media_for_post), Toast.LENGTH_SHORT
                ).show()
                return
            }

            if (isCopyLink) triggerCopyLink(ctx, media) else triggerDownload(ctx, media, thisObj)
        } catch (t: Throwable) {
            ModuleLog.line("(NA|Post) ❌ onOptionClicked: $t")
        }
    }

    // ── Download dispatch ─────────────────────────────────────────────────────

    private fun triggerDownload(ctx: Context, media: Any, clickHandler: Any?) {
        var username = FeedVideoDownloadHook.extractUsernameFromMediaObject(media)
        if (username == null) username = "post"

        var mediaId = "0"
        try {
            val id = media.javaClass.getMethod("getId").invoke(media)
            if (id is String && id.isNotEmpty()) mediaId = id
        } catch (ignored: Throwable) {
        }

        val urls = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, media)

        // Carousel index: live view scan is primary (reads the actual visible slide, and
        // now only trusts itself when exactly one on-screen carousel matches the target
        // size — see ReelDownloadHook.findCarouselIndexFromView). Field-guessing on
        // clickHandler is the fallback for when the scan is ambiguous or finds nothing;
        // it's unreliable specifically for reels whose DOWNLOAD row was patched into the
        // shared post-style option list (see ReelDownloadHook.installReduceOptionsListPatch),
        // where clickHandler carries no real position field.
        val viewIdx = if (urls.size > 1) {
            ReelDownloadHook.findCarouselIndexFromView(ctx, urls.size)
        } else {
            -1
        }
        val carouselIdx =
            if (viewIdx >= 0) viewIdx else findCarouselIndex(clickHandler, urls.size)

        val finalUser = username
        val finalId = mediaId
        FeedVideoDownloadHook.mainHandler.post {
            FeedVideoDownloadHook.showPostDownloadDialog(
                ctx, urls, finalUser, finalId, carouselIdx
            )
        }
    }

    // ── Copy Media Link (#117) ────────────────────────────────────────────────
    //
    // Copies the direct CDN url of the currently-visible slide to the clipboard.
    // Reuses the same URL extraction + carousel-index resolution as the downloader
    // so the copied link always matches the slide the user is looking at.

    private fun triggerCopyLink(ctx: Context, media: Any) {
        val urls = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, media)
        if (urls.isEmpty()) {
            Toast.makeText(
                ctx, I18n.t(ctx, R.string.ig_copy_link_none), Toast.LENGTH_SHORT
            ).show()
            return
        }
        // Single URL copies straight to clipboard; a carousel shows a per-slide chooser
        // (see showCopyLinkSheet — the visible slide can't be resolved reliably in the feed).
        FeedVideoDownloadHook.mainHandler.post {
            FeedVideoDownloadHook.showCopyLinkSheet(ctx, urls)
        }
    }

    /**
     * Finds the current carousel slide index from the click handler object.
     * Tries a known field name first (fast path), then scans all int fields for the
     * first value in [0, urlCount) — only the index field will be in that range.
     */
    internal fun findCarouselIndex(obj: Any?, urlCount: Int): Int {
        if (obj == null || urlCount <= 1) return 0

        // Fast path: try the currently known field name
        try {
            val f = obj.javaClass.getDeclaredField("A00")
            f.isAccessible = true
            val v = f.getInt(obj)
            if (v in 0 until urlCount) return v
        } catch (ignored: Throwable) {
        }

        // Fallback: scan all int fields for a value that fits as a carousel index
        for (f in obj.javaClass.declaredFields) {
            if (f.type != Int::class.javaPrimitiveType) continue
            f.isAccessible = true
            try {
                val v = f.getInt(obj)
                // v > 0 skips 0-value flags/uninitialised
                if (v in 1 until urlCount) return v
            } catch (ignored: Throwable) {
            }
        }

        return 0
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    private fun findContext(obj: Any?): Context? {
        if (obj == null) return null
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (!Context::class.java.isAssignableFrom(f.type)) continue
                f.isAccessible = true
                try {
                    val v = f.get(obj)
                    if (v is Context) return v
                } catch (ignored: Throwable) {
                }
            }
            cls = cls.superclass
        }
        return null
    }

    /**
     * Preferred media lookup: reads the click handler's own reference to the menu-creator
     * (QpF-equivalent) instance and pulls its Media field directly, instead of the generic
     * BFS in [findMedia]. The generic scan can land on an unrelated Media-typed field
     * elsewhere in the object graph first (e.g. a hosting Fragment's own "created with" post
     * reference, which doesn't update as the user navigates between posts within that
     * Fragment) — the menu-creator's own Media field is always the one that was current
     * when THIS specific menu was built, so it can't go stale that way.
     */
    private fun findMediaViaMenuCreator(clickHandler: Any?): Any? {
        if (clickHandler == null || menuCreatorClass == null) return null
        try {
            val creator = findMenuCreator(clickHandler) ?: return null

            var cCls: Class<*>? = creator.javaClass
            while (cCls != null && cCls != Any::class.java) {
                for (f in cCls.declaredFields) {
                    if (f.type.name == "com.instagram.feed.media.Media") {
                        f.isAccessible = true
                        return f.get(creator)
                    }
                }
                cCls = cCls.superclass
            }
        } catch (ignored: Throwable) {
        }
        return null
    }

    /** Replaces the Java labelled `break outer` with an early return. */
    private fun findMenuCreator(clickHandler: Any): Any? {
        var cls: Class<*>? = clickHandler.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (f.type != menuCreatorClass) continue
                f.isAccessible = true
                val v = f.get(clickHandler)
                if (v != null) return v
            }
            cls = cls.superclass
        }
        return null
    }

    private fun findMedia(obj: Any?): Any? = findMediaDepth(obj, 0)

    private fun findMediaDepth(obj: Any?, depth: Int): Any? {
        if (obj == null || depth > 2) return null
        var cls: Class<*>? = obj.javaClass
        val rootName = obj.javaClass.name
        if (obj.javaClass.isPrimitive || rootName.startsWith("java.") ||
            rootName.startsWith("android.")
        ) {
            return null
        }

        val nextLevel: MutableList<Any>? = if (depth < 2) ArrayList() else null

        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (f.type.isPrimitive) continue
                f.isAccessible = true
                try {
                    val v = f.get(obj) ?: continue
                    val name = v.javaClass.name
                    if (name == "com.instagram.feed.media.Media") return v
                    if (nextLevel != null && !name.startsWith("java.") &&
                        !name.startsWith("android.")
                    ) {
                        nextLevel.add(v)
                    }
                } catch (ignored: Throwable) {
                }
            }
            cls = cls.superclass
        }

        if (nextLevel != null) {
            for (child in nextLevel) {
                val found = findMediaDepth(child, depth + 1)
                if (found != null) return found
            }
        }
        return null
    }
}
