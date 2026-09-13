package io.github.nexalloy.morphe.twitter.ads.timelineEntryHook

import io.github.nexalloy.patch

val HideRecommendationItems = patch(
    name = "Hide recommendation items",
    description = "Hides recommendation items such as \"Who to follow\" and \"Today's news\" in " +
        "timeline, search, and replies.",
) {
    dependsOn(TimelineEntryHook)

    // Entry-id rules only; add ClientEventInfo component names to recommendationComponents
    // (and set logTimelineComponents to find them) when a recommendation has no stable entry id.
    hideRevisitPinnedPostsEnabled = true
    hideCommunitiesToJoinEnabled = true
    hideCreatorsToSubscribeEnabled = true
    hideDetailedPostsEnabled = true
    hidePremiumPromptEnabled = true
    hideRevisitBookmarksEnabled = true
    hideTodaysNewsEnabled = true
    hideTopPeopleSearchEnabled = true
    hideWhoToFollowEnabled = true
}
