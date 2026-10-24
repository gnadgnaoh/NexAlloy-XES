package io.github.nexalloy.morphe.youtube.interaction.seekbar

import app.morphe.extension.youtube.patches.SlideToSeekPatch
import io.github.nexalloy.morphe.shared.misc.settings.preference.SwitchPreference
import io.github.nexalloy.morphe.youtube.misc.playservice.VersionCheck
import io.github.nexalloy.morphe.youtube.misc.playservice.is_21_40_or_greater
import io.github.nexalloy.morphe.youtube.misc.settings.PreferenceScreen
import io.github.nexalloy.patch
import io.github.nexalloy.scopedHook

val enableSlideToSeekPatch = patch(
    description = "Adds an option to enable slide to seek " +
        "instead of playing at 2x speed when pressing and holding in the video player."
) {
    dependsOn(VersionCheck)

    PreferenceScreen.SEEKBAR.addPreferences(
        SwitchPreference("morphe_slide_to_seek", summary = true),
    )

    // Restore the behavior to slide to seek.
    val checkMethod = ::slideToSeekCheckMethod.method

    // A/B check method was only called on this class.
    ::slideToSeekCheckCallers.dexMethodList.forEach { method ->
        method.hookMethod(scopedHook(checkMethod) {
            after { it.result = SlideToSeekPatch.isSlideToSeekDisabled(it.result as Boolean) }
        })
    }

    // Disable the double speed seek gesture.
    // NexAlloy: Older versions need a scoped getter hook to avoid Boolean unboxing recursion.
    if (is_21_40_or_greater) {
        DisableFastForwardGestureFingerprint.hookMethod(scopedHook(::fastForwardGestureCheckMethod.method) {
            after { it.result = SlideToSeekPatch.isSlideToSeekDisabled(it.result as Boolean) }
        })
    } else {
        val controllerField = ::fastForwardGestureControllerFieldLegacy.field
        val valueField = ::fastForwardGestureValueFieldLegacy.field
        DisableFastForwardGestureFingerprintLegacy.hookMethod(scopedHook(::fastForwardGestureCheckMethodLegacy.method) {
            after {
                if (innerDepth != 0 || it.hasThrowable()) return@after
                val controller = controllerField.get(outerParam.thisObject) ?: return@after
                if (it.thisObject !== valueField.get(controller)) return@after
                val value = it.result as? Boolean ?: return@after
                it.result = SlideToSeekPatch.isSlideToSeekDisabled(value)
            }
        })
    }
}
