package io.github.nexalloy.revanced.instagram.time

import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.findClassDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import io.github.nexalloy.revanced.shared.time.CONTEXT
import io.github.nexalloy.revanced.shared.time.STRING
import io.github.nexalloy.revanced.shared.time.relativeTimeFormatter
import java.lang.reflect.Modifier

private const val REEL_ITEM = "com.instagram.model.reels.ReelItem"
private const val OP_SGET_WIDE = 0x61

val storyTimeLabelFingerprint = findMethodDirect {
    findMethod {
        matcher { declaredClass(REEL_ITEM); paramTypes("android.content.Context"); returnType = "java.lang.String" }
    }.single { !Modifier.isStatic(it.modifiers) }
}

val storyPostedSecondsGetterFingerprint = findMethodDirect {
    storyTimeLabelFingerprint().instructions.mapNotNull { it.methodRef }
        .first { it.declaredClassName == REEL_ITEM && it.returnTypeName == "long" && it.paramCount == 0 }
}

val storyClockOffsetFieldFingerprint = findFieldDirect {
    storyTimeLabelFingerprint().instructions.first { it.opcode == OP_SGET_WIDE }.fieldRef!!
}

val storyHeaderBuildersFingerprint = findMethodListDirect {
    val label = storyTimeLabelFingerprint()
    findMethod {
        matcher { addInvoke(label.descriptor) }
    }.filter { m -> m.declaredClassName != REEL_ITEM }
}

val storyHeaderTimeFormattersFingerprint = findMethodListDirect {
    val label = storyTimeLabelFingerprint()
    storyHeaderBuildersFingerprint().flatMap { it.instructions.mapNotNull { i -> i.methodRef } }.filter { m ->
        m.descriptor == label.descriptor || (m.returnTypeName == "java.lang.String" &&
            ("long" in m.paramTypeNames || "double" in m.paramTypeNames) &&
            !m.declaredClassName.startsWith("java.") && !m.declaredClassName.startsWith("android.") &&
            m.declaredClassName != REEL_ITEM)
    }.distinctBy { it.descriptor }
}

private fun DexKitBridge.askingFormatter(marker: String, type: StringMatchType) = findMethod {
    matcher {
        usingStrings(listOf(marker), type)
        addInvoke { declaredClass(relativeTimeFormatter().name); paramTypes(CONTEXT, null); returnType = STRING }
    }
}.single()

val feedFooterBuilderFingerprint = findMethodDirect { askingFormatter("CoalescedFooterUseCase#getUiState", StringMatchType.Equals) }

val commentHeaderFingerprint = findMethodDirect {
    findMethod {
        matcher { usingStrings(listOf("com.instagram.comments.mvvm.view.compose.CommentAuthorHeader ("), StringMatchType.StartsWith) }
    }.single()
}

val commentRowTimeFieldFingerprint = findFieldDirect {
    findClass { matcher { addUsingString("row_comment_textview_time_ago", StringMatchType.Equals) } }.mapNotNull { c ->
        c.fields.filter { !Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.Long" }.singleOrNull()
    }.single()
}

val commentRowTimeReadersFingerprint = findMethodListDirect {
    val time = commentRowTimeFieldFingerprint()
    findMethod {
        matcher {
            addUsingField(time.descriptor)
            addInvoke { declaredClass(relativeTimeFormatter().name); paramTypes(CONTEXT, null); returnType = STRING }
        }
    }
}

private const val PURGE = "_"

val reelTitleRenderFingerprint = findMethodDirect {
    findMethod {
        matcher { usingStrings(listOf(PURGE + "ClipsTitleAndSubtitleComponent_render"), StringMatchType.Contains) }
    }.single { !Modifier.isStatic(it.modifiers) }
}

val reelSubtitleTextClassFingerprint = findClassDirect {
    findMethod {
        matcher { usingStrings(listOf(PURGE + "CyclicSubtitleWithIconComponent_render"), StringMatchType.Contains) }
    }.single().declaredClass!!
}

val mediaTakenAtGetterFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass("com.instagram.feed.media.Media")
            paramCount = 0
            returnType = "java.lang.Long"
            usingNumbers("taken_at".hashCode())
        }
    }.single { !Modifier.isStatic(it.modifiers) }
}
