package io.github.nexalloy.revanced.messenger.privacy

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect

val plainReadReceiptRunnableFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name = "run"
            returnType = "void"
            paramCount = 0
            usingEqStrings(listOf("android_messaging_mark_read_start"))
        }
    }.single()
}

val encryptedReadReceiptFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            usingEqStrings(listOf("markAsReadThreadWithThreadIdentifier"))
        }
    }.single()
}

val plainTypingRunnablesFingerprint = findMethodListDirect {
    findMethod {
        matcher {
            name = "run"
            returnType = "void"
            paramCount = 0
            usingEqStrings(listOf("/t_st"))
        }
    }
}

val encryptedTypingFingerprint = findMethodDirect {
    findMethod {
        matcher {
            paramTypes("java.lang.String", "boolean")
            usingEqStrings(listOf("setTypingIndicatorForThreadWithThreadIdentifier"))
        }
    }.single()
}

val montageMarkReadHandlerFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramTypes("com.facebook.messaging.montage.model.MontageCard", "boolean")
            usingEqStrings(listOf("MontageMsysMarkReadHandler"))
        }
    }.single()
}
