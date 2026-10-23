package io.github.nexalloy.revanced.facebook.link

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

private val IN_APP_BROWSERS = listOf(
    "com.facebook.browser.litev2.lite.BrowserLiteDIActivity",
    "com.facebook.browser.lite.BrowserLiteActivity",
)

private fun isInAppBrowser(name: String?) = name != null && name.startsWith("com.facebook.browser.lite")

private fun externalIntent(context: Context, link: Uri?): Intent? {
    if (link == null || !LinkShim.isWebUrl(link)) return null
    val target = LinkShim.destination(link)
    if (LinkShim.staysInApp(target)) return null
    val view = Intent(Intent.ACTION_VIEW, target)
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val handler = runCatching { view.resolveActivity(context.packageManager) }.getOrNull() ?: return null
    return view.takeIf { handler.packageName != context.packageName }
}

val OpenLinksInBrowser = patch(
    name = "Open links in external browser",
    description = "Opens web links in your browser instead of Facebook's built-in one, without click tracking. " +
        "Facebook pages still open in the app.",
) {
    val context: Context = appContext

    val starts = Instrumentation::class.java.declaredMethods.filter { m ->
        m.name == "execStartActivity" && Intent::class.java in m.parameterTypes
    }
    check(starts.isNotEmpty()) { "Instrumentation.execStartActivity not found" }
    starts.forEach { m ->
        m.hookMethod {
            before { param ->
                runCatching {
                    val at = param.args.indexOfFirst { it is Intent }
                    val intent = param.args[at] as Intent
                    if (!isInAppBrowser(intent.component?.className)) return@runCatching
                    param.args[at] = externalIntent(context, intent.data) ?: return@runCatching
                }.onFailure { Logger.printException({ "Facebook: could not open link externally" }, it) }
            }
        }
    }

    fun handOut(activity: Activity, intent: Intent?) {
        val external = externalIntent(activity, intent?.data) ?: return
        runCatching { activity.startActivity(external); activity.finish() }
    }
    IN_APP_BROWSERS.forEach { name ->
        val browser = name.findClassOrNull(classLoader) ?: return@forEach
        browser.declaredMethods.firstOrNull { it.name == "onCreate" && it.parameterTypes.contentEquals(arrayOf(Bundle::class.java)) }
            ?.hookMethod { after { param -> (param.thisObject as? Activity)?.let { handOut(it, it.intent) } } }
        browser.declaredMethods.firstOrNull { it.name == "onNewIntent" && it.parameterTypes.contentEquals(arrayOf(Intent::class.java)) }
            ?.hookMethod { after { param -> (param.thisObject as? Activity)?.let { handOut(it, param.args[0] as? Intent) } } }
    }

    val factories = ::linkHistoryFactoriesFingerprint.dexMethodList
    factories.forEach { it.hookMethod { before { param -> param.result = null } } }
    if (factories.size < LINK_HISTORY_WRITERS.size) {
        Logger.printInfo { "Facebook: found ${factories.size}/${LINK_HISTORY_WRITERS.size} link history factories" }
    }
}
