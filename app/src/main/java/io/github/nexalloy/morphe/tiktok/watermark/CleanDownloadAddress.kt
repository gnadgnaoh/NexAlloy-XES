package io.github.nexalloy.morphe.tiktok.watermark

import app.morphe.extension.shared.Logger
import io.github.nexalloy.callMethodOrNull
import io.github.nexalloy.getObjectFieldOrNull
import io.github.nexalloy.hookMethod
import java.lang.reflect.Method

private const val TAG = "[TikTok watermark]"

internal object CleanDownloadAddress {

    fun install(cleanAddrGetter: Method) {
        cleanAddrGetter.hookMethod {
            after { param ->
                if (hasUrl(param.result)) return@after
                val video = param.thisObject ?: return@after
                val fallback = CLEAN_ADDR_FALLBACK_FIELDS.firstNotNullOfOrNull { field ->
                    addressOrNull(video, field)
                } ?: return@after

                param.result = fallback
                Logger.printDebug { "$TAG clean address missing, using a playback source instead" }
            }
        }
    }

    private fun addressOrNull(video: Any, field: String): Any? {
        val getter = "get" + field.replaceFirstChar(Char::uppercaseChar)
        val model = video.getObjectFieldOrNull(field) ?: video.callMethodOrNull(getter)
        return model?.takeIf { hasUrl(it) }
    }

    private fun hasUrl(model: Any?): Boolean {
        if (model == null) return false
        val urls = model.callMethodOrNull("getUrlList") as? List<*>
            ?: model.getObjectFieldOrNull("urlList") as? List<*>
            ?: return false
        return urls.any { it is String && it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }
}
