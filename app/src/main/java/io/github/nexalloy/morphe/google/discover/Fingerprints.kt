package io.github.nexalloy.morphe.google.discover

import io.github.nexalloy.morphe.findMethodDirect
import java.util.Locale

val streamRenderableListFingerprint = findMethodDirect {
    val streamClasses = findClass {
        matcher {
            usingStrings(
                "WithContent(sessionRepresentation=",
                "contentSlices=",
                "elementsRenderableData=",
            )
        }
    }

    streamClasses
        .flatMap { cls ->
            runCatching {
                findMethod {
                    matcher {
                        declaredClass(cls.name)
                        returnType("java.util.List")
                        paramCount(0)
                    }
                }
            }.getOrDefault(emptyList())
        }
        .filter { it.name != "<init>" && it.name != "<clinit>" }
        .maxByOrNull { scoreStreamMethodCandidate(it.name, it.declaredClassName) }
        ?: error("streamRenderableListMethod not found in AGSA")
}

/** Mirror of DexKitResolver.scoreStreamMethodCandidate(). */
private fun scoreStreamMethodCandidate(methodName: String, className: String): Int {
    val mn = methodName.lowercase(Locale.ROOT)
    val cn = className.lowercase(Locale.ROOT)
    var score = 40
    if ("content" in mn) score += 20
    if ("element" in mn) score += 15
    if ("render" in mn) score += 15
    if ("discover" in cn) score += 15
    if ("stream" in cn) score += 20
    return score
}
