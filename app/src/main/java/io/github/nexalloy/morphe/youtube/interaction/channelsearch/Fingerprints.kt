package io.github.nexalloy.morphe.youtube.interaction.channelsearch

import io.github.nexalloy.SkipTest
import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.InstructionLocation.MatchBeforeAnywhere
import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.ResourceType
import io.github.nexalloy.morphe.fieldAccess
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.indexOfFirstInstructionOrThrow
import io.github.nexalloy.morphe.methodCall
import io.github.nexalloy.morphe.opcode
import io.github.nexalloy.morphe.resourceLiteral
import io.github.nexalloy.morphe.string

val endpointField = findFieldDirect {
    BrowseFragmentOnCreateViewFingerprint.instructionMatches[1].instruction.fieldRef!!
}

val browserIdMethod = findMethodDirect {
    BrowseFragmentOnCreateViewFingerprint().let {
        val endpointIndex = BrowseFragmentOnCreateViewFingerprint.instructionMatches[1].index

        // Browse data is an extension of the endpoint, and reading it starts with that field.
        val browseDataExtensionIndex = it.indexOfFirstInstructionOrThrow(
            endpointIndex, Opcode.SGET_OBJECT
        )
        val browseDataExtensionField = it.instructions[browseDataExtensionIndex].fieldRef!!

        Fingerprint(
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
            returnType = "Ljava/lang/String;",
            parameters = listOf(endpointField().type.descriptor),
            filters = listOf(
                fieldAccess(opcode = Opcode.SGET_OBJECT, reference = browseDataExtensionField)
            )
        ).matchAll(1..2).first().method // Both matches are identical methods in the same class.
    }
}

val BrowseFragmentTarget = findMethodDirect {
    val definingClass = BrowseFragmentOnCreateViewFingerprint().declaredClass!!.descriptor

    Fingerprint(
        definingClass = definingClass,
        returnType = "V",
        parameters = listOf(endpointField().type.descriptor),
        filters = listOf(
            fieldAccess(opcode = Opcode.IPUT_OBJECT, reference = endpointField())
        )
    )()
}

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2964
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

/**
 * Every browse page is shown by this one fragment, and the endpoint it is handed names the page.
 * The browse request cannot be used instead, because a page served from cache makes no request.
 */
internal object BrowseFragmentOnCreateViewFingerprint : Fingerprint(
    returnType = "Landroid/view/View;",
    parameters = listOf(
        "Landroid/view/LayoutInflater;",
        "Landroid/view/ViewGroup;",
        "Landroid/os/Bundle;"
    ),
    filters = listOf(
        string("Browse Fragment was given a navigation endpoint without browse data."),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            location = MatchBeforeAnywhere()
        )
    )
)

/**
 * The search feed is its own fragment, so it does not go through the browse fragment when
 * the user returns to it from a channel.
 */
internal object SearchResultsFragmentOnCreateViewFingerprint : Fingerprint(
    returnType = "Landroid/view/View;",
    parameters = listOf(
        "Landroid/view/LayoutInflater;",
        "Landroid/view/ViewGroup;",
        "Landroid/os/Bundle;"
    ),
    filters = listOf(
        string("search_cache_key")
    )
)

/**
 * Picks the hint of the search box. The default hint is loaded last, after the hints of
 * Shorts search and playlist search.
 */
internal object SearchBoxHintFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "shorts_search_hint"),
        resourceLiteral(ResourceType.STRING, "playlists_search_hint"),
        resourceLiteral(ResourceType.STRING, "search_hint"),
        opcode(Opcode.MOVE_RESULT_OBJECT)
    )
)

val SearchSubmitFingerprint = findMethodDirect {
    try {
        SearchSubmitFingerprint2143()
    } catch (_: Exception) {
        try {
            SearchSubmitFingerprint1()
        } catch (_: Exception) {
            SearchSubmitFingerprint2()
        }
    }
}

/**
 * Every search submit path funnels through this method, including suggestions and filter chips.
 */
@SkipTest
internal object SearchSubmitFingerprint2143 : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(
        "Ljava/lang/String;",
        "I",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z",
        "Ljava/lang/String;"
    ),
    filters = listOf(
        methodCall( // 21.43+
            parameters = listOf(
                "Ljava/lang/String;",
                "[B",
                "Ljava/lang/String;",
                "I",
                "L",
                "L",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Z",
                "Ljava/lang/String;"
            ),
            returnType = "V"
        )
    )
)

/**
 * Every search submit path funnels through this method, including suggestions and filter chips.
 */
@SkipTest
internal object SearchSubmitFingerprint1 : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(
        "Ljava/lang/String;",
        "I",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z"
    ),
    filters = listOf(
        methodCall( // 21.31 - 21.42
            parameters = listOf(
                "Ljava/lang/String;",
                "[B",
                "Ljava/lang/String;",
                "I",
                "L",
                "L",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Z"
            ),
            returnType = "V"
        )
    ),
)

/**
 * Every search submit path funnels through this method, including suggestions and filter chips.
 */
@SkipTest
internal object SearchSubmitFingerprint2 : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),

    parameters = listOf(
        "Ljava/lang/String;",
        "I",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;"
    ),
    filters = listOf(
        methodCall( // 21.30 and older.
            parameters = listOf(
                "Ljava/lang/String;",
                "[B",
                "Ljava/lang/String;",
                "I",
                "L",
                "L",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Ljava/lang/String;"
            ),
            returnType = "V"
        )
    )
)
