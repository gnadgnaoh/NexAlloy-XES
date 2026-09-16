package io.github.nexalloy.morphe.youtube.misc.contexthook

import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.music.misc.playservice.versionCheckPatch
import io.github.nexalloy.morphe.youtube.misc.playservice.is_21_21_or_greater
import io.github.nexalloy.morphe.youtube.misc.playservice.is_21_33_or_greater
import io.github.nexalloy.patch
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Field

enum class Endpoint {
    BROWSE(BrowseEndpointParentFingerprint),
    GET_WATCH({ ::GetWatchEndpointConstructor.dexMethodList }),
    GUIDE(GuideEndpointConstructorFingerprint),
    NEXT(NextEndpointParentFingerprint),
    PLAYER(PlayerEndpointParentFingerprint),
    REEL(
        // 21.21+ removed "reel/create_reel_items" and the replacement isn't clear.
        *(arrayOf(
            ReelItemWatchEndpointConstructorFingerprint,
            ReelWatchSequenceEndpointConstructorFingerprint,
        ) + if (!is_21_21_or_greater) arrayOf(ReelCreateItemsEndpointConstructorFingerprint) else emptyArray())
    ),
    SEARCH(SearchRequestBuildParametersFingerprint),
    TRANSCRIPT(TranscriptEndpointConstructorFingerprint);

    val matchers: List<PatchExecutor.() -> List<DexMethod>>
    var hooks = mutableListOf<(Any) -> Unit>()

    constructor(vararg parentFingerprints: Fingerprint) {
        this.matchers = parentFingerprints.map { { listOf(it.dexMethod) } }
    }

    constructor(vararg parentFingerprints: PatchExecutor.() -> List<DexMethod>) {
        this.matchers = parentFingerprints.toList()
    }
}

val clientContextHookPatch = patch(
    description = "Hooks the context body of the endpoint.",
) {
    dependsOn(versionCheckPatch)

    val clientInfoField = ::clientInfoField.field
    val messageLiteBuilderField = ::messageLiteBuilderField.field

    val messageLiteBuilderMethod = if (is_21_33_or_greater)
        ::messageLiteBuilderMethod.method else
        ::messageLiteBuilderMethodLegacy.method

//    clientVersionFieldRef = ::clientVersionField.field
    osNameFieldRef = ::osNameField.field
//    clientFormFactorFieldRef = ::clientFormFactorField.field

    Endpoint.entries.forEach { endpoint ->
        endpoint.matchers.forEach { matcher ->
            matcher().forEach { method ->
                method.hookMethod {
                    after {
                        val clientInfo = it.thisObject
                            .let { messageLiteBuilderMethod(it) }
                            .let { messageLiteBuilderField.get(it) }
                            .let { clientInfoField.get(it) }

                        if (clientInfo != null)
                            endpoint.hooks.forEach { hook ->
                                hook(clientInfo)
                            }
                    }
                }
            }
        }
    }
}

//lateinit var clientVersionFieldRef: Field
lateinit var osNameFieldRef: Field
//lateinit var clientFormFactorFieldRef: Field

// TODO addClientFormFactorHook

// TODO addClientVersionHook

fun addOSNameHook(endPoint: Endpoint, hook: (String?) -> String) {
    endPoint.hooks.add { clientInfo ->
        osNameFieldRef.set(clientInfo, hook(osNameFieldRef.get(clientInfo) as String?))
    }
}