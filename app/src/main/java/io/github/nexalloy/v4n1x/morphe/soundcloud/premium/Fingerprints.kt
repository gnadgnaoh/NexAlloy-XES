package io.github.nexalloy.v4n1x.morphe.soundcloud.premium

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.findMethodListDirect

object FeatureConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/soundcloud/android/configuration/plans/Feature;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Ljava/lang/String;",
        "Z",
        "Ljava/util/List;",
    ),
)

object UserConsumerPlanConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/soundcloud/android/configuration/plans/UserConsumerPlan;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Ljava/lang/String;",
        "Z",
        "Ljava/lang/String;",
        "Ljava/util/List;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
    ),
)

object GetDowngradeTierFingerprint : Fingerprint(
    definingClass = "Lcom/soundcloud/android/configuration/data/ConfigurationSettingsStorage;",
    returnType = "Lcom/soundcloud/android/configuration/plans/Tier;",
    strings = listOf("pending_plan_downgrade"),
)

object MapToPlanFingerprint : Fingerprint(
    definingClass = "Lcom/soundcloud/android/upsell/UpsellVisibilityController;",
    name = "mapToPlan",
)

val adPlacementConfigurationConstructorsFingerprint = findMethodListDirect {
    findMethod {
        matcher {
            declaredClass =
                "com.soundcloud.android.ads.display.data.config.AdPlacementConfiguration"
            name = "<init>"
        }
    }
}
