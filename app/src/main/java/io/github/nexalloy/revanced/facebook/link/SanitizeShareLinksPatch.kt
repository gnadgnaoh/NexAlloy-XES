package io.github.nexalloy.revanced.facebook.link

import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import app.morphe.extension.shared.Logger
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val SanitizeShareLinks = patch(
    name = "Sanitize sharing links",
    description = "Removes Facebook's tracking tags from links you share or copy.",
) {
    val builders = buildList {
        runCatching {
            add(::externalShareTrackerFingerprint.dexMethod)
            addAll(::externalShareTrackerSiblingsFingerprint.dexMethodList)
        }.onFailure { Logger.printInfo { "Facebook: ExternalShareTracker not found: ${it.message}" } }
        addAll(::shareLinkAppendersFingerprint.dexMethodList)
    }
    builders.forEach { dm ->
        dm.hookMethod { after { param -> (param.result as? String)?.let { param.result = LinkShim.clean(it) } } }
    }

    ClipboardManager::class.java.getDeclaredMethod("setPrimaryClip", ClipData::class.java).hookMethod {
        before { param ->
            runCatching {
                val clip = param.args[0] as? ClipData ?: return@runCatching
                if (clip.itemCount != 1) return@runCatching
                val text = clip.getItemAt(0).text?.toString() ?: return@runCatching
                val cleaned = LinkShim.cleanText(text)
                if (cleaned != text) param.args[0] = ClipData.newPlainText(clip.description?.label, cleaned)
            }
        }
    }

    fun cleanShare(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_CHOOSER) {
            @Suppress("DEPRECATION")
            return cleanShare(intent.getParcelableExtra(Intent.EXTRA_INTENT))
        }
        if (intent.action != Intent.ACTION_SEND) return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return
        val cleaned = LinkShim.cleanText(text)
        if (cleaned != text) intent.putExtra(Intent.EXTRA_TEXT, cleaned)
    }
    Instrumentation::class.java.declaredMethods
        .filter { m -> m.name == "execStartActivity" && Intent::class.java in m.parameterTypes }
        .forEach { m ->
            m.hookMethod { before { param -> runCatching { cleanShare(param.args.firstOrNull { it is Intent } as? Intent) } } }
        }
}
