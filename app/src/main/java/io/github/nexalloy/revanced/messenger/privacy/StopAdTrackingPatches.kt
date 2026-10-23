package io.github.nexalloy.revanced.messenger.privacy

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch
import java.util.Collections
import java.util.WeakHashMap

val StopAdAttributionUploads = patch(
    name = "Stop ad attribution uploads",
    description = "Stops the job that reads your phone's advertising ID and sends it to Meta with your ad tracking setting. " +
        "The ID itself doesn't change.",
) {
    ::attributionUploadFingerprint.hookMethod {
        before { param ->
            Logger.printDebug { "Messenger: ad attribution upload skipped" }
            param.result = null
        }
    }
}

val StopInboxAndAdLinkLogging = patch(
    name = "Stop inbox and ad link logging",
    description = "Stops two reports: which part of your chat list was on screen, which Messenger files under inbox ads, " +
        "and which ad brought you in when a link from an ad opens a chat.",
) {
    ::adEntryEventFingerprint.hookMethod { before { param -> param.result = null } }

    val events = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Any, Boolean>()))
    ::inboxVisibilityEventInitFingerprint.hookMethod {
        after { param -> if (param.args[0] == "inbox2_vr") events.add(param.thisObject) }
    }
    ::inboxVisibilityLogFingerprint.hookMethod {
        before { param -> if (events.remove(param.args[0])) param.result = null }
    }
}
