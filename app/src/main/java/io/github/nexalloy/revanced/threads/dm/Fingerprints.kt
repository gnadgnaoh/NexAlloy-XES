package io.github.nexalloy.revanced.threads.dm

import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import java.lang.reflect.Modifier

private const val THREAD_ACTION_SENDER = "com.instagram.barcelona.messaging.slidesend.api.BcnThreadActionSender"
private const val MARK_READ_MUTATION = "BcnMarkThreadReadMutation"
private const val SEND_FAILURE = "Unknown send failure"
private const val OP_SGET_OBJECT = 0x62

val markThreadReadFingerprint = findMethodDirect {
    findMethod { matcher { declaredClass(THREAD_ACTION_SENDER); usingEqStrings(MARK_READ_MUTATION) } }.single()
}

val threadActionSentFieldFingerprint = findFieldDirect {
    val senders = findMethod { matcher { declaredClass(THREAD_ACTION_SENDER); usingEqStrings(SEND_FAILURE) } }
        .flatMap { it.invokes }.filter { m ->
            m.className == THREAD_ACTION_SENDER && Modifier.isStatic(m.modifiers) && "java.lang.String" in m.paramTypeNames
        }.distinctBy { it.descriptor }
    senders.flatMap { it.instructions }.mapNotNull { insn ->
        insn.fieldRef?.takeIf { f ->
            insn.opcode == OP_SGET_OBJECT && f.typeName == f.declaredClassName &&
                getClassData(f.typeName)?.superClass?.name != "java.lang.Enum"
        }
    }.distinctBy { it.descriptor }.single()
}

private const val TYPING_MUTATION = "BcnPublishTypingIndicatorEventMutation"

val publishTypingFingerprint = findMethodDirect {
    findMethod { matcher { returnType = "void"; usingEqStrings(TYPING_MUTATION) } }
        .single { it.paramTypeNames == listOf("long", "java.lang.String", "boolean") }
}
