package io.github.nexalloy.revanced.facebook.link

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

private const val FB_USER_SESSION = "com.facebook.auth.usersession.FbUserSession"
private const val STRING = "java.lang.String"
private const val INTEGER = "java.lang.Integer"

private val PRIMITIVES = setOf("void", "int", "long", "boolean", "float", "double", "short", "byte", "char")

val externalShareTrackerFingerprint = findMethodDirect {
    val methods = findMethod {
        matcher {
            returnType = STRING
            paramTypes(FB_USER_SESSION, INTEGER, INTEGER, STRING, STRING, "boolean")
            usingEqStrings("ExternalShareTracker")
        }
    }.filter { Modifier.isPublic(it.modifiers) }
    methods.singleOrNull() ?: error("Expected 1 ExternalShareTracker method, found ${methods.size}")
}

val externalShareTrackerSiblingsFingerprint = findMethodListDirect {
    val tracker = getClassData(externalShareTrackerFingerprint().className)!!
    tracker.methods.filter {
        val p = it.paramTypeNames
        it.returnTypeName == STRING && (
            (Modifier.isPublic(it.modifiers) && p == listOf(FB_USER_SESSION, INTEGER, STRING)) ||
                (p.size == 5 && p[1] == FB_USER_SESSION && p.drop(2).all { t -> t == STRING })
            )
    }
}

private fun List<MethodData>.one(): MethodData? = singleOrNull()

val shareLinkAppendersFingerprint = findMethodListDirect {
    listOfNotNull(
        findMethod {
            matcher { returnType = STRING; paramTypes(STRING, STRING); usingEqStrings("sfnsn") }
        }.filter { Modifier.isStatic(it.modifiers) }.one(),
        findMethod {
            matcher { returnType = STRING; paramTypes("com.facebook.graphql.model.GraphQLStory", STRING); usingEqStrings("ref", "share") }
        }.one(),
        findMethod {
            matcher { returnType = STRING; paramTypes(FB_USER_SESSION, INTEGER, STRING); usingEqStrings("controlMsg", "controlTwi") }
        }.one(),
        findMethod {
            matcher {
                declaredClass("com.facebook.facecast.display.sharedialog.utils.LiveStreamingShareDialogModel")
                returnType = STRING
                paramCount = 0
                usingEqStrings("sfnsn")
            }
        }.one(),
    )
}

internal val LINK_HISTORY_WRITERS = listOf(
    "com.facebook.browser.lite.extensions.browserhistory.LinkHistorySignalsWriter",
    "com.facebook.browser.lite.extensions.browserhistory.LinkHistoryUiDataWriter",
)

val linkHistoryFactoriesFingerprint = findMethodListDirect {
    LINK_HISTORY_WRITERS.mapNotNull { writer ->
        findMethod {
            matcher {
                paramCount = 1
                addInvoke { declaredClass(writer); name = "<init>" }
            }
        }.filter { m ->
            m.isMethod && !Modifier.isStatic(m.modifiers) && m.className != writer &&
                m.returnTypeName !in PRIMITIVES && m.opCodes.contains(0x12) && m.opCodes.contains(0x11)
        }.singleOrNull()
    }
}
