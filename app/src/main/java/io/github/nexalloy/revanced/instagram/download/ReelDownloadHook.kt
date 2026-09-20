package io.github.nexalloy.revanced.instagram.download

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import io.github.nexalloy.R
import java.lang.reflect.Field
import java.lang.reflect.Method

object ReelDownloadHook {

    private var controllerClass: Class<*>? = null
    private var hookMethod: Method? = null

    private var buttonAdderMethod: Method? = null
    private var activityField: Field? = null

    // Cached field path to the carousel position holder on the controller.
    // The position holder is identified structurally: a non-framework object field
    // whose class has exactly ONE int field (survives obfuscation renames).
    private var cachedOuterField: Field? = null
    private var cachedInnerField: Field? = null

    /**
     * Binds the options-builder resolved by the patch, so [onOptionsBuilt] knows which
     * controller class it is looking at. The patch hooks the method itself.
     */
    fun bindOptionsBuilder(target: Method?) {
        if (target == null) return
        target.isAccessible = true
        hookMethod = target
        controllerClass = target.declaringClass
        FeatureStatusTracker.setHooked("ReelDownload")
        ModuleLog.line(
            "(NA|Reel) options builder: " +
                target.declaringClass.name + "." + target.name
        )
    }

    /**
     * True when `adder` exposes the reel-menu button-adder method
     * `(Context, View.OnClickListener, String, int)` — the same shape
     * [onOptionsBuilt] invokes. Identifies the correct options-builder overload
     * structurally, so it survives obfuscated renames across Instagram versions.
     */
    fun hasButtonAdderMethod(adder: Class<*>?): Boolean {
        if (adder == null) return false
        for (m in adder.declaredMethods) {
            val p = m.parameterTypes
            if (p.size == 4 &&
                Context::class.java.isAssignableFrom(p[0]) &&
                View.OnClickListener::class.java.isAssignableFrom(p[1]) &&
                p[2] == String::class.java &&
                p[3] == Int::class.javaPrimitiveType
            ) return true
        }
        return false
    }

    /**
     * Fallback index resolver: structurally locates the carousel position holder on the
     * controller. The holder is the unique non-framework field whose class has exactly
     * ONE int field — this property survives obfuscation renames across IG versions.
     * Values outside [0, 200) are excluded to filter out config constants.
     * Result is cached after first resolution.
     */
    private fun findReelCarouselIndex(controller: Any?): Int {
        if (controller == null) return 0

        val outer = cachedOuterField
        val inner = cachedInnerField
        if (outer != null && inner != null) {
            try {
                val holder = outer.get(controller)
                if (holder != null) return inner.getInt(holder)
            } catch (ignored: Throwable) {
            }
            cachedOuterField = null
            cachedInnerField = null
        }

        var bestIdx = Int.MAX_VALUE
        var bestOuter: Field? = null
        var bestInner: Field? = null

        var c: Class<*>? = controller.javaClass
        while (c != null && c != Any::class.java) {
            for (outerF in c.declaredFields) {
                if (outerF.type.isPrimitive) continue
                val pkg = outerF.type.name
                if (pkg.startsWith("android.") || pkg.startsWith("java.") ||
                    pkg.startsWith("androidx.") || pkg.startsWith("kotlin.")
                ) continue
                outerF.isAccessible = true
                val nested: Any? = try {
                    outerF.get(controller)
                } catch (ignored: Throwable) {
                    continue
                }
                if (nested == null) continue

                var singleIntField: Field? = null
                var intCount = 0
                var nc: Class<*>? = nested.javaClass
                while (nc != null && nc != Any::class.java) {
                    val npkg = nc.name
                    if (npkg.startsWith("android.") || npkg.startsWith("java.") ||
                        npkg.startsWith("androidx.") || npkg.startsWith("kotlin.")
                    ) break
                    for (nf in nc.declaredFields) {
                        if (nf.type != Int::class.javaPrimitiveType) continue
                        intCount++
                        singleIntField = nf
                        if (intCount > 1) break
                    }
                    if (intCount > 1) break
                    nc = nc.superclass
                }

                if (intCount == 1 && singleIntField != null) {
                    singleIntField.isAccessible = true
                    try {
                        val idx = singleIntField.getInt(nested)
                        if (idx in 0..199 && idx < bestIdx) {
                            bestIdx = idx
                            bestOuter = outerF
                            bestInner = singleIntField
                        }
                    } catch (ignored: Throwable) {
                    }
                }
            }
            c = c.superclass
        }

        if (bestOuter != null) {
            cachedOuterField = bestOuter
            cachedInnerField = bestInner
            return bestIdx
        }
        return 0
    }

    /**
     * Primary index resolver: walks the activity's live view hierarchy for a
     * ViewPager / ViewPager2 / ReboundViewPager / horizontal RecyclerView whose adapter
     * item count equals `carouselSize` and returns its current data index.
     * Multiple unrelated carousels can coincidentally share the same item count (e.g.
     * two feed posts both showing 4 photos) — trusting the first DFS hit in that case
     * previously misattributed the index to the wrong post. So every match is collected
     * and the result is only trusted when exactly one candidate matches; otherwise the
     * caller falls back to the data-layer field.
     *
     * @return current position [0, carouselSize), or -1 if not found / ambiguous
     */
    internal fun findCarouselIndexFromView(ctx: Context?, carouselSize: Int): Int {
        if (ctx !is Activity) return -1
        return try {
            val root = ctx.window.decorView
            val matches = ArrayList<Int>()
            collectCarouselMatches(root, carouselSize, matches)
            if (matches.size == 1) matches[0] else -1
        } catch (ignored: Throwable) {
            -1
        }
    }

    /** Returns adapter item count, trying RecyclerView-style then PagerAdapter-style. */
    private fun adapterCount(adapter: Any): Int {
        try {
            return adapter.javaClass.getMethod("getItemCount").invoke(adapter) as Int
        } catch (ignored: Throwable) {
        }
        try {
            return adapter.javaClass.getMethod("getCount").invoke(adapter) as Int
        } catch (ignored: Throwable) {
        }
        return -1
    }

    /**
     * Recursive DFS over the view tree, collecting the resolved index of every carousel
     * whose adapter size matches — does not stop at the first hit. ViewPager / ViewPager2 /
     * ReboundViewPager are AndroidX / Instagram common-UI classes — stable names, no obfuscation.
     */
    private fun collectCarouselMatches(view: View, carouselSize: Int, out: MutableList<Int>) {
        val cn = view.javaClass.name

        // ViewPager / ViewPager2 / ReboundViewPager and any subclass
        if (cn.contains("ViewPager")) {
            try {
                val adapter = view.javaClass.getMethod("getAdapter").invoke(view)
                if (adapter != null && adapterCount(adapter) == carouselSize) {
                    // Standard pagers: getCurrentItem()
                    // ReboundViewPager (Instagram looping carousel): getCurrentDataIndex()
                    for (getter in arrayOf(
                        "getCurrentItem", "getCurrentDataIndex",
                        "getCurrentWrappedDataIndex", "getCurrentRawDataIndex"
                    )) {
                        try {
                            val cur = view.javaClass.getMethod(getter).invoke(view) as Int
                            if (cur >= 0) {
                                out.add(cur)
                                break
                            }
                        } catch (ignored: NoSuchMethodException) {
                        }
                    }
                }
            } catch (ignored: Throwable) {
            }
        }

        // Horizontal RecyclerView (carousel, not the vertical feed list)
        if (cn.contains("RecyclerView")) {
            try {
                val adapter = view.javaClass.getMethod("getAdapter").invoke(view)
                if (adapter != null && adapterCount(adapter) == carouselSize) {
                    var lm = view.javaClass.getMethod("getLayoutManager").invoke(view)
                    if (lm != null) {
                        try {
                            val orientation =
                                lm.javaClass.getMethod("getOrientation").invoke(lm) as Int
                            if (orientation != 0 /* HORIZONTAL */) lm = null
                        } catch (ignored: Throwable) {
                        }
                        if (lm != null) {
                            var pos: Int? = null
                            try {
                                val p = lm.javaClass
                                    .getMethod("findFirstCompletelyVisibleItemPosition")
                                    .invoke(lm) as Int
                                if (p >= 0) pos = p
                            } catch (ignored: Throwable) {
                            }
                            if (pos == null) {
                                try {
                                    val p = lm.javaClass
                                        .getMethod("findFirstVisibleItemPosition")
                                        .invoke(lm) as Int
                                    if (p >= 0) pos = p
                                } catch (ignored: Throwable) {
                                }
                            }
                            if (pos != null) out.add(pos)
                        }
                    }
                }
            } catch (ignored: Throwable) {
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectCarouselMatches(view.getChildAt(i), carouselSize, out)
            }
        }
    }

    fun onOptionsBuilt(param: XC_MethodHook.MethodHookParam) {
        try {
            val controller = param.thisObject
            val media = param.args[0]
            val buttonAdder = param.args[1]

            if (activityField == null) {
                for (f in controller.javaClass.declaredFields) {
                    if (Activity::class.java.isAssignableFrom(f.type)) {
                        f.isAccessible = true
                        activityField = f
                        break
                    }
                }
            }
            val actField = activityField
            if (actField == null) {
                ModuleLog.line("(NA|Reel) ❌ no Activity field on controller")
                return
            }

            val activity = actField.get(controller) as? Activity ?: return

            if (buttonAdderMethod == null) {
                for (m in buttonAdder.javaClass.declaredMethods) {
                    val p = m.parameterTypes
                    if (p.size != 4) continue
                    if (!Context::class.java.isAssignableFrom(p[0])) continue
                    if (!View.OnClickListener::class.java.isAssignableFrom(p[1])) continue
                    if (p[2] != String::class.java) continue
                    if (p[3] != Int::class.javaPrimitiveType) continue
                    m.isAccessible = true
                    buttonAdderMethod = m
                    break
                }
            }
            val adderMethod = buttonAdderMethod
            if (adderMethod == null) {
                ModuleLog.line("(NA|Reel) ❌ buttonAdderMethod not found")
                return
            }

            val icon = resolveDownloadIcon(activity)

            adderMethod.invoke(
                buttonAdder, activity,
                View.OnClickListener { startReelDownload(activity, media, controller) },
                I18n.t(activity, R.string.ig_dl_title), icon
            )
        } catch (t: Throwable) {
            ModuleLog.line("(NA|Reel) ❌ onOptionsBuilt: $t")
        }
    }

    private fun startReelDownload(ctx: Context, media: Any?, controller: Any?) {
        var username = FeedVideoDownloadHook.extractUsernameFromMediaObject(media)
        if (username == null) username = "reel"

        var mediaId = "0"
        try {
            val id = media?.javaClass?.getMethod("getId")?.invoke(media)
            if (id is String && id.isNotEmpty()) mediaId = id
        } catch (ignored: Throwable) {
        }

        val videoUrl = FeedVideoDownloadHook.bestVideoUrlFromMedia(media)

        if (videoUrl != null) {
            val fn = FeedVideoDownloadHook.buildFilename(username, "reel", mediaId, true)
            val finalUser = username
            Toast.makeText(
                ctx, I18n.t(ctx, R.string.ig_toast_downloading_reel), Toast.LENGTH_SHORT
            ).show()
            FeedVideoDownloadHook.executor.submit {
                try {
                    val delegated =
                        FeedVideoDownloadHook.downloadAndSave(ctx, videoUrl, fn, true, finalUser)
                    if (!delegated) {
                        FeedVideoDownloadHook.mainHandler.post {
                            Toast.makeText(
                                ctx, I18n.t(ctx, R.string.ig_toast_reel_saved),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Throwable) {
                    FeedVideoDownloadHook.mainHandler.post {
                        Toast.makeText(
                            ctx, I18n.t(ctx, R.string.ig_toast_reel_failed, e.message),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            return
        }

        // No direct video — may be a photo carousel reel
        val allUrls = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, media)
        if (allUrls.isEmpty()) {
            Toast.makeText(
                ctx, I18n.t(ctx, R.string.ig_toast_reel_url_not_found), Toast.LENGTH_SHORT
            ).show()
            return
        }

        // Primary: live view state (always correct). Fallback: data-layer field (stale at slide 0).
        val viewIndex = findCarouselIndexFromView(ctx, allUrls.size)
        val currentIndex = if (viewIndex >= 0) viewIndex else findReelCarouselIndex(controller)

        val finalUsername = username
        val finalMediaId = mediaId
        FeedVideoDownloadHook.mainHandler.post {
            FeedVideoDownloadHook.showPostDownloadDialog(
                ctx, allUrls, finalUsername, finalMediaId, currentIndex
            )
        }
    }

    /** Reads the icon drawable ID from MediaOption$Option.DOWNLOAD enum value. */
    private fun resolveDownloadIcon(ctx: Context): Int {
        try {
            val optionClass = ctx.classLoader
                .loadClass("com.instagram.feed.media.mediaoption.MediaOption\$Option")
            val values = optionClass.getMethod("values").invoke(null) as Array<*>
            for (value in values) {
                if (value != null && value.toString().contains("DOWNLOAD")) {
                    val f = value.javaClass.getField("iconDrawable")
                    return f.getInt(value)
                }
            }
        } catch (ignored: Throwable) {
        }
        return 0
    }
}
