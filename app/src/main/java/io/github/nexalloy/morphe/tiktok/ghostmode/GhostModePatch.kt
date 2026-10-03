package io.github.nexalloy.morphe.tiktok.ghostmode

import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.callMethodOrNull
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.morphe.tiktok.shared.realMatches
import io.github.nexalloy.patch
import java.io.IOException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CancellationException

private const val TAG = "[TikTok ghost mode]"
private const val BLOCKED_MESSAGE = "Blocked by NexAlloy ghost mode"

/** A relative URL Retrofit accepts without the leading slash ("tiktok/story/view/report/v1"). */
private val RELATIVE_PATH = Regex("^[A-Za-z0-9_.\\-]+(/[A-Za-z0-9_.\\-]+)+/?$")

/*
 * Ported from HushFeed "Ghost mode" (GPL-3.0). It suppresses the client's own reports only;
 * nothing here changes what the server already knows, and online status is not touched.
 *
 * Why it is not a plain "return early" on the report methods: all four Retrofit reporters return a
 * lazy Call/Single/Observable or are suspend functions, and their callers go on to enqueue or
 * subscribe what they get. A null there was HushFeed's crash on opening a story, and the reason
 * other people's profiles lost their follower counts. HushFeed therefore rewrites every call site's
 * bytecode; an Xposed module cannot cut half a method, so the requests are stopped one layer down:
 *
 *  - Call/Single/Observable reporters: at com.bytedance.retrofit2.SsHttpCall (kept names), by the
 *    request path. execute() and enqueue() fail the way a dropped connection does, which every
 *    call site already handles (offline). The story-view and interaction callers only log the
 *    failure; none of them keeps the report to retry.
 *  - suspend reporters: on the kept-name wrapper (StoryApi), by cancelling the calling coroutine,
 *    which ends it quietly without reaching a crash handler.
 *
 * The paths are read from the Retrofit annotations of the interface methods at runtime, so an API
 * version bump (v1 -> v2) is followed without an update; KNOWN_REPORT_PATHS is only a fallback.
 */
val GhostMode = patch(
    name = "Ghost mode",
    description = "Stops TikTok from reporting what you look at: story views, profile visits and " +
        "the typing indicator. Your online status is unchanged.",
    use = false,
) {
    val installed = mutableListOf<String>()
    val skipped = mutableListOf<String>()

    fun optional(label: String, block: () -> Unit) {
        runCatching(block)
            .onSuccess { installed += label }
            .onFailure { skipped += "$label (${it.javaClass.simpleName}: ${it.message})" }
    }

    val reporters = REPORTERS.flatMap { (api, names) ->
        val apiClass = api.findClassOrNull(classLoader)
        names.mapNotNull { name ->
            val declared = apiClass?.declaredMethods?.firstOrNull { it.name == name }
            val path = declared?.let(::retrofitPath) ?: KNOWN_REPORT_PATHS[name] ?: return@mapNotNull null
            // Suspend functions return Object; reactive reporters return their Call/Rx type.
            val suspend = declared?.let { it.returnType == Any::class.java } ?: (name == "reportStoryReveal")
            Reporter(name, ReportRequestBlocker.normalize(path), suspend)
        }
    }
    Logger.printDebug { "$TAG reporters=$reporters" }

    optional("reportRequests") {
        val paths = reporters.filterNot { it.suspend }.map { it.path }.toSet()
        check(paths.isNotEmpty()) { "no report path resolved" }
        ReportRequestBlocker.install(classLoader, paths)
    }

    optional("suspendReports") {
        val names = reporters.filter { it.suspend }.map { it.name }.toSet()
        check(names.isNotEmpty()) { "no suspend reporter on this build" }
        val methods = REPORTER_WRAPPERS.mapNotNull { it.findClassOrNull(classLoader) }
            .flatMap { wrapper -> wrapper.declaredMethods.filter { it.name in names && it.returnType == Any::class.java } }
        check(methods.isNotEmpty()) { "no wrapper declares ${names.joinToString()}" }
        methods.forEach { method ->
            method.hookMethod {
                before { param -> param.throwable = CancellationException("NexAlloy ghost mode") }
            }
        }
    }

    // ITypingStatusSenderTimer's two (String)V methods: start and stop sending "typing...".
    optional("typingStatus") {
        val sender = TYPING_STATUS_SENDER_CLASS.findClassOrNull(classLoader) ?: error("$TYPING_STATUS_SENDER_CLASS not found")
        val methods = sender.declaredMethods.filter {
            !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(String::class.java))
        }
        check(methods.isNotEmpty()) { "TypingStatusSenderTimer has no (String)V method" }
        methods.forEach { it.hookMethod(XC_MethodReplacement.DO_NOTHING) }
    }

    // The play report a story view also sends; feed videos' play reports are left alone.
    optional("storyPlayStats") {
        val aweme = AWEME_CLASS.findClassOrNull(classLoader) ?: error("$AWEME_CLASS not found")
        val senders = ::awemeStatsSenderFingerprints.dexMethodList.realMatches()
        check(senders.size == 1) { "expected one play report sender, found ${senders.size}" }
        senders.single().toMethod().hookMethod {
            before { param ->
                val item = param.args.firstOrNull { aweme.isInstance(it) } ?: return@before
                if (isStory(item)) param.result = null
            }
        }
    }

    check(installed.isNotEmpty()) { "no ghost mode hook could be installed: ${skipped.joinToString("; ")}" }
    Logger.printInfo { "$TAG installed=[${installed.joinToString()}] skipped=[${skipped.joinToString("; ")}]" }
}

private data class Reporter(val name: String, val path: String, val suspend: Boolean)

private fun isStory(aweme: Any): Boolean {
    if (aweme.callMethodOrNull("getIsTikTokStory") == true) return true
    val type = aweme.callMethodOrNull("getAwemeType") as? Int ?: return false
    return type in STORY_AWEME_TYPES
}

/**
 * The relative URL of a Retrofit method: the String value of its HTTP-method annotation
 * (@GET/@POST/...), whatever the annotation's element is called.
 */
private fun retrofitPath(method: Method): String? {
    for (annotation in method.annotations) {
        val getters = annotation.annotationClass.java.declaredMethods
            .filter { it.parameterCount == 0 && it.returnType == String::class.java }
        for (getter in getters) {
            val value = runCatching {
                getter.isAccessible = true
                getter.invoke(annotation) as? String
            }.getOrNull()?.trim() ?: continue
            ReportRequestBlocker.pathOf(value)?.let { return it }
            if (RELATIVE_PATH.matches(value)) return "/$value"
        }
    }
    return null
}

internal object ReportRequestBlocker {

    @Volatile
    private var blocked: Set<String> = emptySet()

    fun install(classLoader: ClassLoader, paths: Set<String>) {
        val call = SS_HTTP_CALL_CLASS.findClassOrNull(classLoader) ?: error("$SS_HTTP_CALL_CLASS not found")
        val request = call.getDeclaredMethod("request")
        val execute = call.getDeclaredMethod("execute")
        val enqueue = call.declaredMethods.filter { it.name == "enqueue" && it.parameterCount == 1 }
        check(enqueue.isNotEmpty()) { "SsHttpCall.enqueue not found" }
        blocked = paths

        fun isBlocked(call: Any?): Boolean {
            val req = call?.let { runCatching { request.invoke(it) }.getOrNull() } ?: return false
            val path = req.callMethodOrNull("getPath") as? String
                ?: (req.callMethodOrNull("getUrl") as? String)?.let(::pathOf)
                ?: return false
            return normalize(path) in blocked
        }

        execute.hookMethod {
            before { param ->
                if (isBlocked(param.thisObject)) param.throwable = IOException(BLOCKED_MESSAGE)
            }
        }
        // Answered like a failed connection, as SsHttpCall.enqueue itself does (on the calling
        // thread) when it cannot build the request; the story/interaction callers only log it.
        enqueue.forEach { method ->
            method.hookMethod {
                before { param ->
                    if (!isBlocked(param.thisObject)) return@before
                    runCatching {
                        param.args[0]?.callMethodOrNull("onFailure", param.thisObject, IOException(BLOCKED_MESSAGE))
                    }
                    param.result = null
                }
            }
        }
    }

    /** The path of a relative ("/a/b?x") or absolute ("https://h/a/b") URL; null for neither. */
    fun pathOf(url: String): String? {
        val withoutQuery = url.substringBefore('#').substringBefore('?')
        if (withoutQuery.startsWith("/")) return withoutQuery
        val schemeEnd = withoutQuery.indexOf("://")
        if (schemeEnd <= 0) return null
        val slash = withoutQuery.indexOf('/', schemeEnd + 3)
        return if (slash < 0) "/" else withoutQuery.substring(slash)
    }

    fun normalize(path: String): String = path.trimEnd('/').ifEmpty { "/" }
}
