package io.github.nexalloy.revanced.instagram.ads

import io.github.nexalloy.morphe.findMethodDirect

val adInsertGateFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "boolean"
            usingStrings("injection_orchestrator_position_passed_with_insertion_but_not_impression_")
        }
    }.single()
}
