package io.github.nexalloy.revanced.facebook.ad

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import io.github.nexalloy.hookMethod
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

private const val BRIDGE_NAME = "QuicksilverAndroid"
private const val DISPATCH = "e = new Event('message');e.data = %s;window.dispatchEvent(e);"
private const val NO_AD = "No ad is available."
private const val WATCHED = "nexalloy-watched-"

private val AD_CODES = mapOf(
    "getinterstitialadasync" to "CLIENT_UNSUPPORTED_OPERATION",
    "getrewardedvideoasync" to "CLIENT_UNSUPPORTED_OPERATION",
    "loadadasync" to "ADS_NO_FILL",
    "loadbanneradasync" to "ADS_NO_FILL",
    "showadasync" to "ADS_NOT_LOADED",
)

private val watchedCount = AtomicInteger(0)
private val main = Handler(Looper.getMainLooper())

internal fun wrapGameBridges(known: Class<*>?) {
    WebView::class.java.getDeclaredMethod("addJavascriptInterface", Any::class.java, String::class.java).hookMethod {
        before { param ->
            runCatching {
                if (param.args[1] != BRIDGE_NAME) return@runCatching
                val target = param.args[0] ?: return@runCatching
                if (target is NexGameBridge || (known != null && known.isInstance(target))) return@runCatching
                val post = onlyPostMessage(target.javaClass) ?: return@runCatching
                param.args[0] = NexGameBridge(param.thisObject as WebView, target, post.apply { isAccessible = true })
            }
        }
    }
}

private fun onlyPostMessage(type: Class<*>): Method? {
    var found: Method? = null
    for (m in type.methods) {
        if (!m.isAnnotationPresent(JavascriptInterface::class.java)) continue
        val p = m.parameterTypes
        if (found != null || m.name != "postMessage" || p.size != 2 || p[0] != String::class.java || p[1] != String::class.java) return null
        found = m
    }
    return found
}

class NexGameBridge internal constructor(private val view: WebView, private val target: Any, private val post: Method) {
    @JavascriptInterface
    fun postMessage(message: String?, origin: String?) {
        val answer = message?.let(::answer)
        if (answer == null) {
            try {
                post.invoke(target, message, origin)
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
            return
        }
        main.post { runCatching { view.evaluateJavascript(answer, null) } }
    }

    private fun answer(message: String): String? = runCatching {
        if (AD_CODES.keys.none { message.contains(it) }) return null
        val json = JSONObject(message)
        val type = json.optString("type")
        val code = AD_CODES[type] ?: return null
        val content = json.optJSONObject("content") ?: return null
        val promise = content.optString("promiseID")
        if (promise.isEmpty()) return null
        val watched = when (type) {
            "getrewardedvideoasync" -> JSONObject()
                .put("placementID", content.optString("placementID"))
                .put("adInstanceID", WATCHED + watchedCount.incrementAndGet())
            "loadadasync", "showadasync" -> if (content.optString("adInstanceID").startsWith(WATCHED)) JSONObject() else null
            else -> null
        }
        val body = JSONObject().put("promiseID", promise)
        val kind = if (watched != null) {
            body.put("data", watched); "resolvepromise"
        } else {
            body.put("data", JSONObject().put("code", code).put("message", NO_AD)); "rejectpromise"
        }
        val event = JSONObject().put("type", kind).put("content", body).put("source", "android")
        String.format(Locale.ROOT, DISPATCH, event)
    }.getOrNull()
}
