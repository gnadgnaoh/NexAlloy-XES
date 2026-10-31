package io.github.nexalloy.revanced.threads.tracking

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import java.lang.reflect.Modifier

internal const val MEDIA = "com.instagram.feed.media.Media"
internal const val USER = "com.instagram.user.model.User"

val permalinkResponseParserFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name = "unsafeParseFromJson"
            returnType = "java.lang.Object"
            usingEqStrings("permalink", "XDTPermalinkResponse")
        }
    }.single()
}

val permalinkFetchesFingerprint = findMethodListDirect {
    findMethod {
        matcher { declaredClass = "com.instagram.barcelona.share.permalink.data.PermalinkRepository" }
    }.filter { it.paramTypeNames.contains(MEDIA) }.also { check(it.isNotEmpty()) }
}

private fun org.luckypray.dexkit.DexKitBridge.pandoGetter(owner: String, field: String, returns: String) =
    findMethod {
        matcher { declaredClass = owner; returnType = returns; paramCount = 0; usingNumbers(field.hashCode()) }
    }.single { !Modifier.isStatic(it.modifiers) }

val mediaCodeFingerprint = findMethodDirect { pandoGetter(MEDIA, "code", "java.lang.String") }
val mediaUserFingerprint = findMethodDirect { pandoGetter(MEDIA, "user", USER) }
val userNameFingerprint = findMethodDirect { pandoGetter(USER, "username", "java.lang.String") }
