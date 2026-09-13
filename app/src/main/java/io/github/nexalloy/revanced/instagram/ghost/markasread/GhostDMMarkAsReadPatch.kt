package io.github.nexalloy.revanced.instagram.ghost.markasread

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.instagram.ghost.seenStateFingerprint
import java.lang.reflect.Method

private const val MARK_AS_READ_BTN_TAG = "ie_mark_as_read_btn"

// Cached once on first use — resource IDs are constant for a given app install.
@Volatile private var sCachedComposerContainerId = 0

/**
 * Captures the real argument list Instagram passes to its internal
 * `mark_thread_seen-` call every time it happens naturally (e.g. when a
 * thread is opened or scrolled), and exposes the last-seen [java.lang.reflect.Method]
 * so it can be re-invoked later, outside of the original call site.
 *
 * This mirrors piko's `markChatAsRead` patch, which reflectively calls
 * Instagram's real thread-seen API directly using hand-derived class/method
 * names resolved at patch-build time. NexAlloy resolves the same method at
 * runtime via [seenStateFingerprint], so instead of re-deriving `threadId` /
 * `messageId` / `UserSession` from scratch, this captures the exact argument
 * list Instagram already constructed the last time it tried to mark the
 * thread as seen, and replays it verbatim.
 *
 * This is intentionally independent from Ghost Mode: it hooks
 * [seenStateFingerprint] itself (in `after`, so it never blocks the call),
 * and works regardless of whether Ghost Mode / [io.github.nexalloy.revanced.instagram.ghost.GhostSeenState]
 * is enabled.
 */
private object SeenCallCapture {
    @Volatile
    var method: Method? = null

    @Volatile
    private var lastArgs: Array<Any?>? = null

    fun capture(args: Array<Any?>) {
        lastArgs = args.copyOf()
    }

    fun latestArgs(): Array<Any?>? = lastArgs
}

/**
 * DM Mark As Read
 *
 * Hooks [View.onAttachedToWindow] and, when the attached view is
 * `row_thread_composer_buttons_container`, injects a 👁 [ImageButton] into
 * that container's parent [ViewGroup].
 *
 * Unlike Ghost Mode, this button is a *real*, non-anonymous mark-as-read:
 * tapping it directly invokes Instagram's internal `mark_thread_seen-`
 * function with the exact argument list Instagram itself last used when it
 * naturally tried to mark the thread as seen (captured via [SeenCallCapture]).
 *
 * This patch does not depend on, arm, or consume any Ghost Mode bypass flag.
 * The other person WILL see "Seen" after this button is tapped, regardless
 * of whether Ghost Mode is enabled elsewhere.
 */
val GhostDMMarkAsRead = patch(
    name = "DM mark as read",
    description = "Injects a button into the DM composer bar that marks the conversation as read on demand. " +
            "This sends a real read receipt — the other person will see \"Seen\".",
) {
    // Resolve the real mark_thread_seen- method once, and capture its real
    // argument list every time Instagram invokes it naturally. Uses `after`
    // so this never interferes with (or depends on) Ghost Mode's `before`
    // block that may block the call.
    runCatching {
        SeenCallCapture.method = ::seenStateFingerprint.method

        ::seenStateFingerprint.hookMethod {
            after { param -> SeenCallCapture.capture(param.args) }
        }
    }.onFailure { Logger.printException({ "DMMarkAsRead seen-state capture hook failed" }, it) }

    // View is a framework class, so there is nothing to fingerprint: hook it directly.
    runCatching {
        View::class.java.getDeclaredMethod("onAttachedToWindow").hookMethod {
            after { param ->
                val view = param.thisObject as? View ?: return@after

                if (sCachedComposerContainerId == 0) {
                    @SuppressLint("DiscouragedApi")
                    val id = view.context.resources.getIdentifier(
                        "row_thread_composer_buttons_container", "id", view.context.packageName
                    )
                    sCachedComposerContainerId = id
                }

                if (sCachedComposerContainerId == 0 || view.id != sCachedComposerContainerId) return@after

                val parent = view.parent as? ViewGroup ?: return@after
                injectMarkAsReadButton(parent)
            }
        }
    }.onFailure { Logger.printException({ "DMMarkAsRead hook failed" }, it) }
}

// ──────────────────────────────────────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────────────────────────────────────

private fun injectMarkAsReadButton(parent: ViewGroup) {
    // Guard: don't inject more than once per parent.
    if (parent.findViewWithTag<View>(MARK_AS_READ_BTN_TAG) != null) return

    val ctx: Context = parent.context

    val markAsReadBtn = ImageButton(ctx).apply {
        tag = MARK_AS_READ_BTN_TAG
        setImageResource(android.R.drawable.ic_menu_view)
        setColorFilter(Color.WHITE)
        background = null

        val size = dp(ctx, 35)
        layoutParams = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setMargins(dp(ctx, 5), 25, 0, 0)
        }

        setOnClickListener { triggerRealMarkAsSeen(ctx) }
    }

    // Post to avoid mutating the hierarchy during a layout pass.
    parent.post {
        parent.addView(markAsReadBtn, 3)
    }
}

/**
 * Directly invokes Instagram's real `mark_thread_seen-` function with the
 * most recently captured real argument list.
 */
private fun triggerRealMarkAsSeen(ctx: Context) {
    try {
        val method = SeenCallCapture.method
        if (method == null) {
            Logger.printDebug { "DMMarkAsRead: seen-state method not resolved yet" }
            Toast.makeText(ctx, "Not ready yet, try again", Toast.LENGTH_SHORT).show()
            return
        }

        val args = SeenCallCapture.latestArgs()
        if (args == null) {
            // Instagram hasn't naturally attempted a mark-as-seen call for
            // this thread yet, so there's nothing captured to replay.
            Logger.printDebug { "DMMarkAsRead: no captured mark_thread_seen- args yet" }
            Toast.makeText(
                ctx,
                "Open the chat and wait a moment before marking as read",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // The original method, not a reflective call: GhostSeenState hooks this very method
        // and would swallow a normal invoke.
        XposedBridge.invokeOriginalMethod(method, null, args)

        Toast.makeText(ctx, "Marked as read", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Logger.printException({ "DMMarkAsRead trigger failed" }, e)
        Toast.makeText(ctx, "Failed to mark as read", Toast.LENGTH_SHORT).show()
    }
}

private fun dp(ctx: Context, v: Int): Int =
    (v * ctx.resources.displayMetrics.density).toInt()
