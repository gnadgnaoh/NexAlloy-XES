package io.github.nexalloy.morphe.tiktok.captcha

import android.app.Activity
import android.os.Handler
import android.os.Looper
import app.morphe.extension.shared.Logger
import io.github.nexalloy.isStatic
import io.github.nexalloy.morphe.tiktok.shared.TikTokServices
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "[TikTok captcha]"

internal object CaptchaSuppressor {
    private const val CLOSE_CODE = 3

    /** Long enough for the caller to reach its await(); shorter than closing a real dialog. */
    private const val SHOP_ANSWER_DELAY_MS = 500L

    /** `listener.result(code, ok)` and `listener.dismiss()`, taken from SecCaptcha.onFail. */
    @Volatile
    private var closeCallbacks: List<Method> = emptyList()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val requestStringFields = ConcurrentHashMap<Class<*>, List<Field>>()
    private val requestActivityFields = ConcurrentHashMap<Class<*>, List<Field>>()
    private val bdTuringOnFail = ConcurrentHashMap<Class<*>, List<Method>>()

    fun init(closeCallbacks: List<Method>) {
        this.closeCallbacks = closeCallbacks
    }

    /** The Sec hooks are pointless without a way to answer the caller. */
    fun canCloseSecCaptcha() = closeCallbacks.isNotEmpty()

    /** Only ever hides while signed in: a captcha shown to a signed-out user is part of signing in. */
    private fun enabled() = TikTokServices.isLoggedIn()

    /** `popCaptchaV2(activity, riskInfo, listener, fragment)`. */
    fun handleRiskInfoCaptcha(activity: Any?, riskInfo: Any?, listener: Any?): Boolean {
        if (!enabled()) return false
        val target = activity as? Activity
        if (!CaptchaPolicy.shouldHide(target?.javaClass?.name, routeOf(target), riskInfo as? String)) return false
        return close(listener, "popCaptchaV2")
    }

    /** `popCaptcha(activity, errorCode, listener)`: the classic image captcha, no payload to read. */
    fun handleLegacyCaptcha(activity: Any?, listener: Any?): Boolean {
        if (!enabled()) return false
        val target = activity as? Activity
        if (!CaptchaPolicy.shouldHide(target?.javaClass?.name, routeOf(target), null)) return false
        return close(listener, "popCaptcha")
    }

    /** `RiskControlService.execute(request, callback)` of the Shop verification SDK. */
    fun handleVerifyRequest(request: Any?, callback: Any?): Boolean {
        if (request == null || callback == null || !enabled()) return false
        val activity = activityOf(request)
        if (!CaptchaPolicy.shouldHideVerifyRequest(
                activity?.javaClass?.name,
                routeOf(activity),
                stringsOf(request),
            )
        ) {
            return false
        }

        val onFail = bdTuringOnFail.getOrPut(callback.javaClass) {
            generateSequence(callback.javaClass) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .filter {
                    it.name == "onFail" && it.parameterCount == 2 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType
                }
                .take(1)
                .onEach { it.isAccessible = true }
                .toList()
        }.firstOrNull() ?: return false

        // Posted, never inline: see the note on this object.
        mainHandler.postDelayed({
            runCatching { onFail.invoke(callback, CLOSE_CODE, null) }
                .onFailure { Logger.printDebug { "$TAG could not close oec verification: $it" } }
        }, SHOP_ANSWER_DELAY_MS)
        Logger.printDebug { "$TAG hidden: oec verification" }
        return true
    }

    /**
     * Reports the close to the listener. The result callback is what the waiting caller reads, so
     * once it has been delivered the dialog counts as handled even if the follow-up dismiss
     * callback throws - running the original afterwards would show a dialog for an answer the
     * caller has already consumed.
     */
    private fun close(listener: Any?, source: String): Boolean {
        if (listener == null) return true
        val callbacks = closeCallbacks
        val result = callbacks.firstOrNull() ?: return false
        runCatching { result.invoke(listener, CLOSE_CODE, false) }
            .onFailure {
                Logger.printDebug { "$TAG could not close $source: $it" }
                return false
            }
        callbacks.drop(1).forEach { dismiss ->
            runCatching { dismiss.invoke(listener) }
                .onFailure { Logger.printDebug { "$TAG $source dismiss callback failed: $it" } }
        }
        Logger.printDebug { "$TAG hidden: $source" }
        return true
    }

    private fun routeOf(activity: Activity?): String? =
        runCatching { activity?.intent?.dataString }.getOrNull()

    /** The request carries the activity it belongs to in its only Activity-typed field. */
    private fun activityOf(request: Any): Activity? {
        val fields = requestActivityFields.getOrPut(request.javaClass) {
            fieldsOf(request.javaClass) { Activity::class.java.isAssignableFrom(it.type) }
        }
        return fields.firstNotNullOfOrNull { field ->
            runCatching { field.get(request) as? Activity }.getOrNull()
        }
    }

    private fun stringsOf(request: Any): List<String> {
        val fields = requestStringFields.getOrPut(request.javaClass) {
            fieldsOf(request.javaClass) { it.type == String::class.java }
        }
        return fields.mapNotNull { field -> runCatching { field.get(request) as? String }.getOrNull() }
    }

    private fun fieldsOf(type: Class<*>, predicate: (Field) -> Boolean): List<Field> =
        generateSequence(type) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !it.isStatic && predicate(it) }
            .onEach { it.isAccessible = true }
            .toList()
}
