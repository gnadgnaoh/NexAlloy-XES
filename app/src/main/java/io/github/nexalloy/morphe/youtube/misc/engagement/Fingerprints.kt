package io.github.nexalloy.morphe.youtube.misc.engagement

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.fieldAccess
import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.youtube.shared.EngagementPanelControllerFingerprint

internal object EngagementPanelUpdateFingerprint : Fingerprint(
    classFingerprint = EngagementPanelControllerFingerprint,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Z"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Landroid/app/Activity;"
        )
    )
)

val panelInitFingerprint = findMethodDirect {
    panelClass().findMethod {
        matcher {
            name = "<init>"
        }
    }.single()
}

val panelIdField = findFieldDirect {
    panelClass().fields.single { it.typeName == "java.lang.String" }
}

val panelClass = findClassDirect {
    EngagementPanelControllerFingerprint.instructionMatches[3].instruction.classRef!!
}