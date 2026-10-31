package io.github.nexalloy.morphe.tiktok.interaction

import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.nexalloy.R
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.morphe.tiktok.shared.Model
import io.github.nexalloy.morphe.tiktok.shared.TikTokServices
import io.github.nexalloy.morphe.tiktok.shared.TikTokUi
import io.github.nexalloy.patch

val ShowFollowStatus = patch(
    name = "Show follow status",
    description = "Shows on a profile whether that person follows you back, and marks accounts in your follow lists that don't.",
) {
    TikTokServices.init(classLoader)
    val infoGetter = ::profileCommonInfoGetterFingerprint.method.apply { isAccessible = true }
    val userClass = "com.ss.android.ugc.aweme.profile.model.User".findClassOrNull(classLoader) ?: error("User class not found")
    val itemView = BASE_UI_COMPONENT.findClassOrNull(classLoader)!!.declaredFields
        .single { !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == View::class.java }.apply { isAccessible = true }

    ::profileHeaderTextBindFingerprint.hookMethod {
        after { param ->
            val item = param.thisObject
            val view = runCatching { itemView.get(item) }.getOrNull() as? TextView ?: return@after
            val info = runCatching { infoGetter.invoke(item) }.getOrNull() ?: return@after
            FollowStatus.onHeaderText(view, info)
        }
    }

    ::relationCellBindFingerprint.hookMethod {
        after { param ->
            val cell = runCatching { param.thisObject.javaClass.getMethod("getContentView").invoke(param.thisObject) }.getOrNull() as? ViewGroup
                ?: return@after
            FollowStatus.onRelationCell(cell, Model.ofType(param.args[0], userClass))
        }
    }
}

internal object FollowStatus {
    private const val FOLLOWING = 1
    private const val MUTUAL = 2
    private const val LABEL_TAG = "nexalloy_follow_status"
    private const val MAX_CLIMB = 4

    private fun headerLabel(mine: Int, theirs: Int): String? = when {
        theirs == FOLLOWING || mine == MUTUAL -> TikTokUi.t(R.string.tt_follows_you)
        mine == FOLLOWING -> TikTokUi.t(R.string.tt_not_following_back)
        else -> null
    }

    private fun listLabel(mine: Int, theirs: Int): String? =
        if (mine == FOLLOWING && theirs != FOLLOWING) TikTokUi.t(R.string.tt_not_following_back) else null

    private fun int(v: Any?) = (v as? Number)?.toInt() ?: 0

    private fun isMe(uid: String?) = uid != null && uid == TikTokServices.currentUserId()

    fun onHeaderText(text: TextView, info: Any) {
        val profile = Model.get(info, "getUserProfileInfo", "userProfileInfo")
        val relation = Model.get(info, "getUserRelationInfo", "userRelationInfo")
        val username = Model.string(profile, "getUsername", "username") ?: return
        val uid = Model.string(profile, "getUid", "uid")
        val label = if (isMe(uid)) null else headerLabel(
            int(Model.get(relation, "getFollowStatus", "followStatus")),
            int(Model.get(relation, "getFollowerStatus", "followerStatus")),
        )
        text.post { runCatching { if (isHandle(text.text, username)) place(text, label, null) } }
    }

    fun onRelationCell(cell: ViewGroup, user: Any?) {
        if (user == null) {
            cell.post { hideLabels(cell) }
            return
        }
        val handle = Model.string(user, "getUniqueId", "uniqueId")
        val nickname = Model.string(user, "getNickname", "nickname")
        val label = if (isMe(Model.string(user, "getUid", "uid"))) null else listLabel(
            int(Model.get(user, "getFollowStatus", "followStatus")),
            int(Model.get(user, "getFollowerStatus", "followerStatus")),
        )
        cell.post {
            runCatching {
                hideLabels(cell)
                if (label == null) return@runCatching
                val anchor = findText(cell, handle, true) ?: findText(cell, nickname, false) ?: return@runCatching
                place(anchor, label, cell)
            }
        }
    }

    private fun shown(text: CharSequence?): String =
        text?.toString()?.trimEnd(' ', ' ', '￼')?.trim().orEmpty()

    private fun isHandle(text: CharSequence?, handle: String?): Boolean {
        if (handle.isNullOrEmpty()) return false
        return shown(text).removePrefix("@") == handle.removePrefix("@")
    }

    private fun findText(view: View, value: String?, handle: Boolean): TextView? {
        if (value.isNullOrEmpty()) return null
        if (view is TextView) {
            if (view.tag == LABEL_TAG || view.visibility != View.VISIBLE) return null
            val match = if (handle) isHandle(view.text, value) else shown(view.text) == value.trim()
            return view.takeIf { match }
        }
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) findText(view.getChildAt(i), value, handle)?.let { return it }
        return null
    }

    private fun place(anchor: TextView, label: String?, limit: View?) {
        var child: View = anchor
        var row: LinearLayout? = null
        var rowChild: View? = null
        var parent = anchor.parent
        repeat(MAX_CLIMB) {
            if (parent !is ViewGroup) return@repeat
            val p = parent as ViewGroup
            if (p is LinearLayout) {
                if (p.orientation == LinearLayout.VERTICAL) return show(p, child, anchor, label, true)
                if (row == null) { row = p; rowChild = child }
            }
            if (p === limit) { row?.let { show(it, rowChild!!, anchor, label, false) }; return }
            child = p
            parent = p.parent
        }
        row?.let { show(it, rowChild!!, anchor, label, false) }
    }

    private fun show(layout: LinearLayout, after: View, anchor: TextView, label: String?, below: Boolean) {
        var view = (0 until layout.childCount).map(layout::getChildAt).firstOrNull { it is TextView && it.tag == LABEL_TAG } as? TextView
        if (label == null) { view?.visibility = View.GONE; return }
        if (view == null) {
            view = TextView(anchor.context).apply {
                tag = LABEL_TAG
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
            val gap = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, if (below) 2f else 6f, anchor.resources.displayMetrics))
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (below) {
                params.topMargin = gap
                params.gravity = (after.layoutParams as? LinearLayout.LayoutParams)?.gravity?.takeIf { it > 0 } ?: -1
            } else {
                params.marginStart = gap
                params.gravity = Gravity.CENTER_VERTICAL
            }
            layout.addView(view, minOf(layout.indexOfChild(after) + 1, layout.childCount), params)
        }
        val color = anchor.currentTextColor
        view.setTextColor(Color.argb(Math.round(Color.alpha(color) * 0.7f), Color.red(color), Color.green(color), Color.blue(color)))
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, anchor.textSize * 0.85f)
        view.typeface = anchor.typeface
        view.text = label
        view.visibility = View.VISIBLE
    }

    private fun hideLabels(root: View) {
        if (root is TextView) { if (root.tag == LABEL_TAG) root.visibility = View.GONE; return }
        if (root is ViewGroup) for (i in 0 until root.childCount) hideLabels(root.getChildAt(i))
    }
}
