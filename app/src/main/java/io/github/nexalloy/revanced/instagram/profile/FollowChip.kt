package io.github.nexalloy.revanced.instagram.profile

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.nexalloy.patch
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt

internal enum class Relation { FOLLOWS_YOU, FOLLOWING_EACH_OTHER, DOESNT_FOLLOW_YOU }

val FollowStatusChip = patch(
    name = "Show follow status as a chip",
    description = "Shows the follow status as a colored chip under the follower counts: blue for Follows you, green for Following each other, red for Doesn't follow you.",
) {
    dependsOn(FollowsYou)
    FollowChip.enabled = true
}

internal object FollowChip {
    private const val FOLLOWERS_COUNT = "profile_header_familiar_followers_value"
    private const val FOLLOWING_ICON = "instagram_user_following_outline_24"
    private const val NOT_FOLLOWING_ICON = "instagram_user_unfollow_outline_24"
    private const val MAX_DEPTH = 8

    @Volatile
    var enabled = false

    private val shown = WeakHashMap<View, Chip>()

    @Volatile
    private var countId = -1

    fun show(inside: View, relation: Relation, text: String, style: TextView?): Boolean {
        val block = counts(inside) ?: return false
        val chip = synchronized(shown) { shown.getOrPut(block) { Chip(block) } }
        chip.show(relation, text, style)
        return true
    }

    fun clear(inside: View) {
        val block = counts(inside) ?: return
        synchronized(shown) { shown.remove(block) }?.remove()
    }

    @SuppressLint("DiscouragedApi")
    private fun counts(inside: View): View? {
        if (countId == -1) countId = inside.resources.getIdentifier(FOLLOWERS_COUNT, "id", inside.context.packageName)
        val id = countId
        if (id == 0) return null
        var at: View = inside
        repeat(MAX_DEPTH) {
            val group = at as? ViewGroup ?: at.parent as? ViewGroup ?: return null
            val count = group.findViewById<View>(id)
            if (count != null && count != group) {
                var child: View = count
                while (child.parent !== group) child = child.parent as? View ?: return null
                return child.takeIf { it.visibility == View.VISIBLE }
            }
            at = group.parent as? View ?: return null
        }
        return null
    }

    private fun color(relation: Relation, light: Boolean): Int = when (relation) {
        Relation.FOLLOWING_EACH_OTHER -> if (light) Color.rgb(0x18, 0x80, 0x38) else Color.rgb(0x2E, 0xCC, 0x71)
        Relation.DOESNT_FOLLOW_YOU -> if (light) Color.rgb(0xD9, 0x30, 0x25) else Color.rgb(0xED, 0x49, 0x56)
        Relation.FOLLOWS_YOU -> if (light) Color.rgb(0x1A, 0x73, 0xE8) else Color.rgb(0x37, 0x97, 0xEF)
    }

    private fun light(context: Context, style: TextView?): Boolean {
        if (style != null) return Color.luminance(style.currentTextColor) < 0.25f
        return context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
    }

    @SuppressLint("DiscouragedApi", "UseCompatLoadingForDrawables")
    private fun icon(context: Context, name: String): Drawable? = runCatching {
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        if (id == 0) null else context.getDrawable(id)?.mutate()
    }.getOrNull()

    private class Chip(block: View) : View.OnLayoutChangeListener {
        private val block = WeakReference(block)
        private val pill = Pill(block.context)
        private var ownBottom = block.paddingBottom
        private val room = pill.intrinsicHeight + pill.dp(8f)

        init {
            block.overlay.add(pill)
            block.addOnLayoutChangeListener(this)
        }

        fun show(relation: Relation, text: String, style: TextView?) {
            val view = block.get() ?: return
            val context = view.context
            val follows = relation != Relation.DOESNT_FOLLOW_YOU
            pill.set(text, icon(context, if (follows) FOLLOWING_ICON else NOT_FOLLOWING_ICON), color(relation, light(context, style)), style?.typeface)
            if (view.paddingBottom != ownBottom + room) {
                ownBottom = view.paddingBottom
                view.setPaddingRelative(view.paddingStart, view.paddingTop, view.paddingEnd, ownBottom + room)
            }
            place()
        }

        fun remove() {
            val view = block.get() ?: return
            view.overlay.remove(pill)
            view.removeOnLayoutChangeListener(this)
            if (view.paddingBottom == ownBottom + room) {
                view.setPaddingRelative(view.paddingStart, view.paddingTop, view.paddingEnd, ownBottom)
            }
        }

        override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) = place()

        private fun place() {
            val view = block.get() ?: return
            pill.layoutDirection = view.layoutDirection
            val width = pill.intrinsicWidth
            val height = pill.intrinsicHeight
            val rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val left = if (rtl) view.width - view.paddingEnd - width else view.paddingStart
            val bottom = view.height - ownBottom
            pill.setBounds(left, bottom - height, left + width, bottom)
        }
    }

    private class Pill(context: Context) : Drawable() {
        private val metrics = context.resources.displayMetrics
        private val words = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, metrics)
        }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val shape = RectF()
        private val height: Int
        private val padding: Int
        private val iconSize: Int
        private val iconGap: Int
        private var text = ""
        private var icon: Drawable? = null

        init {
            outline.strokeWidth = maxOf(1, dp(1f)).toFloat()
            val font = words.fontMetrics
            height = maxOf(dp(28f), ceil(font.descent - font.ascent).toInt() + dp(8f))
            padding = dp(12f)
            iconSize = dp(16f)
            iconGap = dp(6f)
        }

        fun dp(value: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics).roundToInt()

        fun set(text: String, icon: Drawable?, color: Int, typeface: Typeface?) {
            this.text = text
            this.icon = icon
            words.color = color
            words.typeface = typeface
            outline.color = Color.argb(Color.alpha(color) * 2 / 5, Color.red(color), Color.green(color), Color.blue(color))
            icon?.setTint(color)
            invalidateSelf()
        }

        override fun getIntrinsicWidth(): Int =
            padding * 2 + (if (icon != null) iconSize + iconGap else 0) + ceil(words.measureText(text)).toInt()

        override fun getIntrinsicHeight(): Int = height

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            if (bounds.isEmpty) return
            val inset = outline.strokeWidth / 2
            shape.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
            val radius = shape.height() / 2
            canvas.drawRoundRect(shape, radius, radius, outline)
            val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
            val middle = bounds.centerY()
            val textWidth = words.measureText(text)
            val iconStart = if (rtl) bounds.right - padding - iconSize else bounds.left + padding
            icon?.let {
                it.setBounds(iconStart, middle - iconSize / 2, iconStart + iconSize, middle + iconSize / 2)
                it.draw(canvas)
            }
            val iconWidth = if (icon != null) iconSize + iconGap else 0
            val textStart = if (rtl) bounds.right - padding - iconWidth - textWidth else (bounds.left + padding + iconWidth).toFloat()
            val font = words.fontMetrics
            canvas.drawText(text, textStart, middle - (font.ascent + font.descent) / 2, words)
        }

        override fun setAlpha(alpha: Int) {
            words.alpha = alpha
            outline.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            words.colorFilter = colorFilter
            outline.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
