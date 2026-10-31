package io.github.nexalloy.revanced.instagram.story

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Modifier

private const val SHARE_TYPE = "com.instagram.pendingmedia.model.constants.ShareType"

val UploadStoriesFullQuality = patch(
    name = "Upload stories at full quality",
    description = "Sends the video stories you post at up to 1080 pixels wide instead of 720, as Instagram does with " +
        "its own Upload at highest quality setting on.",
) {
    val check = ::uploadHighQualityCheckFingerprint.method
    val shareType = check.declaringClass.declaredFields.single {
        !Modifier.isStatic(it.modifiers) && it.type.name == SHARE_TYPE
    }.apply { isAccessible = true }
    check.hookMethod {
        before { param ->
            val type = runCatching { shareType.get(param.thisObject) as? Enum<*> }.getOrNull() ?: return@before
            if (type.name == "STORY") param.result = true
        }
    }
}
