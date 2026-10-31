package io.github.nexalloy.morphe.tiktok.unsent

import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import java.lang.reflect.Modifier

private const val RECALLED_FLAG = "s:is_recalled"
private const val ATTACHMENT_TABLE_LOG = "IMAttachmentDao getAttachments"
private val RECALL_TAGS = listOf("RecallMsgFeature", "RecallMsgUseCase")
private val MESSAGE_METHODS = setOf("isRecalled", "getUuid", "getMessageStatus", "setContent", "getAttachments")

val imMessageClassFingerprint = findClassDirect {
    findClass { matcher { usingStrings(RECALLED_FLAG) } }.filter { cls ->
        cls.methods.map { it.name }.toSet().containsAll(MESSAGE_METHODS)
    }.single()
}

val recallHandlerMethodsFingerprint = findMethodListDirect {
    RECALL_TAGS.flatMap { tag -> findMethod { matcher { usingStrings(tag) } } }.distinctBy { it.descriptor }
        .also { check(it.isNotEmpty()) { "no recall handler found" } }
}

val attachmentTableReadFingerprint = findMethodDirect {
    findMethod { matcher { usingStrings(ATTACHMENT_TABLE_LOG) } }.filter { m ->
        m.paramTypeNames == listOf("java.lang.String") && m.returnTypeName == "java.util.List"
    }.single()
}

val attachmentTableDeleteMethodsFingerprint = findMethodListDirect {
    getClassData(attachmentTableReadFingerprint().className)!!.methods.filter { m ->
        m.isMethod && !Modifier.isStatic(m.modifiers) && !Modifier.isAbstract(m.modifiers) &&
            m.returnTypeName == "void" && m.paramTypeNames == listOf("java.lang.String")
    }.also { check(it.isNotEmpty()) { "attachment table has no delete by message id" } }
}
