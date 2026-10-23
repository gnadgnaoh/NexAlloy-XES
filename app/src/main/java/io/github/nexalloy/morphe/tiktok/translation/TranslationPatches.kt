package io.github.nexalloy.morphe.tiktok.translation

import android.os.Handler
import android.os.Looper
import app.morphe.extension.shared.Logger
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.morphe.tiktok.shared.TikTokUi
import io.github.nexalloy.patch
import java.lang.reflect.Method
import java.util.Locale

val TranslateComments = patch(
    name = "Translate comments",
    description = "Translates comments as they appear, using TikTok's own translator and the translation language set in TikTok, " +
        "so you don't have to tap See translation on each one. Comments already in your language are left alone.",
    use = false,
) {
    val batch = ::commentTranslateBatchFingerprint.method.apply { isAccessible = true }
    val getComment = ::commentCellCommentGetterFingerprint.method.apply { isAccessible = true }
    val commentClass = COMMENT_CLASS.findClassOrNull(classLoader) ?: error("Comment class not found")
    val contextClass = batch.parameterTypes[1]
    val languages = LanguagePolicy(classLoader)
    val translator = CommentTranslator(batch, contextClass, commentClass, languages)

    ::commentCellBindFingerprint.hookMethod {
        after { param ->
            val comment = runCatching { getComment.invoke(param.thisObject) }.getOrNull() ?: return@after
            translator.offer(comment)
        }
    }
    batch.hookMethod {
        before { param -> param.args[1]?.let { translator.context = it } }
    }
}

private class LanguagePolicy(loader: ClassLoader) {
    private val service: Any? = runCatching {
        "com.ss.android.ugc.aweme.translation.service.TranslationLangKevaServiceImpl".findClassOrNull(loader)
            ?.getDeclaredConstructor()?.newInstance()
    }.getOrNull()

    private val targetGetter: Method? = service?.javaClass?.declaredMethods
        ?.firstOrNull { it.parameterCount == 0 && it.returnType == String::class.java }?.apply { isAccessible = true }

    private val dntSource: Pair<Method, Method>? = service?.javaClass?.declaredMethods?.firstNotNullOfOrNull { provider ->
        if (provider.parameterCount != 0 || provider.returnType == Void.TYPE) return@firstNotNullOfOrNull null
        val list = runCatching { provider.returnType.getMethod("getSelectedDoNotTranslateLanguageCodes") }.getOrNull()
            ?: return@firstNotNullOfOrNull null
        provider.isAccessible = true
        provider to list
    }

    fun target(): String? {
        val chosen = runCatching { targetGetter?.invoke(service) as? String }.getOrNull()
        return primary(chosen?.takeIf { it.isNotBlank() } ?: TikTokUi.app()?.resources?.configuration?.locales?.get(0)?.toLanguageTag())
    }

    fun doNotTranslate(): Set<String> = runCatching {
        val (provider, list) = dntSource ?: return emptySet()
        val settings = provider.invoke(service) ?: return emptySet()
        (list.invoke(settings) as? Array<*>)?.mapNotNull { primary(it as? String) }?.toSet()
    }.getOrNull().orEmpty()

    companion object {
        fun primary(tag: String?): String? = tag?.trim()?.replace('_', '-')?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }?.substringBefore('-')
    }
}

private class CommentTranslator(
    private val batch: Method,
    private val contextClass: Class<*>,
    commentClass: Class<*>,
    private val languages: LanguagePolicy,
) {
    @Volatile
    var context: Any? = null

    private val main = Handler(Looper.getMainLooper())
    private val queued = LinkedHashMap<String, Any>()
    private val requested = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 2000
    }
    private var scheduled = false
    private var failures = 0

    private val cid = commentClass.getMethod("getCid")
    private val isTranslated = commentClass.getMethod("isTranslated")
    private val translatable = commentClass.getMethod("isCommentTranslatable")
    private val language = commentClass.getMethod("getCommentLanguage")

    fun offer(comment: Any) {
        if (failures >= MAX_FAILURES) return
        val id = runCatching { cid.invoke(comment) as? String }.getOrNull() ?: return
        if (requested.containsKey(id) || queued.containsKey(id)) return
        if (runCatching { isTranslated.invoke(comment) == true }.getOrDefault(true)) return
        if (runCatching { translatable.invoke(comment) == false }.getOrDefault(false)) return
        queued[id] = comment
        if (!scheduled) {
            scheduled = true
            main.postDelayed(::flush, DEBOUNCE_MS)
        }
    }

    private fun flush() {
        scheduled = false
        if (queued.isEmpty()) return
        val target = languages.target()
        val skip = languages.doNotTranslate()
        val list = ArrayList<Any>()
        queued.forEach { (id, comment) ->
            requested[id] = true
            val lang = LanguagePolicy.primary(runCatching { language.invoke(comment) as? String }.getOrNull())
            if (lang != null && (lang == target || lang in skip)) return@forEach
            list += comment
        }
        queued.clear()
        if (list.isEmpty()) return
        try {
            val ctx = context ?: runCatching { contextClass.getDeclaredConstructor().newInstance() }.getOrNull()
            batch.invoke(null, list, ctx, false)
        } catch (e: Throwable) {
            failures++
            Logger.printException({ "TikTok: comment translation batch failed" }, e)
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 350L
        private const val MAX_FAILURES = 5
    }
}

val DontAutoTranslateVideos = patch(
    name = "Don't auto translate videos",
    description = "With TikTok's Always translate on, video descriptions and captions stay in the language they were made in. " +
        "Comments still follow TikTok's comment translation setting.",
    use = false,
) {
    val read = ::masterTranslationReadFingerprint.method
    val state = ::masterTranslationStateFingerprint.method.apply { isAccessible = true }
    val isOn = state.returnType.getMethod("isOn")
    val inComment = ThreadLocal.withInitial { 0 }

    var lastPublished: Boolean? = null
    ::publishedTranslationStateFingerprint.hookMethod {
        before { param ->
            val on = param.args[0] as? Boolean ?: return@before
            lastPublished = on
            param.args[0] = false
        }
    }

    ::commentTranslationDefaultsFingerprint.dexMethodList.forEach { dex ->
        dex.hookMethod {
            before { inComment.set(inComment.get() + 1) }
            after { inComment.set(inComment.get() - 1) }
        }
    }

    read.hookMethod {
        before { param ->
            if (inComment.get() > 0) {
                lastPublished?.let { param.result = it; return@before }
                param.result = runCatching { isOn.invoke(state.invoke(null)) as? Boolean }.getOrNull() ?: return@before
                return@before
            }
            param.result = false
        }
    }
}
