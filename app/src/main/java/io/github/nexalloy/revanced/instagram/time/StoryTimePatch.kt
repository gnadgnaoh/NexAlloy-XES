package io.github.nexalloy.revanced.instagram.time

import android.content.Context
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.hook.ScopedFrameworkHook

private val NO_ITEM = Any()

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
                param.setObjectExtra("na_outer", current.get())
                current.set(item?.let { ctx to it } ?: (ctx to NO_ITEM))
            }
            after { param -> current.set(param.getObjectExtra("na_outer") as? Pair<Context, Any>) }
        }
    }

    ScopedFrameworkHook(posted, after = { param ->
        val item = param.thisObject ?: return@ScopedFrameworkHook
        val ctx = current.get()?.first ?: app
        if (current.get()?.second !== item) current.set(ctx to item)
    }).also { hook -> builders.forEach { hook.installOn(it) } }

    formatters.forEach { f ->
        val hook = ScopedFrameworkHook(f, after = { param ->
            val (ctx, item) = current.get() ?: return@ScopedFrameworkHook
            if (item === NO_ITEM || param.result !is String) return@ScopedFrameworkHook
            val seconds = posted.invoke(item) as Long + offset.getLong(null) / 1000
            if (seconds > 0) param.result = TimeFormat.exact(ctx, seconds * 1000)
        })
        builders.forEach { hook.installOn(it) }
    }
}
