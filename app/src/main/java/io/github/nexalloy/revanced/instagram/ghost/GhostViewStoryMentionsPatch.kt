package io.github.nexalloy.revanced.instagram.ghost

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.graphics.Color
import app.morphe.extension.shared.Logger
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private const val MENTIONS_BTN_TAG = "ie_story_mentions_btn"

// Cached resource ID
@Volatile private var sCachedStoryOptionsId = 0

val GhostViewStoryMentions = patch(
    name = "View story mentions",
    description = "Adds a button in story options to view (and open) all tagged users " +
            "— including mentions hidden from the story viewer.",
) {
    // View is a framework class, so there is nothing to fingerprint: hook it directly.
    runCatching {
        View::class.java.getDeclaredMethod("onAttachedToWindow").hookMethod {
            after { param ->
                val view = param.thisObject as? View ?: return@after
                val ctx = view.context ?: return@after

                // The story options panel is the container the button is attached to.
                if (sCachedStoryOptionsId == 0) {
                    @SuppressLint("DiscouragedApi")
                    val id = ctx.resources.getIdentifier(
                        "reel_viewer_options_container", "id", ctx.packageName
                    ).takeIf { it != 0 }
                        ?: ctx.resources.getIdentifier("story_viewer_options", "id", ctx.packageName)
                    sCachedStoryOptionsId = id
                }

                if (sCachedStoryOptionsId == 0 || view.id != sCachedStoryOptionsId) return@after
                val container = view as? ViewGroup ?: return@after

                injectMentionsButton(container, ctx)
            }
        }
    }.onFailure { Logger.printException({ "GhostViewStoryMentions hook failed" }, it) }
}

// ──────────────────────────────────────────────────────────────────────────────
// Button injection
// ──────────────────────────────────────────────────────────────────────────────

private fun injectMentionsButton(container: ViewGroup, ctx: Context) {
    if (container.findViewWithTag<View>(MENTIONS_BTN_TAG) != null) return

    val btn = ImageButton(ctx).apply {
        tag = MENTIONS_BTN_TAG
        setImageResource(android.R.drawable.ic_dialog_info)
        setColorFilter(Color.WHITE)
        background = null
        contentDescription = "View story mentions"

        val size = dp(ctx, 36)
        layoutParams = LinearLayout.LayoutParams(size, size)

        setOnClickListener {
            // Try to find a media/story object in the container's tag or nearby views
            val mediaObj = findMediaObject(container) ?: run {
                showMentionsDialog(ctx, emptyList())
                return@setOnClickListener
            }
            val mentions = extractMentions(mediaObj)
            showMentionsDialog(ctx, mentions)
        }
    }

    container.post { container.addView(btn) }
}

// ──────────────────────────────────────────────────────────────────────────────
// Media object discovery via view tags
// ──────────────────────────────────────────────────────────────────────────────

private fun findMediaObject(root: ViewGroup): Any? {
    // Walk up the view tree looking for a tag that is a non-Android object
    var v: View? = root
    repeat(6) {
        val tag = v?.tag
        if (tag != null && !tag.javaClass.name.startsWith("android") &&
            !tag.javaClass.name.startsWith("java")
        ) return tag
        v = (v?.parent as? View)
    }
    return null
}

// ──────────────────────────────────────────────────────────────────────────────
// Mention extraction via reflection
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Extracts usernames from a media/story object using reflection.
 * Looks for:
 *  - String fields containing "@"
 *  - Collection/Array fields whose items have a "username" String field
 */
private fun extractMentions(obj: Any): List<String> {
    val results = mutableSetOf<String>()

    fun scanObject(target: Any?, depth: Int) {
        if (target == null || depth > 3) return
        val cls = target.javaClass
        if (cls.name.startsWith("java") || cls.name.startsWith("android")) return

        for (f in cls.declaredFields) {
            f.isAccessible = true
            try {
                val value = f.get(target) ?: continue
                when {
                    f.type == String::class.java -> {
                        val s = value as String
                        if (s.startsWith("@") || (s.isNotEmpty() && !s.contains(" ") &&
                                    f.name.lowercase().contains("user"))
                        ) results.add(s.trimStart('@'))
                    }
                    value is Iterable<*> -> value.forEach { scanObject(it, depth + 1) }
                    value is Array<*> -> value.forEach { scanObject(it, depth + 1) }
                    else -> {
                        // Look for username field on nested objects
                        val userField = runCatching {
                            value.javaClass.getDeclaredField("username")
                                .also { it.isAccessible = true }
                        }.getOrNull()
                        if (userField != null) {
                            val username = userField.get(value) as? String
                            if (!username.isNullOrBlank()) results.add(username)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    scanObject(obj, 0)
    return results.toList().sorted()
}

// ──────────────────────────────────────────────────────────────────────────────
// Dialog
// ──────────────────────────────────────────────────────────────────────────────

private fun showMentionsDialog(ctx: Context, mentions: List<String>) {
    if (mentions.isEmpty()) {
        AlertDialog.Builder(ctx)
            .setTitle("Story Mentions")
            .setMessage("No mentions found in this story.")
            .setPositiveButton("OK", null)
            .show()
        return
    }

    val items = mentions.map { "@$it" }.toTypedArray()
    AlertDialog.Builder(ctx)
        .setTitle("Story Mentions (${items.size})")
        .setItems(items) { _, which ->
            val username = mentions[which]
            try {
                // Try Instagram deep link first, fall back to browser
                val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse("instagram://user?username=$username"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(deepLink)
            } catch (_: Exception) {
                val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com/$username"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(web) }
            }
        }
        .setNegativeButton("Close", null)
        .show()
}

private fun dp(ctx: Context, v: Int): Int =
    (v * ctx.resources.displayMetrics.density).toInt()
