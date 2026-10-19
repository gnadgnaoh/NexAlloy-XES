package io.github.nexalloy.revanced.instagram.profile

import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

private const val USER = "com.instagram.user.model.User"
private const val PANDO_RELATIONSHIP = "com.instagram.api.schemas.ImmutablePandoRelationshipInfoDict"
private const val BOOLEAN = "java.lang.Boolean"

private fun DexKitBridge.pandoGetter(type: String, field: String, returns: String?): MethodData =
    findMethod {
        matcher {
            declaredClass(type)
            paramCount = 0
            if (returns != null) returnType = returns
            usingNumbers(field.hashCode())
        }
    }.single { !Modifier.isStatic(it.modifiers) && '.' in it.returnTypeName }

val profileHeaderBinderFingerprint = findMethodDirect {
    findMethod { matcher { usingEqStrings("bindFullName") } }.single()
}

val userBiographyGetterFingerprint = findMethodDirect { pandoGetter(USER, "biography", "java.lang.String") }
val userHdProfilePicGetterFingerprint = findMethodDirect {
    pandoGetter(USER, "hd_profile_pic_url_info", "com.instagram.api.schemas.ProfilePicUrlInfo")
}

val userFriendshipStatusGetterFingerprint = findMethodDirect { pandoGetter(USER, "friendship_status", null) }
val relationshipFollowedByGetterFingerprint = findMethodDirect { pandoGetter(PANDO_RELATIONSHIP, "followed_by", BOOLEAN) }
val userFollowedByGetterFingerprint = findMethodDirect { pandoGetter(USER, "followed_by", BOOLEAN) }
