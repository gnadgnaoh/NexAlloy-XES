package io.github.nexalloy.revanced.zalo.call

import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import java.lang.reflect.Modifier

private const val CALL_CALLBACK_CLASS = "com.vng.zing.vn.zrtc.CallCallback"
private const val PEER_IS_IN_CALL =
    "Lcom/vng/zing/vn/zrtc/PeerJNI;->zrtc_peer_is_in_call(J)Z"

val callCallbackImplFingerprint = findClassDirect {
    findClass {
        matcher { superClass = CALL_CALLBACK_CLASS }
    }.filter { candidate ->
        candidate.methods.any { it.isMethod && CallRecordingLifecycle.observes(it.name) }
    }.single()
}

private fun FieldData.isInstance() = !Modifier.isStatic(modifiers)

private fun ClassData.singleLongField(): FieldData? =
    fields.filter { it.isInstance() && it.typeName == "long" }.singleOrNull()

private fun DexKitBridge.peerContainerFieldOf(manager: ClassData): FieldData? =
    manager.fields
        .filter { it.isInstance() && !it.typeName.startsWith("java.") && it.typeName != "long" }
        .filter { field -> classOf(field)?.singleLongField() != null }
        .singleOrNull()

private fun DexKitBridge.classOf(field: FieldData): ClassData? =
    runCatching { getClassData(field.typeSign) }.getOrNull()

private fun ClassData.selfAccessors() =
    methods.filter {
        it.isMethod && Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames.isEmpty() && it.returnTypeName == name
    }

val callPeerManagerFingerprint = findClassDirect {
    findMethod {
        matcher { addInvoke(PEER_IS_IN_CALL) }
    }.map { it.declaredClass!! }
        .distinctBy { it.name }
        .filter { manager ->
            manager.selfAccessors().size == 1 && peerContainerFieldOf(manager) != null
        }
        .single()
}

val callPeerManagerAccessorFingerprint = findMethodDirect {
    callPeerManagerFingerprint().selfAccessors().single()
}

val callPeerContainerField = findFieldDirect {
    peerContainerFieldOf(callPeerManagerFingerprint())!!
}

val callPeerHandleField = findFieldDirect {
    val container = callPeerContainerField()
    classOf(container)!!.singleLongField()!!
}
