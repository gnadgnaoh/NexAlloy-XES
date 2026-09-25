package io.github.nexalloy.revanced.facebook.privacy

import io.github.nexalloy.morphe.findMethodListDirect
import java.lang.reflect.Modifier

val storyViewerNonCriticalSetupFingerprint = findMethodListDirect {
    findMethod { matcher { usingStrings("StoryviewerFragment.initializeNonCriticalControllers") } }
        .filter { m ->
            m.isMethod && !Modifier.isAbstract(m.modifiers) &&
                !(Modifier.isStatic(m.modifiers) && m.returnTypeName == "java.lang.String" &&
                    m.paramTypeNames == listOf("int"))
        }
        .distinctBy { it.descriptor }
}
