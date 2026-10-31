package io.github.nexalloy.morphe.tiktok.interaction

import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.hook.ScopedFrameworkHook
import java.text.NumberFormat

val CopyCommentsWithoutUsername = patch(
    name = "Copy comments without username",
    description = "When you copy a comment, you get just its text, without the username in front.",
) {
    ::commentClipBuilderFingerprint.hookMethod {
        before { param -> if (param.args[0] is String) param.args[0] = "" }
    }

    val commentClass = COMMENT_CLASS.findClassOrNull(classLoader) ?: error("Comment class not found")
    val getText = commentClass.getMethod("getText")
    val current = ThreadLocal<Any?>()
    val helper = ScopedFrameworkHook(::clipboardHelperFingerprint.method, before = { param ->
        val comment = current.get() ?: return@ScopedFrameworkHook
        val text = runCatching { getText.invoke(comment) as? String }.getOrNull() ?: return@ScopedFrameworkHook
        param.args[1] = text
    })
    ::commentCopyBuildersFingerprint.dexMethodList.forEach { dex ->
        val scope = dex.toMember()
        scope.hookMethod {
            before { param ->
                val comment = param.args.firstOrNull { commentClass.isInstance(it) }
                    ?: param.args.firstNotNullOfOrNull { arg -> arg?.let { fieldOfType(it, commentClass) } }
                current.set(comment)
            }
            after { current.remove() }
        }
        helper.installOn(scope)
    }
}

private fun fieldOfType(obj: Any, type: Class<*>): Any? {
    var c: Class<*>? = obj.javaClass
    while (c != null && c != Any::class.java) {
        for (f in c.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
            val v = runCatching { f.isAccessible = true; f.get(obj) }.getOrNull()
            if (type.isInstance(v)) return v
        }
        c = c.superclass
    }
    return null
}

val ShowExactCounts = patch(
    name = "Show exact counts",
    description = "Shows likes, comments and other counts as full numbers, like 1,234,567 instead of 1.2M.",
) {
    val formatters = ::countFormattersFingerprint.dexMethodList + ::compactCountFormattersFingerprint.dexMethodList
    check(formatters.isNotEmpty()) { "no count formatter" }
    ::localizedNumberFormatterFingerprint.hookMethod {
        before { param ->
            val n = param.args[0]
            val count = when (n) { is Long -> n; is Int -> n.toLong(); is Short -> n.toLong(); else -> return@before }
            if (count >= 0) param.result = NumberFormat.getIntegerInstance().format(count)
        }
    }
    formatters.distinct().forEach { dex ->
        dex.hookMethod {
            before { param ->
                val count = param.args[0] as? Long ?: return@before
                if (count >= 0) param.result = NumberFormat.getIntegerInstance().format(count)
            }
        }
    }
}

private const val USER_CHOSE_HD = 1

val AlwaysUploadInHd = patch(
    name = "Always upload in HD",
    description = "Posts every video you upload in HD, as if you'd turned on TikTok's own HD upload setting each time.",
) {
    val keva = "com.bytedance.keva.Keva".findClassOrNull(classLoader) ?: error("Keva not found")
    val getInt = keva.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
    val choice = ScopedFrameworkHook(getInt, after = { param ->
        if (param.args[0] == HD_CHOICE_KEY) param.result = USER_CHOSE_HD
    })
    val readers = ::hdChoiceReadersFingerprint.dexMethodList
    check(readers.isNotEmpty()) { "no HD choice reader" }
    readers.forEach { choice.installOn(it.toMember()) }
}
