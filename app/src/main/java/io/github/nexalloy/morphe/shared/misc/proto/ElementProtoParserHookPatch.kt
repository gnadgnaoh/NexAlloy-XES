package io.github.nexalloy.morphe.shared.misc.proto

import io.github.nexalloy.patch


/**
 * Shared factory for the element proto parser hook used by both YouTube and YT Music.
 *
 * Hooks the generic proto parser method so that patched extensions can inspect (and modify)
 * the raw byte buffer of every parsed proto element.
 *
 * @param sharedExtensionPatchDep The app-specific `sharedExtensionPatch`.
 */
internal fun createElementProtoParserHookPatch(
) = patch(
    description = "Hook to modify the proto message class, which can only be accessed through reflection.",
) {

    NewElementProtoParserFingerprint.hookMethod {
        after {
            var result = it.result as ByteArray?
            hooks.forEach {
                result = it(result)
            }
            it.result = result
        }
    }
}

private val hooks = mutableListOf<(ByteArray?) -> ByteArray?>()
fun hookElement(hook: (ByteArray?) -> ByteArray?) {
    hooks.add(hook)
}