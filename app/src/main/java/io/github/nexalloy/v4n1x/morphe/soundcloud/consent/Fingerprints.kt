package io.github.nexalloy.v4n1x.morphe.soundcloud.consent

import io.github.nexalloy.morphe.Fingerprint

object PrivacyConsentControllerProviderFingerprint : Fingerprint(
    definingClass = "Lcom/soundcloud/android/privacy/consent/main/PrivacyConsentControllerModule\$Companion;",
    name = "a",
    returnType = "Lcom/soundcloud/android/privacy/consent/base/PrivacyConsentController;",
    parameters = listOf(
        "Lcom/soundcloud/android/privacy/legislation/LegislationOperations;",
        "Ldagger/Lazy;",
    ),
)
