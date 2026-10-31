package io.github.nexalloy.morphe.tiktok.readreceipts

import io.github.nexalloy.morphe.findMethodDirect
import java.lang.reflect.Modifier

internal const val IM_REQUEST = "com.bytedance.im.core.proto.Request"
internal const val MARK_READ_BODY = "com.bytedance.im.core.proto.MarkConversationReadRequestBody"
private const val MARK_READ_SENT = "Mark conversation read request completed successfully"
private const val MARK_READ_HANDLER = "skipMarkReadRequest: corrupted read_index detected, readIndex="

val markReadSendFingerprint = findMethodDirect {
    findMethod { matcher { usingEqStrings(MARK_READ_SENT) } }.filter { m ->
        !Modifier.isStatic(m.modifiers) && m.paramTypeNames.size == 2 && m.paramTypeNames[0] == IM_REQUEST
    }.single()
}

val markReadResultFingerprint = findMethodDirect {
    markReadSendFingerprint().invokes.filter { m ->
        m.isConstructor && m.paramTypeNames == listOf("boolean", "java.lang.String", "int", "java.lang.Exception")
    }.distinctBy { it.descriptor }.single()
}

val markReadHandlerSendFingerprint = findMethodDirect {
    val handler = findMethod { matcher { usingStrings(MARK_READ_HANDLER) } }.single().className
    getClassData(handler)!!.methods.filter { m ->
        !Modifier.isStatic(m.modifiers) && m.returnTypeName == "void" &&
            m.paramTypeNames == listOf("int", MARK_READ_BODY, "java.lang.String", "int")
    }.single()
}
