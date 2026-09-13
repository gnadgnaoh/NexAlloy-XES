package io.github.nexalloy.hoodles.morphe.protonvpn.premium

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.fieldAccess
import io.github.nexalloy.morphe.methodCall
import io.github.nexalloy.morphe.string

internal object VpnUserGetUserTierFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/auth/data/VpnUser;",
    name = "getUserTier",
    returnType = "I",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList(),
)

internal object VpnUserGetMaxTierFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/auth/data/VpnUser;",
    name = "getMaxTier",
    returnType = "Ljava/lang/Integer;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList(),
)

internal object VpnUserIsFreeUserFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/auth/data/VpnUser;",
    name = "isFreeUser",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList(),
)

internal object VpnUserIsUserPlusOrAboveFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/auth/data/VpnUser;",
    name = "isUserPlusOrAbove",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList(),
)

internal object VpnUserGetUserTierNameFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/auth/data/VpnUser;",
    name = "getUserTierName",
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList(),
)

internal object VpnUserConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/auth/data/VpnUser;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Lme/proton/core/domain/entity/UserId;",
        "I", "I", "I", "I", "Z", "I", "I",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z",
        "Ljava/lang/Integer;",
        "I",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "J",
        "Lme/proton/core/network/domain/session/SessionId;",
        "Ljava/lang/String;",
        "Lcom/protonvpn/android/models/login/NetShieldConfig;",
    ),
)

internal object HasAccessToServerFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    parameters = listOf(
        "Lcom/protonvpn/android/auth/data/VpnUser;",
        "Lcom/protonvpn/android/servers/Server;",
    ),
    filters = listOf(
        methodCall(definingClass = "Lcom/protonvpn/android/servers/Server;", name = "getTier"),
        methodCall(definingClass = "Lcom/protonvpn/android/auth/data/VpnUser;", name = "getUserTier"),
    ),
)

internal object HaveAccessWithFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/auth/data/VpnUserKt;",
    name = "haveAccessWith",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    parameters = listOf(
        "Lcom/protonvpn/android/servers/Server;",
        "Ljava/lang/Integer;",
    ),
)

internal object ServerListFilterFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC, AccessFlags.FINAL),
    parameters = listOf(
        "Z",
        "Lcom/protonvpn/android/redesign/countries/ui/ServerFilterType;",
        "Ljava/lang/String;",
        "Lcom/protonvpn/android/redesign/CityStateId;",
        "Z",
        "Ljava/lang/String;",
        "Lcom/protonvpn/android/servers/Server;",
    ),
    filters = listOf(
        methodCall(definingClass = "Lcom/protonvpn/android/servers/Server;", name = "isFreeServer"),
    ),
)

internal object ServerGroupGetAvailableFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/redesign/countries/ui/ServerGroupUiItem\$ServerGroup;",
    name = "getAvailable",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList(),
)

internal object GetBestScoreServerFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/utils/ServerManager;",
    name = "getBestScoreServer",
    returnType = "Lcom/protonvpn/android/servers/Server;",
    parameters = listOf(
        "Ljava/lang/Iterable;",
        "Lcom/protonvpn/android/auth/data/VpnUser;",
        "Lcom/protonvpn/android/vpn/ProtocolSelection;",
        "Ljava/util/List;",
    ),
)

internal object IsFeatureFlagEnabledFingerprint : Fingerprint(
    definingClass = "Lme/proton/core/featureflag/data/IsFeatureFlagEnabledImpl;",
    name = "invoke",
    returnType = "Z",
    parameters = listOf("Lme/proton/core/domain/entity/UserId;"),
    filters = listOf(
        methodCall(definingClass = "Lme/proton/core/featureflag/data/IsFeatureFlagEnabledImpl;", name = "isLocalEnabled"),
        methodCall(definingClass = "Lme/proton/core/featureflag/data/IsFeatureFlagEnabledImpl;", name = "isRemoteEnabled"),
    ),
)

internal object GetNetShieldAvailabilityFingerprint : Fingerprint(
    returnType = "Lcom/protonvpn/android/netshield/NetShieldAvailability;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    parameters = listOf("Lcom/protonvpn/android/auth/data/VpnUser;"),
    filters = listOf(
        methodCall(definingClass = "Lcom/protonvpn/android/auth/data/VpnUser;", name = "isFreeUser"),
        fieldAccess(name = "AVAILABLE"),
    ),
)

internal object GetFilterButtonsFingerprint : Fingerprint(
    returnType = "Ljava/util/List;",
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    parameters = listOf(
        "Ljava/util/Set;",
        "Lcom/protonvpn/android/redesign/countries/ui/ServerFilterType;",
        "I",
        "Ljava/util/Set;",
        "Lkotlin/jvm/functions/Function1;",
    ),
    filters = listOf(
        string("availableTypes"),
        methodCall(definingClass = "Lcom/protonvpn/android/redesign/countries/ui/ServerFilterType;", name = "getEntries"),
    ),
)

internal object ProfileCountriesFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/profiles/ui/ProfilesServerDataAdapter;",
    name = "countries",
    filters = listOf(
        methodCall(definingClass = "Lcom/protonvpn/android/servers/ServerManager2;", name = "getVpnCountries"),
    ),
)

internal object ServerManager2GetVpnCountriesFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/servers/ServerManager2;",
    name = "getVpnCountries",
    parameters = listOf("Lkotlin/coroutines/Continuation;"),
)

internal object ProfileAvailableTypesFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/profiles/ui/TypeAndLocationScreenState\$Standard;",
    name = "getAvailableTypes",
    returnType = "Ljava/util/List;",
    parameters = emptyList(),
)

internal object UpgradeOnboardingLaunchFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/ui/planupgrade/UpgradeDialogLauncherVM;",
    name = "launchOnboarding",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)

internal object ChangeServerViewStateFlowCollectFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/redesign/vpn/ui/ChangeServerViewStateFlow;",
    name = "collect",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(
        "Lkotlinx/coroutines/flow/FlowCollector;",
        "Lkotlin/coroutines/Continuation;",
    ),
)

internal object ServerManager2GetRandomServerFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/servers/ServerManager2;",
    name = "getRandomServer",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(
        "Lcom/protonvpn/android/auth/data/VpnUser;",
        "Lcom/protonvpn/android/vpn/ProtocolSelection;",
        "Lkotlin/coroutines/Continuation;",
    ),
)
