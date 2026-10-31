package io.github.nexalloy.revanced.threads.download

import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.InstructionData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

internal const val MEDIA = "com.instagram.feed.media.Media"
private const val PANDO_VIDEO_VERSION = "com.instagram.api.schemas.ImmutablePandoVideoVersion"
private const val POST_MENU_NOTE = "com.instagram.barcelona.feed.post.actionmenu.PostActionMenuSheet.<anonymous> (PostActionMenuSheet.kt:"
private const val MEDIA_MENU_NOTE = "com.instagram.barcelona.postmedia.ui.MediaActionMenuSheet.<anonymous> (MediaActionMenuSheet.kt:"
private const val MENU_ICON_NOTE = "com.instagram.barcelona.common.ui.listcell.getMenuRowEndIcon (BdsListCell.kt:"

private const val OP_CHECK_CAST = 0x1f
private const val OP_INSTANCE_OF = 0x20
private const val OP_SGET_OBJECT = 0x62
private const val OP_INVOKE_VIRTUAL = 0x6e
private const val OP_INVOKE_STATIC = 0x71

private fun InstructionData.call(): MethodData? = methodRef

val postMenuRowsFingerprint = findMethodDirect {
    findMethod { matcher { name = "invoke"; usingStrings(POST_MENU_NOTE) } }.single()
}

val mediaMenuRowsFingerprint = findMethodDirect {
    findMethod { matcher { name = "invoke"; usingStrings(MEDIA_MENU_NOTE) } }.single()
}

val copyLinkActionFingerprint = findClassDirect {
    findClass {
        matcher { addMethod { name = "toString"; usingEqStrings("CopyLinkAction") } }
    }.single()
}

private class CopyLinkRow(code: List<InstructionData>, action: String) {
    val row: MethodData
    val click: MethodData
    val role: MethodData
    val base: InstructionData

    init {
        val start = code.indexOfFirst { it.opcode == OP_INSTANCE_OF && it.classRef?.name == action }
        check(start >= 0)
        val rest = code.drop(start + 1)
        row = rest.first {
            it.opcode == OP_INVOKE_STATIC && it.call()?.let { m ->
                m.returnTypeName == "void" && m.paramCount == 5 && m.paramTypeNames[4] == "java.lang.String"
            } == true
        }.call()!!
        val end = rest.indexOfFirst { it.call()?.descriptor == row.descriptor }
        val body = rest.take(end)
        click = body.first {
            it.opcode == OP_INVOKE_STATIC && it.call()?.let { m ->
                m.paramCount == 5 && m.paramTypeNames[2] == "java.lang.Object" && m.returnTypeName == row.paramTypeNames[1]
            } == true
        }.call()!!
        role = body.first {
            it.opcode == OP_INVOKE_STATIC && it.call()?.let { m ->
                m.paramTypeNames == listOf("int") && m.returnTypeName == click.paramTypeNames[1]
            } == true
        }.call()!!
        base = body.first { it.opcode == OP_SGET_OBJECT && it.fieldRef != null }
    }
}

private fun DexKitBridge.copyLinkRow() =
    CopyLinkRow(postMenuRowsFingerprint().instructions, copyLinkActionFingerprint().name)

val menuRowFingerprint = findMethodDirect { copyLinkRow().row }
val menuClickModifierFingerprint = findMethodDirect { copyLinkRow().click }
val menuRoleFingerprint = findMethodDirect { copyLinkRow().role }
val menuBaseModifierFingerprint = findFieldDirect { copyLinkRow().base.fieldRef!! }

val menuRowIconFingerprint = findMethodDirect {
    findMethod {
        matcher { paramTypes(null, "int", "int", "boolean"); usingStrings(MENU_ICON_NOTE) }
    }.single { Modifier.isStatic(it.modifiers) }
}

val mediaMenuIconFingerprint = findMethodDirect {
    val row = copyLinkRow().row
    val code = mediaMenuRowsFingerprint().instructions
    code.indices.filter { code[it].call()?.descriptor == row.descriptor }.firstNotNullOf { at ->
        (at - 1 downTo maxOf(0, at - 4)).map { code[it] }.firstOrNull {
            it.opcode == OP_INVOKE_STATIC && it.call()?.paramTypeNames == listOf(row.paramTypeNames[0], "int")
        }?.call()
    }
}

val postMenuCloseFingerprint = findMethodDirect {
    findMethod { matcher { name = "invoke"; usingEqStrings("copy_link") } }.mapNotNull { method ->
        val code = method.instructions
        val at = code.indexOfFirst { it.string == "copy_link" }
        val controller = (at downTo 0).map { code[it] }.firstOrNull { it.opcode == OP_CHECK_CAST }?.classRef?.name
            ?: return@mapNotNull null
        val after = code.drop(at)
        val shared = after.indexOfFirst { insn -> insn.call()?.let { it.isConstructor && MEDIA in it.paramTypeNames } == true }
        if (shared < 0) return@mapNotNull null
        after.drop(shared).firstOrNull {
            it.opcode == OP_INVOKE_VIRTUAL && it.call()?.let { m ->
                m.declaredClassName == controller && m.paramCount == 0 && m.returnTypeName == "void"
            } == true
        }?.call()
    }.distinctBy { it.descriptor }.single()
}

private fun DexKitBridge.pandoGetter(owner: String, field: String, returns: String) =
    findMethod {
        matcher { declaredClass = owner; returnType = returns; paramCount = 0; usingNumbers(field.hashCode()) }
    }.single { !Modifier.isStatic(it.modifiers) }

val mediaVideoVersionsFingerprint = findMethodDirect { pandoGetter(MEDIA, "video_versions", "java.util.List") }
val mediaDashManifestFingerprint = findMethodDirect { pandoGetter(MEDIA, "video_dash_manifest", "java.lang.String") }
val mediaCarouselFingerprint = findMethodDirect { pandoGetter(MEDIA, "carousel_media", "java.util.List") }
val versionUrlFingerprint = findMethodDirect { pandoGetter(PANDO_VIDEO_VERSION, "url", "java.lang.String") }
val versionWidthFingerprint = findMethodDirect { pandoGetter(PANDO_VIDEO_VERSION, "width", "java.lang.Integer") }
val versionHeightFingerprint = findMethodDirect { pandoGetter(PANDO_VIDEO_VERSION, "height", "java.lang.Integer") }

val mediaViewerTopBarInitFingerprint = findMethodDirect {
    val owner = findMethod {
        matcher { name = "invoke"; usingStrings("com.instagram.barcelona.feed.mediaviewer.ui.MediaViewerTopBar.<anonymous> (MediaViewerTopBar.kt:") }
    }.single().declaredClassName
    findMethod { matcher { declaredClass = owner; name = "<init>"; paramCount = 11 } }.single()
        .also { check(it.paramTypeNames[2] == "kotlin.jvm.functions.Function0" && it.paramTypeNames[9] == "boolean") }
}
