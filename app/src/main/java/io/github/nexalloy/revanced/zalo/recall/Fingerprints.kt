package io.github.nexalloy.revanced.zalo.recall

import io.github.nexalloy.morphe.findMethodDirect

val applyUndoToChatDbFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingEqStrings(
                "updateMessageUndo->updateChatMsgAPI",
                "isForceUpdateFields=true",
            )
            returnType = "void"
            paramTypes(null, "boolean", null)
        }
    }.single()
}

val applyUndoEntryFingerprint = findMethodDirect {
    applyUndoToChatDbFingerprint().callers
        .single { it.returnTypeName == "void" && it.paramTypeNames.size == 1 }
}
