package io.github.nexalloy.morphe.twitter.timeline.forceTranslate

import io.github.nexalloy.morphe.twitter.timeline.tweetInfoHook.TweetInfoHook
import io.github.nexalloy.morphe.twitter.timeline.tweetInfoHook.forceTranslateEnabled
import io.github.nexalloy.patch

val ForceTranslate = patch(
    name = "Force enable translate",
    description = "Get translate option for all posts.",
) {
    dependsOn(TweetInfoHook)
    forceTranslateEnabled = true
}
