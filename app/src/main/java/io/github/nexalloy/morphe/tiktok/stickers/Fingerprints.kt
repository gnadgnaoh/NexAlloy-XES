package io.github.nexalloy.morphe.tiktok.stickers

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import java.lang.reflect.Modifier

internal const val STICKER_TEMPLATE = "com.ss.android.ugc.aweme.im.message.template.card.StickerTemplate"
internal const val COMMENT_STICKER = "com.ss.android.ugc.aweme.comment.model.CommentStickerStruct"
private const val STICKER_CLICK_HANDLER = "com.ss.android.ugc.aweme.im.sdk.chat.ui.gif.DefaultStickerClickHandler"
private const val CHAT_STICKER_CLICK = "chat_general_sticker_click"
private const val VIEW = "android.view.View"

val chatStickerClickFingerprint = findMethodDirect {
    findMethod { matcher { declaredClass(STICKER_CLICK_HANDLER); returnType = "void"; usingEqStrings(CHAT_STICKER_CLICK) } }
        .single { m -> m.paramTypeNames.firstOrNull() == VIEW && m.paramTypeNames.lastOrNull() == STICKER_TEMPLATE }
}

val commentStickerClickFingerprint = findMethodListDirect {
    findMethod { matcher { name = "<init>"; paramTypes(COMMENT_STICKER, null, null, VIEW, null) } }
        .filter { c -> c.paramTypeNames.firstOrNull() == COMMENT_STICKER && VIEW in c.paramTypeNames }
        .flatMap { it.callers }
        .filter { m -> m.isMethod && m.returnTypeName == "void" && m.paramTypeNames.lastOrNull() == VIEW && m.name.startsWith("onClick") }
        .distinctBy { it.descriptor }
        .also { check(it.isNotEmpty()) { "no comment sticker click" } }
}
