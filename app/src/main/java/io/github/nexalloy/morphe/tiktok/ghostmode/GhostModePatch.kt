package io.github.nexalloy.morphe.tiktok.ghostmode

import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.callMethodOrNull
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.morphe.tiktok.shared.realMatches
import io.github.nexalloy.patch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URLDecoder
import java.util.concurrent.CancellationException

private const val TAG = "[TikTok ghost mode]"
private const val BLOCKED_MESSAGE = "Blocked by NexAlloy ghost mode"

private val RELATIVE_PATH = Regex("^[A-Za-z0-9_.\\-]+(/[A-Za-z0-9_.\\-]+)+/?$")

val GhostMode = patch(
    name = "Ghost mode",
    description = "Stops TikTok from reporting what you look at: story views, profile visits, " +
        "the typing indicator and which videos of people you follow you watched (their view " +
        "history). Your online status is unchanged.",
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

    optional("typingStatus") {
        val sender = TYPING_STATUS_SENDER_CLASS.findClassOrNull(classLoader) ?: error("$TYPING_STATUS_SENDER_CLASS not found")
        val methods = sender.declaredMethods.filter {
            !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(String::class.java))
        }
        check(methods.isNotEmpty()) { "TypingStatusSenderTimer has no (String)V method" }
        methods.forEach { it.hookMethod(XC_MethodReplacement.DO_NOTHING) }
    }

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

    var statsDispatchHooked = false

    optional("followedPlayStats") {
        val aweme = AWEME_CLASS.findClassOrNull(classLoader) ?: error("$AWEME_CLASS not found")
        FollowedViews.init(aweme)
        val senders = ::awemeStatsSenderFingerprints.dexMethodList.realMatches()
        check(senders.size == 1) { "expected one play report sender, found ${senders.size}" }
        senders.single().toMethod().hookMethod {
            before { param ->
                val item = param.args.firstOrNull { aweme.isInstance(it) } ?: return@before
                if (FollowedViews.isFollowedAuthor(item)) param.result = null
            }
        }
    }

    optional("followedStatsDispatch") {
        val byName = AWEME_STATS_API_CLASS.findClassOrNull(classLoader)?.declaredMethods?.filter {
            Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE && it.parameterCount == 1 &&
                !List::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                statsParamsGetters(it.parameterTypes[0]).size == 1
        }.orEmpty()
        val dispatch = byName.singleOrNull()
            ?: ::awemeStatsDispatchFingerprints.dexMethodList.realMatches().singleOrNull()?.toMethod()
            ?: error("expected one AwemeStatsApi (model)V dispatcher, found ${byName.size}")
        val model = dispatch.parameterTypes.single()
        val params = statsParamsGetters(model).singleOrNull()
            ?: ::awemeStatsParamsFingerprints.dexMethodList.realMatches().singleOrNull()?.toMethod()
                ?.takeIf { it.declaringClass == model }
            ?: error("${model.name} has no ()HashMap stats params getter")
        dispatch.hookMethod {
            before { param ->
                val fields = runCatching { params.invoke(param.args[0]) as? Map<*, *> }.getOrNull() ?: return@before
                if (FollowedViews.isFollowedPlay { key -> fields[key] as? String }) param.result = null
            }
        }
        statsDispatchHooked = true
    }

    optional("followedViewRequests") {
        val service = AWEME_STATS_SERVICE_CLASS.findClassOrNull(classLoader)
        fun pathOf(method: String): String? {
            val declared = service?.declaredMethods?.firstOrNull { it.name == method }
            val path = declared?.let(::retrofitPath) ?: KNOWN_STATS_PATHS[method] ?: return null
            return ReportRequestBlocker.normalize(path)
        }
        val fastStats = pathOf(FAST_STATS_REPORT_METHOD) ?: error("fast stats path not resolved")
        val stats = if (statsDispatchHooked) null else pathOf(STATS_REPORT_METHOD)
        FollowedViewRequestBlocker.install(classLoader, fastStats, stats)
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

private fun statsParamsGetters(model: Class<*>): List<Method> = model.declaredMethods.filter {
    !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && Map::class.java.isAssignableFrom(it.returnType)
}.onEach { it.isAccessible = true }

internal object FollowedViews {
    private lateinit var getAuthor: Method
    private lateinit var getFollowStatus: Method
    private var getForwardItem: Method? = null

    fun init(aweme: Class<*>) {
        getAuthor = aweme.getMethod("getAuthor")
        getFollowStatus = getAuthor.returnType.getMethod("getFollowStatus").also {
            check(it.returnType == Int::class.javaPrimitiveType) { "getFollowStatus returns ${it.returnType.name}" }
        }
        getForwardItem = runCatching { aweme.getMethod("getForwardItem") }.getOrNull()
            ?.takeIf { aweme.isAssignableFrom(it.returnType) }
    }

    fun isFollowedAuthor(aweme: Any): Boolean {
        if (followsAuthorOf(aweme)) return true
        val origin = getForwardItem?.let { runCatching { it.invoke(aweme) }.getOrNull() } ?: return false
        return followsAuthorOf(origin)
    }

    private fun followsAuthorOf(aweme: Any): Boolean {
        val author = runCatching { getAuthor.invoke(aweme) }.getOrNull() ?: return false
        val status = runCatching { getFollowStatus.invoke(author) as? Int }.getOrNull() ?: return false
        return status in FOLLOWING_STATUSES
    }

    fun isFollowedPlay(field: (String) -> String?): Boolean {
        if (values(field(PLAY_DELTA_FIELD)).none { (it.toIntOrNull() ?: 0) > 0 }) return false
        return FOLLOW_STATUS_FIELDS.any { key ->
            values(field(key)).any { value -> value.toIntOrNull()?.let { it in FOLLOWING_STATUSES } == true }
        }
    }

    private fun values(value: String?): List<String> = value?.split(',')?.map(String::trim).orEmpty()
}

internal object FollowedViewRequestBlocker {

    fun install(classLoader: ClassLoader, fastStatsPath: String, statsPath: String?) {
        val call = SS_HTTP_CALL_CLASS.findClassOrNull(classLoader) ?: error("$SS_HTTP_CALL_CLASS not found")
        val request = call.getDeclaredMethod("request")
        val execute = call.getDeclaredMethod("execute")
        val enqueue = call.declaredMethods.filter { it.name == "enqueue" && it.parameterCount == 1 }
        check(enqueue.isNotEmpty()) { "SsHttpCall.enqueue not found" }

        fun isBlocked(call: Any?): Boolean {
            val req = call?.let { runCatching { request.invoke(it) }.getOrNull() } ?: return false
            val path = (req.callMethodOrNull("getPath") as? String
                ?: (req.callMethodOrNull("getUrl") as? String)?.let(ReportRequestBlocker::pathOf))
                ?.let(ReportRequestBlocker::normalize)
                ?: return false
            if (path == fastStatsPath) return true
            if (statsPath == null || path != statsPath) return false
            val fields = formFields(req.callMethodOrNull("getBody")) ?: return false
            return FollowedViews.isFollowedPlay { fields[it] }
        }

        execute.hookMethod {
            before { param ->
                if (isBlocked(param.thisObject)) param.throwable = IOException(BLOCKED_MESSAGE)
            }
        }
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

    private fun formFields(body: Any?): Map<String, String>? {
        if (body == null) return null
        val out = ByteArrayOutputStream()
        runCatching {
            body.javaClass.getMethod("writeTo", OutputStream::class.java)
                .apply { isAccessible = true }
                .invoke(body, out)
        }.getOrElse { return null }
        return out.toString("UTF-8").split('&')
            .filter { '=' in it }
            .associate { decode(it.substringBefore('=')) to decode(it.substringAfter('=')) }
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
