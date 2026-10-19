package io.github.nexalloy.revanced.instagram.privacy

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import app.morphe.extension.shared.Logger
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.util.Locale

private const val IN_APP_BROWSER = "com.instagram.inappbrowser.fragments.BrowserLiteInMainProcessIGActivity"
private const val IAB_CONTEXT = "EXTRA_IAB_CONTEXT"
private const val ADS_CONTEXT = "com.facebook.browser.iabcontext.IABAdsContext"

private val INTERNAL_HOSTS = listOf(
    "instagram.com", "instagr.am", "ig.me", "facebook.com", "fb.com", "fb.me", "fb.watch",
    "messenger.com", "meta.com", "threads.net", "threads.com",
)

private val SHIM_HOSTS = setOf("l.instagram.com", "l.facebook.com", "l.alpha.facebook.com", "lm.alpha.facebook.com")
private const val MAX_SHIMS = 8
private val FBCLID = Regex("([?&])fbclid=[^&#]*&?")

private fun isWeb(uri: Uri?) = uri != null && !uri.isOpaque &&
    (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrEmpty()

private fun isOn(host: String?, domains: List<String>): Boolean {
    val lower = host?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: return false
    return domains.any { lower == it || lower.endsWith(".$it") } // dấu chấm: "notinstagram.com" không khớp
}

private fun unwrap(uri: Uri): Uri {
    var target = uri
    repeat(MAX_SHIMS) {
        val host = target.host?.lowercase(Locale.ROOT)
        if (!isWeb(target) || host !in SHIM_HOSTS) return target
        val inner = target.getQueryParameter("u")?.let(Uri::parse)?.takeIf(::isWeb) ?: return target
        target = inner
    }
    return target
}

private fun isAd(intent: Intent): Boolean = runCatching {
    @Suppress("DEPRECATION")
    var type: Class<*>? = intent.getParcelableExtra<android.os.Parcelable>(IAB_CONTEXT)?.javaClass
    while (type != null) {
        if (type.name == ADS_CONTEXT) return true
        type = type.superclass
    }
    false
}.getOrDefault(true) // không đọc được → coi như quảng cáo, để Instagram mở

private fun externalIntent(context: Context, intent: Intent?): Intent? {
    val data = intent?.data ?: return null
    if (!isWeb(data) || isAd(intent)) return null
    val target = unwrap(data)
    if (isOn(target.host, INTERNAL_HOSTS)) return null
    val clean = Uri.parse(target.toString().replace(FBCLID) { m -> if (m.value.endsWith("&")) m.groupValues[1] else "" }.removeSuffix("?"))
    if (!isWeb(clean)) return null
    val view = Intent(Intent.ACTION_VIEW, clean).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val handler = runCatching { view.resolveActivity(context.packageManager) }.getOrNull() ?: return null
    return view.takeIf { handler.packageName != context.packageName }
}

val OpenLinksInBrowser = patch(
    name = "Open links in external browser",
    description = "Opens web links in your browser instead of Instagram's built-in one, without click tracking. " +
        "Instagram pages and ads still open in the app.",
) {
    val context: Context = appContext
    val browser = IN_APP_BROWSER.findClassOrNull(classLoader) ?: error("$IN_APP_BROWSER not found")

    val starts = Instrumentation::class.java.declaredMethods.filter { m ->
        m.name == "execStartActivity" && Intent::class.java in m.parameterTypes
    }
    check(starts.isNotEmpty()) { "Instrumentation.execStartActivity not found" }
    starts.forEach { m ->
        m.hookMethod {
            before { param ->
                // Chạy lúc app mở link (runtime) → lỗi ở đây không được làm hỏng việc mở link.
                runCatching {
                    val at = param.args.indexOfFirst { it is Intent }
                    val intent = param.args[at] as Intent
                    if (intent.component?.className != IN_APP_BROWSER) return@runCatching
                    param.args[at] = externalIntent(context, intent) ?: return@runCatching
                }.onFailure { Logger.printException({ "Instagram: could not open link externally" }, it) }
            }
        }
    }

    fun handOut(activity: Activity, intent: Intent?) {
        val external = externalIntent(activity, intent) ?: return
        runCatching { activity.startActivity(external); activity.finish() }
    }
    browser.declaredMethods.firstOrNull { it.name == "onCreate" && it.parameterTypes.contentEquals(arrayOf(Bundle::class.java)) }
        ?.hookMethod { after { param -> (param.thisObject as? Activity)?.let { handOut(it, it.intent) } } }
    browser.declaredMethods.firstOrNull { it.name == "onNewIntent" && it.parameterTypes.contentEquals(arrayOf(Intent::class.java)) }
        ?.hookMethod { after { param -> (param.thisObject as? Activity)?.let { handOut(it, param.args[0] as? Intent) } } }
}
