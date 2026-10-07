package io.github.nexalloy.morphe.twitter.timeline.showpollresults

import io.github.nexalloy.patch

private const val COUNTS_ARE_FINAL = "counts_are_final"
private const val POLL_MARKER = "choice1_count"

val ShowPollResults = patch(
    name = "Show poll results",
    description = "Adds an option to show poll results without voting.",
) {
    val booleanValueCtor = CardBooleanValueToStringFingerprint.declaredClass
        .getDeclaredConstructor(Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }

    LegacyCardBindingValuesFingerprint.hookMethod {
        after { param ->
            @Suppress("UNCHECKED_CAST")
            val map = param.result as? Map<Any?, Any?> ?: return@after

            if (!map.containsKey(POLL_MARKER)) return@after

            val current = map[COUNTS_ARE_FINAL]
            if (current != null && current.toString().contains("value=true")) return@after

            val patched = LinkedHashMap<Any?, Any?>(map)
            patched[COUNTS_ARE_FINAL] = booleanValueCtor.newInstance(true)
            param.result = patched
        }
    }
}
