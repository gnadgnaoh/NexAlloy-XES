package io.github.nexalloy.revanced.instagram.time

import android.content.Context
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.hook.ScopedFrameworkHook

val ShowStoryTime = patch(
    name = "Show a story's exact time",
    description = "Shows the date and time a story was posted, like Oct 2, 3:45 PM, instead of how long ago.",
) {
    val builders = ::storyHeaderBuildersFingerprint.dexMethodList.map { it.toMethod() }
    val formatters = ::storyHeaderTimeFormattersFingerprint.dexMethodList.map { it.toMethod() }
    val label = ::storyTimeLabelFingerprint.method
    val posted = ::storyPostedSecondsGetterFingerprint.method.apply { isAccessible = true }
    val offset = ::storyClockOffsetFieldFingerprint.field.apply { isAccessible = true }
    check(builders.isNotEmpty() && formatters.isNotEmpty()) { "no story header time source found" }
    val reelItem = label.declaringClass
    val app = appContext
    val current = ThreadLocal<Pair<Context, Any>?>()
    builders.forEach { builder ->
        builder.hookMethod {
            before { param ->
                val ctx = param.args.firstOrNull { it is Context } as? Context ?: app
                val item = param.args.firstOrNull { reelItem.isInstance(it) }
                current.set(item?.let { ctx to it })
            }
            after { current.remove() }
        }
    }

    formatters.forEach { f ->
        val hook = ScopedFrameworkHook(f, after = { param ->
            val (ctx, item) = current.get() ?: return@ScopedFrameworkHook
            if (param.result !is String) return@ScopedFrameworkHook
            val seconds = posted.invoke(item) as Long + offset.getLong(null) / 1000
            if (seconds > 0) param.result = TimeFormat.exact(ctx, seconds * 1000)
        })
        builders.forEach { hook.installOn(it) }
    }
}
