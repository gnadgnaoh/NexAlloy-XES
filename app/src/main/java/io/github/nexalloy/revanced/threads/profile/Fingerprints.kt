package io.github.nexalloy.revanced.threads.profile

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.revanced.threads.tracking.USER
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

internal const val IMAGE_URL = "com.instagram.common.typedurl.ImageUrl"

private fun DexKitBridge.focusedPictureScreen(): MethodData =
    findMethod { matcher { usingEqStrings("focused_profile_picture_background_scrim") } }.single()

val focusedPictureScreenFingerprint = findMethodDirect { focusedPictureScreen() }

val focusedPictureImageFingerprint = findMethodDirect {
    focusedPictureScreen().invokes.first { m ->
        Modifier.isStatic(m.modifiers) && m.paramTypeNames.lastOrNull() == IMAGE_URL && m.returnTypeName == "void"
    }
}

val focusedPictureHostFingerprint = findMethodListDirect {
    focusedPictureScreen().callers.distinctBy { it.descriptor }
}

val userBiographyFingerprint = findMethodDirect {
    findMethod {
        matcher { declaredClass = USER; returnType = "java.lang.String"; paramCount = 0; usingNumbers("biography".hashCode()) }
    }.single { !Modifier.isStatic(it.modifiers) }
}

val userHdPictureFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass = USER
            returnType = "com.instagram.api.schemas.ProfilePicUrlInfo"
            paramCount = 0
            usingNumbers("hd_profile_pic_url_info".hashCode())
        }
    }.single { !Modifier.isStatic(it.modifiers) }
}
