package io.github.nexalloy.morphe.tiktok.interaction

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.nexalloy.R
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.morphe.tiktok.shared.Model
import io.github.nexalloy.morphe.tiktok.shared.TikTokUi
import io.github.nexalloy.patch
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.WeakHashMap

val CopyBioAndIds = patch(
    name = "Copy bio and IDs",
    description = "Lets you long-press a bio to copy it, and adds share sheet buttons that copy a username, user ID or video ID.",
) {
    val tuxText = "com.bytedance.tux.input.TuxTextView".findClassOrNull(classLoader)
    ::bioBindFingerprints.dexMethodList.forEach { bind ->
        bind.hookMethod {
            after { param ->
                val view = (tuxText?.let { Model.ofType(param.thisObject, it) } as? TextView) ?: return@after
                CopyIds.onBio(view, param.args[0] as? String)
            }
        }
    }

    val pkgClass = BASE_SHARE_PACKAGE.findClassOrNull(classLoader) ?: error("BaseSharePackage not found")
    ::shareSnapshotFingerprint.hookMethod {
        after { param -> CopyIds.sharePackage = WeakReference(Model.ofType(param.thisObject, pkgClass)) }
    }
    ::shareActionPanelAttachFingerprint.hookMethod {
        after { param -> CopyIds.onActionPanel(param.thisObject as? FrameLayout) }
    }
    ::shareAppsPanelAttachFingerprint.hookMethod {
        after { param -> CopyIds.onAppsPanel(param.thisObject as? FrameLayout) }
    }
}

internal object CopyIds {
    private const val ROW_TAG = "nexalloy_copy_ids"

    private val bios = WeakHashMap<View, String?>()
    private val margins = WeakHashMap<View, Int>()

    @Volatile
    var sharePackage: WeakReference<Any?> = WeakReference(null)
    private var actionPanel = WeakReference<FrameLayout?>(null)
    private var appsPanel = WeakReference<FrameLayout?>(null)

    private class Target(val label: String, val text: String, val toast: String)

    fun onBio(view: TextView, bio: String?) {
        bios[view] = bio
        view.post { install(view) }
        view.postDelayed({ install(view) }, 600)
    }

    private fun install(view: TextView) {
        val current = longClickListener(view)
        if (current is BioCopy) return
        view.isLongClickable = true
        view.setOnLongClickListener(BioCopy(current))
    }

    private class BioCopy(private val tiktok: View.OnLongClickListener?) : View.OnLongClickListener {
        override fun onLongClick(v: View): Boolean {
            val text = bios[v]?.takeIf { it.isNotBlank() } ?: (v as? TextView)?.text?.toString()
            if (!text.isNullOrBlank() && TikTokUi.copy("TikTok bio", text.trim())) TikTokUi.toast(TikTokUi.t(R.string.tt_bio_copied))
            runCatching { tiktok?.onLongClick(v) }
            return true
        }
    }

    private fun longClickListener(view: View): View.OnLongClickListener? = runCatching {
        val info = View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }.get(view) ?: return null
        info.javaClass.getDeclaredField("mOnLongClickListener").apply { isAccessible = true }.get(info) as? View.OnLongClickListener
    }.getOrNull()

    private fun targets(pkg: Any?): List<Target> {
        pkg ?: return emptyList()
        val type = Model.string(pkg, "getItemType", "itemType")?.lowercase(Locale.ROOT)
        if (type == "user") {
            val user = Model.field(pkg, "user") ?: return emptyList()
            return listOfNotNull(
                Model.string(user, "getUniqueId", "uniqueId")?.let { Target(TikTokUi.t(R.string.tt_copy_username), it, TikTokUi.t(R.string.tt_username_copied)) },
                Model.string(user, "getUid", "uid")?.let { Target(TikTokUi.t(R.string.tt_copy_user_id), it, TikTokUi.t(R.string.tt_user_id_copied)) },
            )
        }
        val aweme = Model.field(pkg, "aweme") ?: return emptyList()
        return listOfNotNull(
            Model.string(aweme, "getAid", "aid")?.let { Target(TikTokUi.t(R.string.tt_copy_video_id), it, TikTokUi.t(R.string.tt_video_id_copied)) },
        )
    }

    fun onActionPanel(panel: FrameLayout?) {
        panel ?: return
        actionPanel = WeakReference(panel)
        panel.post {
            runCatching {
                inSameSheet(panel, appsPanel.get())?.let { showRow(it, emptyList()) }
                showRow(panel, targets(sharePackage.get()))
            }
        }
    }

    fun onAppsPanel(panel: FrameLayout?) {
        panel ?: return
        appsPanel = WeakReference(panel)
        panel.post {
            runCatching {
                val hasActions = inSameSheet(panel, actionPanel.get()) != null
                showRow(panel, if (hasActions) emptyList() else targets(sharePackage.get()))
            }
        }
    }

    private fun inSameSheet(panel: View, other: FrameLayout?): FrameLayout? {
        if (other == null || other === panel || !other.isAttachedToWindow) return null
        return other.takeIf { it.rootView === panel.rootView }
    }

    private fun showRow(panel: FrameLayout, targets: List<Target>) {
        var row = (0 until panel.childCount).map(panel::getChildAt).firstOrNull { it.tag == ROW_TAG } as? LinearLayout
        if (targets.isEmpty()) {
            row?.visibility = View.GONE
            reserve(panel, row, 0)
            return
        }
        val ctx = panel.context
        if (row == null) {
            row = LinearLayout(ctx).apply {
                tag = ROW_TAG
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 16), dp(ctx, 6), dp(ctx, 16), dp(ctx, 10))
            }
            panel.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        row.removeAllViews()
        val color = textColor(panel)
        targets.forEach { row.addView(chip(ctx, it, color)) }
        row.visibility = View.VISIBLE
        val width = if (panel.width > 0) panel.width else ctx.resources.displayMetrics.widthPixels
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        reserve(panel, row, row.measuredHeight)
    }

    private fun reserve(panel: FrameLayout, row: View?, height: Int) {
        for (i in 0 until panel.childCount) {
            val child = panel.getChildAt(i)
            if (child === row) continue
            val params = child.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            val original = margins[child] ?: if (height == 0) continue else params.bottomMargin.also { margins[child] = it }
            if (params.bottomMargin != original + height) {
                params.bottomMargin = original + height
                child.layoutParams = params
            }
        }
    }

    private fun chip(ctx: Context, target: Target, color: Int) = TextView(ctx).apply {
        text = target.label
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        isSingleLine = true
        setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6))
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 16).toFloat()
            setColor(Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)))
        }
        setOnClickListener { if (TikTokUi.copy("TikTok ID", target.text)) TikTokUi.toast(target.toast) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 8) }
    }

    private fun textColor(group: ViewGroup): Int {
        firstText(group)?.let { return it.currentTextColor }
        val a = group.context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        return try { a.getColor(0, Color.GRAY) } finally { a.recycle() }
    }

    private fun firstText(view: View): TextView? {
        if (view.tag == ROW_TAG) return null
        if (view is TextView && view.visibility == View.VISIBLE && !view.text.isNullOrEmpty()) return view
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) firstText(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun dp(ctx: Context, v: Int) = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics))
}

