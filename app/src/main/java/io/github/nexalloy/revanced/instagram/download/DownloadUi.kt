package io.github.nexalloy.revanced.instagram.download

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.nexalloy.R

internal enum class MediaKind(val tag: String) {
    POST("post"), REEL("reel"), STORY("story"), INSTANT("instant"), PROFILE("profile");

    fun startText(ctx: Context, isVideo: Boolean): String = when (this) {
        POST -> I18n.t(ctx, if (isVideo) R.string.ig_toast_downloading_video else R.string.ig_toast_downloading_photo)
        REEL -> I18n.t(ctx, R.string.ig_toast_downloading_reel)
        STORY -> I18n.t(ctx, if (isVideo) R.string.ig_toast_downloading_story_video else R.string.ig_toast_downloading_story_photo)
        INSTANT -> I18n.t(ctx, R.string.ig_instant_saving)
        PROFILE -> I18n.t(ctx, R.string.ig_toast_downloading_profile_pic)
    }

    fun doneText(ctx: Context, isVideo: Boolean): String = when (this) {
        POST -> I18n.t(ctx, if (isVideo) R.string.ig_toast_video_saved else R.string.ig_toast_photo_saved)
        REEL -> I18n.t(ctx, R.string.ig_toast_reel_saved)
        STORY -> I18n.t(ctx, R.string.ig_toast_story_saved)
        INSTANT -> I18n.t(ctx, R.string.ig_instant_saved)
        PROFILE -> I18n.t(ctx, R.string.ig_toast_profile_pic_saved)
    }
}

internal object DownloadUi {

    fun saveOne(ctx: Context, item: MediaItem, kind: MediaKind, username: String?, mediaId: String?, index: Int = -1) {
        MediaSaver.saveAll(
            ctx,
            listOf(MediaSaver.Job(item, username, kind.tag, mediaId, index)),
            kind.startText(ctx, item.isVideo),
            kind.doneText(ctx, item.isVideo),
        )
    }

    fun offerItems(
        ctx: Context,
        items: List<MediaItem>,
        kind: MediaKind,
        username: String?,
        mediaId: String?,
        currentIndex: Int,
    ) {
        if (items.isEmpty()) {
            MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_no_media_for_post))
            return
        }
        if (items.size == 1) {
            saveOne(ctx, items[0], kind, username, mediaId)
            return
        }
        val n = items.size
        val idx = if (currentIndex in 0 until n) currentIndex else 0
        showSheet(
            ctx,
            title = I18n.t(ctx, R.string.ig_dl_title),
            subtitle = I18n.t(ctx, R.string.ig_dl_carousel_subtitle, n),
            rows = listOf(
                Row(I18n.t(ctx, R.string.ig_dl_carousel_current, idx + 1, n), primary = true) {
                    saveOne(ctx, items[idx], kind, username, mediaId, idx)
                },
                Row(I18n.t(ctx, R.string.ig_dl_carousel_all, n)) {
                    MediaSaver.saveAll(
                        ctx,
                        items.mapIndexed { i, it -> MediaSaver.Job(it, username, kind.tag, mediaId, i) },
                        I18n.t(ctx, R.string.ig_toast_downloading_all_n_items, n),
                        I18n.t(ctx, R.string.ig_toast_all_items_saved, n),
                    )
                },
            ),
        )
    }

    fun offerStoryChoice(ctx: Context, imageUrl: String?, videoUrl: String?, username: String?, mediaId: String?) {
        val rows = ArrayList<Row>()
        imageUrl?.let { url ->
            rows.add(Row("📷  " + I18n.t(ctx, R.string.ig_story_download_photo)) {
                saveOne(ctx, MediaItem(url, false), MediaKind.STORY, username, mediaId)
            })
        }
        videoUrl?.let { url ->
            rows.add(Row("🎬  " + I18n.t(ctx, R.string.ig_story_download_video_music)) {
                saveOne(ctx, MediaItem(url, true), MediaKind.STORY, username, mediaId)
            })
        }
        showSheet(
            ctx,
            title = I18n.t(ctx, R.string.ig_story_download_choice_title),
            subtitle = I18n.t(ctx, R.string.ig_story_download_choice_subtitle),
            rows = rows,
            cancel = true,
        )
    }

    fun offerCopyLinks(ctx: Context, urls: List<String>) {
        if (urls.isEmpty()) {
            MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_copy_link_none))
            return
        }
        if (urls.size == 1) {
            copyToClipboard(ctx, urls[0], I18n.t(ctx, R.string.ig_copy_link_copied))
            return
        }
        val n = urls.size
        val rows = urls.mapIndexed { i, url ->
            Row(I18n.t(ctx, R.string.ig_copy_link_slide, i + 1, n)) {
                copyToClipboard(ctx, url, I18n.t(ctx, R.string.ig_copy_link_copied))
            }
        } + Row(I18n.t(ctx, R.string.ig_copy_link_all, n), primary = true) {
            copyToClipboard(ctx, urls.joinToString("\n"), I18n.t(ctx, R.string.ig_copy_link_copied_all, n))
        }
        showSheet(
            ctx,
            title = I18n.t(ctx, R.string.ig_copy_link_title),
            subtitle = I18n.t(ctx, R.string.ig_dl_carousel_subtitle, n),
            rows = rows,
            scrollable = true,
        )
    }

    private fun copyToClipboard(ctx: Context, text: String, confirmation: String) {
        MediaSaver.mainHandler.postDelayed({
            try {
                val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("NexAlloy", text))
                MediaSaver.toast(ctx, confirmation)
            } catch (t: Throwable) {
                ModuleLog.line("(NA|UI) clipboard: $t")
            }
        }, 350)
    }

    class Row(val label: String, val primary: Boolean = false, val action: () -> Unit)

    private class Palette(dark: Boolean) {
        val sheet = Color.parseColor(if (dark) "#1C1C1E" else "#F2F2F7")
        val text = if (dark) Color.WHITE else Color.parseColor("#1C1C1E")
        val subtext = Color.parseColor(if (dark) "#AEAEB2" else "#6C6C70")
        val accent = Color.parseColor("#0A84FF")
        val button = Color.parseColor(if (dark) "#3A3A3C" else "#E5E5EA")
        val handle = Color.parseColor(if (dark) "#48484A" else "#C7C7CC")
    }

    fun showSheet(
        ctx: Context,
        title: String,
        subtitle: String?,
        rows: List<Row>,
        cancel: Boolean = false,
        scrollable: Boolean = false,
    ) {
        try {
            val dp = ctx.resources.displayMetrics.density
            val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val c = Palette(dark)
            val dialog = Dialog(ctx)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            val sheet = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = round(c.sheet, 20f * dp)
                val h = (20 * dp).toInt()
                setPadding(h, (12 * dp).toInt(), h, (28 * dp).toInt())
            }
            sheet.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (4 * dp).toInt()).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = (16 * dp).toInt()
                }
                background = round(c.handle, 2f * dp)
            })
            sheet.addView(TextView(ctx).apply {
                text = title
                setTextColor(c.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTypeface(null, Typeface.BOLD)
            })
            if (!subtitle.isNullOrEmpty()) {
                sheet.addView(TextView(ctx).apply {
                    text = subtitle
                    setTextColor(c.subtext)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (2 * dp).toInt()
                        bottomMargin = (8 * dp).toInt()
                    }
                })
            }

            val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            for (row in rows) {
                list.addView(button(ctx, row.label, if (row.primary) c.accent else c.button,
                    if (row.primary) Color.WHITE else c.text, dp) {
                    dialog.dismiss()
                    row.action()
                })
            }
            if (scrollable && rows.size > 5) {
                val scroller = ScrollView(ctx)
                scroller.addView(list)
                scroller.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (300 * dp).toInt()
                )
                sheet.addView(scroller)
            } else {
                sheet.addView(list)
            }
            if (cancel) {
                sheet.addView(button(ctx, ctx.getString(android.R.string.cancel), c.button, c.text, dp) {
                    dialog.dismiss()
                })
            }

            dialog.setContentView(sheet)
            dialog.window?.let { w ->
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                w.setGravity(Gravity.BOTTOM)
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                val margin = (12 * dp).toInt()
                w.attributes = w.attributes.apply {
                    x = margin
                    y = margin
                }
            }
            dialog.show()
        } catch (t: Throwable) {
            ModuleLog.line("(NA|UI) sheet failed: $t")
            MediaSaver.toast(ctx, I18n.t(ctx, R.string.ig_toast_download_failed, t.message))
        }
    }

    private fun button(ctx: Context, label: String, bg: Int, fg: Int, dp: Float, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = round(bg, 14f * dp)
            val h = (20 * dp).toInt()
            val v = (14 * dp).toInt()
            setPadding(h, v, h, v)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * dp).toInt() }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun round(color: Int, radiusPx: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
    }

}
