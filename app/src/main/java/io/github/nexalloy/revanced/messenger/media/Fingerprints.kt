package io.github.nexalloy.revanced.messenger.media

import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.InstructionData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

private const val OP_CONST = 0x14
private const val OP_INSTANCE_OF = 0x20
private const val OP_IGET_BOOLEAN = 0x55
private const val OP_SGET_OBJECT = 0x62
private const val OP_INVOKE_VIRTUAL = 0x6e
private const val OP_INVOKE_STATIC = 0x71
private const val OP_CONST_WIDE_16 = 0x16
private const val OP_CMP_LONG = 0x31
private const val OP_IGET_WIDE = 0x53

private const val STORY_MENU_TAG = "toolbar_click_menu_button"

val storyMenuBuilderFingerprint = findMethodDirect {
    findMethod {
        matcher { name = "onClick"; paramTypes("android.view.View"); usingEqStrings(STORY_MENU_TAG) }
    }.single()
}

private class StorySave(code: List<InstructionData>) {
    val isOwn: MethodData
    val ownFlag: InstructionData
    val menuFactory: MethodData
    val controller: MethodData
    val saveable: List<String>
    val id: Long
    val label: Long
    val labelOf: MethodData
    val icon: InstructionData
    val addItem: MethodData

    init {
        val tag = code.indexOfFirst { it.string == STORY_MENU_TAG }
        check(tag >= 0)
        val ownAt = (tag + 1 until code.size).first { i ->
            code[i].opcode == OP_INVOKE_VIRTUAL && code[i].methodRef?.let { it.returnTypeName == "boolean" && it.paramCount == 0 } == true
        }
        isOwn = code[ownAt].methodRef!!
        val fragment = isOwn.declaredClassName
        menuFactory = (ownAt downTo tag).map { code[it] }.first {
            it.opcode == OP_INVOKE_STATIC && it.methodRef?.paramTypeNames == listOf("android.content.Context")
        }.methodRef!!
        ownFlag = (ownAt until code.size).map { code[it] }.first { it.opcode == OP_IGET_BOOLEAN }
        check(ownFlag.fieldRef!!.declaredClassName == fragment)
        val firstTest = (ownAt until code.size).first { code[it].opcode == OP_INSTANCE_OF }
        controller = (firstTest downTo ownAt).map { code[it] }.first {
            it.opcode == OP_INVOKE_STATIC && it.methodRef?.paramTypeNames == listOf(fragment)
        }.methodRef!!
        var i = firstTest
        val types = mutableListOf<String>()
        while (code[i].opcode == OP_INSTANCE_OF) { types += code[i].classRef!!.name; i += 2 }
        saveable = types
        val rest = code.drop(i).take(12)
        val consts = rest.filter { it.opcode == OP_CONST }
        id = consts[0].literal!!
        label = consts[1].literal!!
        labelOf = rest.first { it.opcode == OP_INVOKE_STATIC && it.methodRef?.returnTypeName == "java.lang.String" }.methodRef!!
        icon = rest.first { it.opcode == OP_SGET_OBJECT }
        addItem = rest.first { it.opcode == OP_INVOKE_STATIC && it.methodRef?.paramCount == 5 }.methodRef!!
        check(addItem.paramTypeNames[1] == fragment && addItem.paramTypeNames[4] == "int")
    }
}

private fun DexKitBridge.storySave() = StorySave(storyMenuBuilderFingerprint().instructions)

val storyOwnCheckFingerprint = findMethodDirect { storySave().isOwn }
val storyOwnFlagFingerprint = findFieldDirect { storySave().ownFlag.fieldRef!! }
val storyMenuFactoryFingerprint = findMethodDirect { storySave().menuFactory }
val storyCardControllerFingerprint = findMethodDirect { storySave().controller }
val storySaveLabelOfFingerprint = findMethodDirect { storySave().labelOf }
val storySaveIconFingerprint = findFieldDirect { storySave().icon.fieldRef!! }
val storyMenuAddItemFingerprint = findMethodDirect { storySave().addItem }

val storySaveNumbersValue: DexKitBridge.() -> String = {
    val save = storySave()
    "${save.id};${save.label};${save.saveable.joinToString(",")}"
}

val storyMenuHandlerInitFingerprint = findMethodDirect {
    val fragment = storySave().isOwn.declaredClassName
    val handler = findMethod { matcher { usingEqStrings("menu_item_download") } }.single().declaredClassName
    findMethod {
        matcher { declaredClass(handler); name = "<init>"; paramTypes(fragment) }
    }.single()
}

private const val MEDIA_TRANSCODER = "com.facebook.msys.mci.transcoder.DefaultMediaTranscoder"

val videoTranscodeFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass(MEDIA_TRANSCODER)
            returnType = "void"
            paramTypes(
                "com.facebook.msys.mci.TranscodeVideoCompletionCallback", "com.facebook.msys.mci.VideoEdits",
                "java.lang.String", "java.lang.String", "java.util.Map",
            )
            usingEqStrings("mci_video_passthrough")
        }
    }.single { !Modifier.isStatic(it.modifiers) }
}

val videoSizeFieldFingerprint = findFieldDirect {
    val code = videoTranscodeFingerprint().instructions
    val margin = code.indices.single { i ->
        code[i].opcode == OP_CONST_WIDE_16 && code[i].literal == 8000L &&
            (i + 1..minOf(i + 8, code.lastIndex)).any { code[it].opcode == OP_CMP_LONG }
    }
    val reads = (margin downTo 0).map { code[it] }.filter { it.opcode == OP_IGET_WIDE }.take(2)
    val size = reads[1].fieldRef!!
    check(size.declaredClassName == reads[0].fieldRef!!.declaredClassName && size.typeName == "long")
    size
}

val videoMetadataReaderFingerprint = findMethodDirect {
    val meta = videoSizeFieldFingerprint().declaredClassName
    videoTranscodeFingerprint().invokes.single {
        Modifier.isStatic(it.modifiers) && it.returnTypeName == meta &&
            it.paramTypeNames == listOf("android.content.Context", "android.net.Uri", "boolean")
    }
}
