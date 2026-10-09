package io.github.nexalloy.morphe.youtube.video.information

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.InstructionLocation.MatchAfterWithin
import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.OpcodesFilter
import io.github.nexalloy.morphe.StringComparisonType
import io.github.nexalloy.morphe.fieldAccess
import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.fingerprint
import io.github.nexalloy.morphe.literal
import io.github.nexalloy.morphe.methodCall
import io.github.nexalloy.morphe.opcode
import io.github.nexalloy.morphe.string
import io.github.nexalloy.morphe.youtube.shared.PlaybackSpeedOnItemClickParentFingerprint
import io.github.nexalloy.morphe.youtube.shared.VideoQualityClass
import io.github.nexalloy.morphe.youtube.shared.videoQualityChangedFingerprint
import org.luckypray.dexkit.query.enums.OpCodeMatchType
import org.luckypray.dexkit.result.FieldUsingType

internal object PlaybackSpeedOnItemClickFingerprint : Fingerprint(
    classFingerprint = PlaybackSpeedOnItemClickParentFingerprint,
    name = "onItemClick",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "L", "I", "J"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET,
            type = "F"
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("F"),
            returnType = "V"
        )
    )
)

val setPlaybackSpeedMethodReference = findMethodDirect {
    PlaybackSpeedOnItemClickFingerprint().invokes.findMethod { matcher { paramTypes("float") } }
        .single()
}

val PlayerControllerClass = findClassDirect { setPlaybackSpeedMethodReference().declaredClass!! }

internal object PlayerControllerSetTimeReferenceFingerprint : Fingerprint(
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_DIRECT_RANGE,
        Opcode.IGET_OBJECT
    ) + string("Media progress reported outside media playback: ", comparison = StringComparisonType.CONTAINS)
)

val timeMethod = findMethodDirect {
    PlayerControllerSetTimeReferenceFingerprint().invokes.single { it.name == "<init>" }
}

internal object PlayerInitFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.CONSTRUCTOR),
    custom = {
        declaredClass {
            addEqString("playVideo called on player response with no videoStreamingData.")
        }
    }
)

internal object SeekFingerprint : Fingerprint(
    classFingerprint = PlayerInitFingerprint,
    filters = listOf(
        string("currentPositionMs.")
    )
)

val seekSourceType = findClassDirect {
    SeekFingerprint().paramTypes[1]
}

private object CreateVideoPlayerSeekbarFingerprint : Fingerprint(
    name = "onDraw",
    returnType = "V",
    filters = listOf(
        string("timed_markers_width")
    )
)

internal object VideoLengthFingerprint : Fingerprint(
    classFingerprint = CreateVideoPlayerSeekbarFingerprint,
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall("Landroid/graphics/Rect;->set(Landroid/graphics/Rect;)V"),

        methodCall(returnType = "J"),
        methodCall(returnType = "J", location = MatchAfterWithin(5)),
        methodCall(returnType = "J", location = MatchAfterWithin(10)),
        methodCall(returnType = "J", location = MatchAfterWithin(10)),

        methodCall(returnType = "Z", parameters = listOf()),
        opcode(Opcode.CMP_LONG, location = MatchAfterWithin(8))
    )
)


val videoLengthField = findFieldDirect {
    VideoLengthFingerprint().usingFields.single { it.usingType == FieldUsingType.Write && it.field.typeName == "long" }.field
}

val videoLengthHolderField = findFieldDirect {
    val videoLengthField = videoLengthField()
    VideoLengthFingerprint().usingFields.single { it.usingType == FieldUsingType.Read && it.field.typeName == videoLengthField.declaredClassName }.field
}

object MdxSeekFingerprint : Fingerprint(
    classFingerprint = MdxPlayerDirectorSetVideoStageFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("J", "L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.RETURN,
    ),
    custom = {
        opCodesMatcher!!.matchType = OpCodeMatchType.Equals
    }
)


val mdxSeekSourceType = findClassDirect {
    MdxSeekFingerprint().paramTypes[1]
}

internal object MdxPlayerDirectorSetVideoStageFingerprint : Fingerprint(
    filters = listOf(
        string("MdxDirector setVideoStage ad should be null when videoStage is not an Ad state "),
    )
)

internal object MdxSeekRelativeFingerprint : Fingerprint(
    classFingerprint = MdxPlayerDirectorSetVideoStageFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    // Return type is boolean up to 19.39, and void with 19.39+.
    parameters = listOf("J", "L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IGET_OBJECT,
        Opcode.INVOKE_INTERFACE,
    )
)

internal object SeekRelativeFingerprint : Fingerprint(
    classFingerprint = PlayerInitFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    // Return type is boolean up to 19.39, and void with 19.39+.
    parameters = listOf("J", "L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.ADD_LONG_2ADDR,
        Opcode.INVOKE_VIRTUAL,
    )
)

internal object GetVideoTimeFingerprint : Fingerprint(
    classFingerprint = PlayerInitFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    returnType = "V",
    filters = listOf(
        methodCall(
            // getVideoTime()
            definingClass = "this",
            returnType = "J",
            parameters = listOf(),
        ),
        literal(69, location = MatchAfterWithin(5))
    )
)

val getVideoTime = findMethodDirect {
    GetVideoTimeFingerprint.instructionMatches.first().instruction.methodRef!!
}

val mdxGetVideoTime = findMethodDirect {
    findMethod {
        matcher {
            declaredClass(MdxPlayerDirectorSetVideoStageFingerprint().declaredClassName)
            name(getVideoTime().name)
            returnType("long")
            paramTypes()
        }
    }.single()
}

/**
 * Resolves with the class found in [videoQualityChangedFingerprint].
 */
val playbackSpeedMenuSpeedChangedFingerprint = fingerprint {
    classFingerprint(videoQualityChangedFingerprint)
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returns("L")
    parameters("L")
    opcodes(
        Opcode.IGET,
        Opcode.INVOKE_VIRTUAL,
        Opcode.SGET_OBJECT,
        Opcode.RETURN_OBJECT,
    )
}

val videoQualitySetterFingerprint = fingerprint {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returns("V")
    parameters("[L", "I", "Z")
    opcodes(
        Opcode.IF_EQZ,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.IPUT_BOOLEAN,
    )
    strings("menu_item_video_quality")
}

/**
 * Matches with the class found in [videoQualitySetterFingerprint].
 */
val setVideoQualityFingerprint = fingerprint {
    classFingerprint(videoQualitySetterFingerprint)
    returns("V")
    parameters("L")
    opcodes(
        Opcode.IGET_OBJECT,
        Opcode.IPUT_OBJECT,
        Opcode.IGET_OBJECT,
    )
}

val onItemClickListenerClassReference =
    findFieldDirect { setVideoQualityFingerprint().usingFields[0].field }
val setQualityFieldReference = findFieldDirect { setVideoQualityFingerprint().usingFields[1].field }
val setQualityMenuIndexMethod = findMethodDirect {
    setVideoQualityFingerprint().usingFields[1].field.type.findMethod {
        matcher { addParamType { descriptor = VideoQualityClass().descriptor } }
    }.single()
}
