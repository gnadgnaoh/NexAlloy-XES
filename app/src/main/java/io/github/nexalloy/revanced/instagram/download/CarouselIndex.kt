package io.github.nexalloy.revanced.instagram.download

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Which slide of a carousel is on screen. */
internal object CarouselIndex {

    /**
     * Looks for exactly one horizontal pager (ViewPager or RecyclerView) whose adapter holds
     * [size] items and returns its current position, or -1 when none or several match.
     * Main thread only.
     */
    fun fromWindow(ctx: Context?, size: Int): Int {
        if (size <= 1) return 0
        val activity = activityOf(ctx) ?: return -1
        return try {
            val matches = ArrayList<Int>(2)
            collect(activity.window.decorView, size, matches, 0)
            if (matches.size == 1) matches[0] else -1
        } catch (ignored: Throwable) {
            -1
        }
    }

    /** Last resort: a small int field on the menu controller that fits the carousel. */
    fun fromFields(obj: Any?, size: Int): Int {
        if (obj == null || size <= 1) return 0
        runCatching {
            val f = obj.javaClass.getDeclaredField("A00").apply { isAccessible = true }
            if (f.type == Int::class.javaPrimitiveType) {
                val v = f.getInt(obj)
                if (v in 0 until size) return v
            }
        }
        for (f in obj.javaClass.declaredFields) {
            if (f.type != Int::class.javaPrimitiveType) continue
            val v = runCatching { f.isAccessible = true; f.getInt(obj) }.getOrNull() ?: continue
            if (v in 1 until size) return v
        }
        return 0
    }

    fun activityOf(context: Context?): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun collect(view: View, size: Int, out: MutableList<Int>, depth: Int) {
        if (depth > 40 || out.size > 1) return
        val name = view.javaClass.name
        if (name.contains("ViewPager")) pagerIndex(view, size)?.let { out.add(it) }
        else if (name.contains("RecyclerView")) recyclerIndex(view, size)?.let { out.add(it) }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collect(view.getChildAt(i), size, out, depth + 1)
        }
    }

    private fun pagerIndex(view: View, size: Int): Int? {
        val adapter = call(view, "getAdapter") ?: return null
        if (count(adapter) != size) return null
        for (getter in arrayOf("getCurrentItem", "getCurrentDataIndex", "getCurrentWrappedDataIndex", "getCurrentRawDataIndex")) {
            val v = call(view, getter) as? Int ?: continue
            if (v >= 0) return v
        }
        return null
    }

    private fun recyclerIndex(view: View, size: Int): Int? {
        val adapter = call(view, "getAdapter") ?: return null
        if (count(adapter) != size) return null
        val lm = call(view, "getLayoutManager") ?: return null
        val orientation = call(lm, "getOrientation") as? Int
        if (orientation != null && orientation != 0) return null // vertical: a feed, not a carousel
        for (getter in arrayOf("findFirstCompletelyVisibleItemPosition", "findFirstVisibleItemPosition")) {
            val v = call(lm, getter) as? Int ?: continue
            if (v >= 0) return v
        }
        return null
    }

    private fun count(adapter: Any): Int =
        (call(adapter, "getItemCount") ?: call(adapter, "getCount")) as? Int ?: -1

    private val methodCache = ConcurrentHashMap<String, Method>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    private fun call(target: Any, name: String): Any? {
        val key = target.javaClass.name + '#' + name
        if (key in missing) return null
        val m = methodCache[key] ?: runCatching { target.javaClass.getMethod(name) }.getOrNull()
            ?.also { methodCache[key] = it }
        if (m == null) {
            missing.add(key)
            return null
        }
        return runCatching { m.invoke(target) }.getOrNull()
    }
}
