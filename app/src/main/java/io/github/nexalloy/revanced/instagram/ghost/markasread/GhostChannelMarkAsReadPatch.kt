package io.github.nexalloy.revanced.instagram.ghost.markasread

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import app.morphe.extension.shared.Logger
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private const val CHANNEL_TAG = "ie_channel_seen"

// Cached once on first use — resource IDs are constant for a given app install.
@Volatile private var sCachedSeenStateId = 0
@Volatile private var sCachedHeaderButtonsId = 0

/**
 * Ghost Channel Mark As Read
 *
 * Hooks [View.onAttachedToWindow] and, when the attached view is the
 * `seen_state_text` view inside a broadcast channel thread, injects an
 * on-click listener that scrolls the message list to the bottom (triggering
 * Instagram's built-in "mark as read" logic) and then scrolls back — making
 * it look as though you read the channel while keeping Ghost Mode active.
 *
 * The hook bails early for audio-call, video-call, and blend contexts so it
 * only fires inside regular broadcast channels.
 */
val GhostChannelMarkAsRead = patch(
    name = "Ghost channel mark as read",
    description = "Lets you silently mark a broadcast channel as read while Ghost Mode is enabled. " +
            "Injects a tap target on the 'seen' label inside the channel thread.",
) {
    // View is a framework class, so there is nothing to fingerprint: hook it directly.
    runCatching {
        View::class.java.getDeclaredMethod("onAttachedToWindow").hookMethod {
            after { param ->
                val view = param.thisObject as? View ?: return@after
                val context = view.context ?: return@after

                if (sCachedSeenStateId == 0) {
                    @SuppressLint("DiscouragedApi")
                    val id = context.resources.getIdentifier("seen_state_text", "id", context.packageName)
                    sCachedSeenStateId = id
                }

                if (sCachedSeenStateId == 0 || view.id != sCachedSeenStateId) return@after
                val seenTextView = view as? TextView ?: return@after

                // Skip audio/video-call and blend screens.
                if (sCachedHeaderButtonsId == 0) {
                    @SuppressLint("DiscouragedApi")
                    val id = context.resources.getIdentifier("header_right_buttons", "id", context.packageName)
                    sCachedHeaderButtonsId = id
                }

                if (sCachedHeaderButtonsId != 0) {
                    val container = view.rootView.findViewById<View>(sCachedHeaderButtonsId)
                    if (container is ViewGroup) {
                        for (i in 0 until container.childCount) {
                            val desc = container.getChildAt(i).contentDescription?.toString()
                                ?.lowercase() ?: continue
                            if (desc.contains("audio call") || desc.contains("video call") || desc.contains("blend")) {
                                return@after
                            }
                        }
                    }
                }

                updateChannelSeen(seenTextView)
            }
        }
    }.onFailure { Logger.printException({ "GhostChannelMarkAsRead hook failed" }, it) }
}

// ──────────────────────────────────────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────────────────────────────────────

private fun updateChannelSeen(textView: TextView) {
    if (textView.tag == CHANNEL_TAG) return
    textView.tag = CHANNEL_TAG

    // Visual indicator that the element is interactive / modded.
    textView.setTextColor(Color.CYAN)

    textView.setOnClickListener { triggerChannelSeen(textView) }

    // Append ghost emoji so the user knows the tap is available.
    val current = textView.text.toString()
    if (!current.contains("👻")) {
        textView.text = "$current 👻"
    }
}

private fun triggerChannelSeen(view: View) {
    try {
        val ctx: Context = view.context
        @SuppressLint("DiscouragedApi")
        val messageListId = ctx.resources.getIdentifier(
            "message_list", "id", ctx.packageName
        )

        val messageList = view.rootView.findViewById<View>(messageListId)
        if (messageList is ViewGroup) {
            // scrollBy is capped by RecyclerView's LayoutManager to the actual
            // content bottom, so 100 000 px is effectively "scroll to end".
            messageList.scrollBy(0, 100_000)

            messageList.scrollBy(0, -300)

            view.postDelayed({
                messageList.scrollBy(0, 300)
                Toast.makeText(
                    ctx,
                    "Channel marked as read 👻",
                    Toast.LENGTH_SHORT
                ).show()
            }, 400)
        }
    } catch (e: Exception) {
        Logger.printException({ "GhostChannelMarkAsRead trigger failed" }, e)
    }
}
