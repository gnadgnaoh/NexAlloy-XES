package io.github.nexalloy.morphe.youtube.interaction.swipecontrols

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.RestrictQuery
import io.github.nexalloy.morphe.fieldAccess
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.literal
import io.github.nexalloy.morphe.methodCall
import io.github.nexalloy.morphe.opcode

@RestrictQuery
internal object PlayerOverlayContainerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = $$"Landroid/view/ViewGroup$LayoutParams;",
    parameters = listOf(),
    filters = listOf(
        opcode(Opcode.NEW_INSTANCE),
        literal(-1),
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            definingClass = "this"
        ),
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>",
            parameters = listOf("I", "I", "Z")
        )
    ),
    custom = {
        addUsingField { type = "boolean" }
    }
)

val PlayerOverlayNameField = findFieldDirect {
    PlayerOverlayContainerFingerprint().declaredClass!!.fields.first { it.typeName == "java.lang.String" }
}