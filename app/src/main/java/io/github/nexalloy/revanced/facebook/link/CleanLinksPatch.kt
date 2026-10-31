package io.github.nexalloy.revanced.facebook.link

import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.app.Activity
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Modifier

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

internal fun unwrapFacebookRedirect(url: String): String? {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    if (!LinkShim.isWebUrl(uri)) return null
    val unwrapped = LinkShim.unwrap(uri)
    if (unwrapped == uri && !url.contains("fbclid")) return null
    return LinkShim.clean(unwrapped.toString()).takeIf { it != url }
}
