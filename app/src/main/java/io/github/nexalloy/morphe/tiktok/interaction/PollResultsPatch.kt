package io.github.nexalloy.morphe.tiktok.interaction

import io.github.nexalloy.patch

val ShowPollResults = patch(
    name = "Show poll results before voting",
    description = "Comment polls show how many picked each answer before you vote. Tapping an answer still votes.",
) {
    ::pollShowsResultsFingerprint.hookMethod { after { param -> param.result = true } }
}
