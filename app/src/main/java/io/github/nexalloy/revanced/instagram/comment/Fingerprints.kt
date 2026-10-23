package io.github.nexalloy.revanced.instagram.comment

import io.github.nexalloy.morphe.findFieldListDirect
import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import java.lang.reflect.Modifier

private const val GIPHY = "com.instagram.api.schemas.CommentGiphyMediaInfoIntf"
private const val FRAGMENT = "androidx.fragment.app.Fragment"

private fun ClassData.isRawComment() = isInterface() && methods.any { it.paramCount == 0 && it.returnTypeName == GIPHY }

private fun ClassData.isInterface() = Modifier.isInterface(modifiers)

val commentMenuRendererFingerprint = findMethodDirect {
    findMethod {
        matcher {
            modifiers = Modifier.PUBLIC
            returnType = "void"
            paramTypes(null, null, null, "java.util.List", "float")
            addInvoke { declaredClass(FRAGMENT); name = "requireContext" }
        }
    }.single { m -> m.paramTypes[1].fields.any { it.type.isRawComment() } }
}

private fun DexKitBridge.rawCommentInterface(): ClassData =
    commentMenuRendererFingerprint().paramTypes[1].fields.map { it.type }.first { it.isRawComment() }

val commentMediaInfoGetterFingerprint = findMethodDirect {
    val raw = rawCommentInterface()
    val tree = findMethod {
        matcher {
            declaredClass { addInterface(raw.name) }
            paramCount = 0
            usingNumbers("media_comment_info".hashCode())
        }
    }.filter { !Modifier.isStatic(it.modifiers) && '.' in it.returnTypeName }.map { it.name }.distinct().single()
    raw.methods.single { it.name == tree && it.paramCount == 0 }
}

val menuActionSingletonsFingerprint = findFieldListDirect {
    val copy = findClass { matcher { addMethod { name = "toString"; usingEqStrings("CopyText") } } }.single { c ->
        c.superClass?.methods?.any { m ->
            m.isConstructor && m.paramTypeNames.size == 4 && m.paramTypeNames.drop(1) == listOf("int", "int", "boolean")
        } == true
    }
    val family = copy.superClass!!.name
    findClass { matcher { superClass(family) } }.flatMap { c ->
        c.fields.filter { Modifier.isStatic(it.modifiers) && it.typeName == c.name }
    }
}
