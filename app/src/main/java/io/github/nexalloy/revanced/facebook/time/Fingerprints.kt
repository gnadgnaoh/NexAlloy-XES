package io.github.nexalloy.revanced.facebook.time

import io.github.nexalloy.morphe.findMethodDirect
import java.lang.reflect.Modifier

val timeStyleFormatFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "java.lang.String"
            paramTypes("java.lang.Integer", "long")
            usingEqStrings("Unknown style")
        }
    }.single { !Modifier.isStatic(it.modifiers) }
}

val secondsAgoFormatFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "java.lang.String"
            paramTypes("android.content.Context", "com.facebook.auth.usersession.FbUserSession", "long")
            usingEqStrings("MMMM d")
        }
    }.single()
}
