package io.github.nexalloy.morphe.tiktok.downloads

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import java.lang.reflect.Modifier

private const val ACTIVITY = "android.app.Activity"
private const val STRING = "java.lang.String"
private const val CONTEXT = "android.content.Context"
private const val BASE_SHARE_PACKAGE = "com.ss.android.ugc.aweme.share.base.model.BaseSharePackage"
private const val OP_CHECK_CAST = 0x1f

val nativeSaveActionConstructorFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name = "<init>"
            paramTypes(ACTIVITY, AWEME_CLASS, STRING, STRING, STRING, "int")
        }
    }.single()
}

val nativeSaveActionClickFingerprint = findMethodDirect {
    val owner = nativeSaveActionConstructorFingerprint().declaredClassName
    findMethod {
        matcher { declaredClass(owner); returnType = "void"; paramTypes(CONTEXT, BASE_SHARE_PACKAGE) }
    }.single { !Modifier.isStatic(it.modifiers) && OP_CHECK_CAST in it.opCodes }
}

val nativeSaveActionLabelFingerprint = findMethodDirect {
    val owner = nativeSaveActionConstructorFingerprint().declaredClassName
    val binder = findMethod {
        matcher { declaredClass(owner); returnType = "void"; paramTypes("android.widget.TextView") }
    }.single()
    val helper = binder.invokes.single { Modifier.isStatic(it.modifiers) && it.paramTypeNames.lastOrNull() == "android.widget.TextView" }
    val label = helper.invokes.first { it.returnTypeName == STRING && it.paramCount == 0 && !it.declaredClassName.startsWith("java.") }
    findMethod {
        matcher { declaredClass(owner); name = label.name; paramCount = 0; returnType = STRING }
    }.single()
}

val otherProfileAvatarLongPressFingerprint = findMethodDirect {
    findMethod {
        matcher { returnType = "void"; usingEqStrings("long_press", "long_hold_head") }
    }.single { m ->
        m.paramTypeNames.lastOrNull() == "android.view.View" &&
            m.usingStrings.none { it.contains("photo") || it.contains("video") }
    }
}

val downloadAddressReadersFingerprint = findMethodListDirect {
    findMethod {
        matcher { addInvoke { declaredClass("com.ss.android.ugc.aweme.feed.model.Video"); name = "getDownloadNoWatermarkAddr" } }
    }.distinctBy { it.descriptor }
}

internal const val URL_MODEL = "com.ss.android.ugc.aweme.base.model.UrlModel"

val commentPhotoSaverFingerprint = findMethodDirect {
    findMethod {
        matcher { name = "run"; returnType = "void"; paramCount = 0; usingStrings("CommentPhotoSaver@") }
    }.single { m ->
        m.declaredClass!!.methods.any {
            it.isConstructor && it.paramTypeNames == listOf(ACTIVITY, URL_MODEL, STRING, "kotlin.jvm.functions.Function1", "boolean", "boolean")
        }
    }
}
