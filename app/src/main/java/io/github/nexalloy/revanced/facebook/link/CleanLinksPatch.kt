package io.github.nexalloy.revanced.facebook.link

import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.app.Activity
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Modifier
import java.net.URLDecoder

val CleanLinks = patch(
    name = "Unwrap l.php redirect links",
    description = "Opens the real destination of Facebook's l.php redirect links, without fbclid tracking.",
) {
    listOf(Activity::class.java, ContextWrapper::class.java).forEach { type ->
        type.declaredMethods
            .filter {
                !Modifier.isAbstract(it.modifiers) && it.name == "startActivity" &&
                    it.parameterCount in 1..2 && it.parameterTypes[0] == Intent::class.java
            }
            .forEach { m ->
                runCatching {
                    m.hookMethod {
                        before { param ->
                            runCatching {
                                val intent = param.args.getOrNull(0) as? Intent ?: return@runCatching
                                val url = intent.dataString ?: return@runCatching
                                val unwrapped = unwrapFacebookRedirect(url) ?: return@runCatching
                                intent.data = Uri.parse(unwrapped)
                            }
                        }
                    }
                }
            }
    }
}

private val LPHP_PATTERN = Regex(".+?l\\.php\\?u=(.+?)(%26|%3F)fbclid.+")

private val FBCLID_PARAM = Regex("([?&])fbclid=[^&#]*&?")

internal fun unwrapFacebookRedirect(url: String): String? {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    val host = uri.host?.lowercase()
    val destination = if ((host == "facebook.com" || host?.endsWith(".facebook.com") == true) && uri.path == "/l.php") {
        runCatching { uri.getQueryParameter("u") }.getOrNull()
    } else if (url.contains("fbclid")) {
        LPHP_PATTERN.matchEntire(url)?.groupValues?.get(1)
            ?.let { runCatching { URLDecoder.decode(it, "utf-8") }.getOrNull() }
    } else {
        null
    }
    if (destination.isNullOrEmpty() || !(destination.startsWith("http://") || destination.startsWith("https://"))) {
        return null
    }
    return stripFbclid(destination)
}

private fun stripFbclid(url: String): String =
    url.replace(FBCLID_PARAM) { m -> if (m.value.endsWith("&")) m.groupValues[1] else "" }
        .removeSuffix("?")
