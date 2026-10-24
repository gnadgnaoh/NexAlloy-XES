package io.github.nexalloy.morphe.youtube.misc.backgesture

import io.github.nexalloy.morphe.youtube.misc.playservice.VersionCheck
import io.github.nexalloy.morphe.youtube.misc.playservice.is_20_40_or_greater
import io.github.nexalloy.patch

val backGesturePatch = patch {
    dependsOn(VersionCheck)

    YouTubeMainActivityOnBackPressedFingerprint.hookMethod {
        before {
            beforeOnBackPressed.forEach { it() }
        }
        after {
            afterOnBackPressed.forEach { it() }

        }
    }

    if (is_20_40_or_greater) {
        PredictiveGesturesOnBackInvokedFingerprint.hookMethod {
            before {
                beforeOnBackInvoked.forEach { it() }
            }
        }
    }
}


private val beforeOnBackPressed = mutableListOf<() -> Unit>()
private val afterOnBackPressed = mutableListOf<() -> Unit>()
private val beforeOnBackInvoked = mutableListOf<() -> Unit>()

fun addBackPressedHook(
    hook: () -> Unit,
    afterActivityBackPressed: Boolean = false
) {
    (if (afterActivityBackPressed) afterOnBackPressed else beforeOnBackPressed)
        .add(hook)
}

fun addPredictiveBackGestureHook(
    hook: () -> Unit
) {
    beforeOnBackInvoked.add(hook)
}
