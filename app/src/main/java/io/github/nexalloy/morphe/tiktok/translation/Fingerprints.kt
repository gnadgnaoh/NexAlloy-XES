package io.github.nexalloy.morphe.tiktok.translation

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import java.lang.reflect.Modifier

internal const val COMMENT_CLASS = "com.ss.android.ugc.aweme.comment.model.Comment"
private const val MULTI_TRANSLATION_BODY = "com.ss.android.ugc.aweme.comment.translation.MultiTranslationBody"
private const val BASE_COMMENT_CELL = "com.ss.android.ugc.aweme.commentv2.commentlist.powercell.BaseCommentCell"
private const val MASTER_KEY = "key_is_master_translation_toggle_on"
private const val COMMENT_KEY = "key_is_comment_translation_on"

val commentTranslateBatchFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramTypes("java.util.List", null, "boolean")
            addInvoke { declaredClass(MULTI_TRANSLATION_BODY); name = "<init>" }
        }
    }.single { Modifier.isStatic(it.modifiers) }
}

val commentCellCommentGetterFingerprint = findMethodDirect {
    findMethod {
        matcher { declaredClass(BASE_COMMENT_CELL); paramCount = 0; returnType = COMMENT_CLASS }
    }.single { !Modifier.isStatic(it.modifiers) }
}

val commentCellBindFingerprint = findMethodDirect {
    val getter = commentCellCommentGetterFingerprint()
    findMethod {
        matcher {
            declaredClass(BASE_COMMENT_CELL)
            returnType = "void"
            paramCount = 1
            usingEqStrings("comment_panel")
            addInvoke(getter.descriptor)
        }
    }.single { (it.modifiers and SYNTHETIC) == 0 }
}

val masterTranslationStateFingerprint = findMethodDirect {
    findMethod {
        matcher { paramCount = 0; usingEqStrings(MASTER_KEY) }
    }.single { Modifier.isStatic(it.modifiers) && !it.returnTypeName.startsWith("java.") && it.returnTypeName != "void" }
}

val masterTranslationReadFingerprint = findMethodDirect {
    val state = masterTranslationStateFingerprint()
    findMethod {
        matcher {
            declaredClass(state.declaredClassName)
            returnType = "boolean"
            paramCount = 0
            addInvoke(state.descriptor)
            addInvoke { declaredClass(state.returnTypeName); name = "isOn" }
        }
    }.single { Modifier.isStatic(it.modifiers) }
}

val commentTranslationDefaultsFingerprint = findMethodListDirect {
    val read = masterTranslationReadFingerprint()
    findMethod {
        matcher { declaredClass(read.declaredClassName); usingEqStrings(COMMENT_KEY); addInvoke(read.descriptor) }
    }
}

val publishedTranslationStateFingerprint = findMethodDirect {
    val owner = masterTranslationStateFingerprint().declaredClassName
    val setter = findMethod {
        matcher {
            declaredClass(owner)
            returnType = "void"
            paramTypes("java.lang.String", "boolean")
            usingEqStrings(MASTER_KEY)
            addInvoke { declaredClass("com.bytedance.keva.Keva"); name = "storeBoolean" }
        }
    }.single()
    setter.invokes.filter { it.isConstructor && it.paramTypeNames == listOf("boolean", "java.lang.String") }
        .distinctBy { it.descriptor }.single()
}

private const val SYNTHETIC = 0x1000
