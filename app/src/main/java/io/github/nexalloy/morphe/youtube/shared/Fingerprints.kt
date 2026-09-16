package io.github.nexalloy.morphe.youtube.shared

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.InstructionLocation.MatchAfterImmediately
import io.github.nexalloy.morphe.InstructionLocation.MatchAfterWithin
import io.github.nexalloy.morphe.InstructionLocation.MatchFirst
import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.fieldAccess
import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.fingerprint
import io.github.nexalloy.morphe.literal
import io.github.nexalloy.morphe.methodCall
import io.github.nexalloy.morphe.opcode
import io.github.nexalloy.morphe.string
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

internal const val CLIENT_INFO_CLASS =
    $$"Lcom/google/protos/youtube/api/innertube/InnertubeContext$ClientInfo;"

internal const val YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE =
    "Lcom/google/android/apps/youtube/app/watchwhile/MainActivity;"


internal object BuildClientContextBodyConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    filters = listOf(
        string("Android Wear"),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("Landroid/content/Context;"),
            returnType = "Z"
        ),
        opcode(Opcode.IF_EQZ),
        string("Android Automotive", location = MatchAfterImmediately()),
        string("Android"),
        fieldAccess(opcode = Opcode.IPUT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object EngagementPanelControllerFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("L", "L", "Z", "Z"),
    filters = listOf(
        string("EngagementPanelController: cannot show EngagementPanel before EngagementPanelController.init() has been called."),
        methodCall(smali = "Lj$/util/Optional;->orElse(Ljava/lang/Object;)Ljava/lang/Object;"),
        methodCall(smali = "Lj$/util/Optional;->orElse(Ljava/lang/Object;)Ljava/lang/Object;"),
        opcode(opcode = Opcode.CHECK_CAST, location = MatchAfterWithin(4)),
        opcode(opcode = Opcode.IF_EQZ, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        literal(45615449L),
        methodCall(smali = "Ljava/util/ArrayDeque;->iterator()Ljava/util/Iterator;"),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;",
            location = MatchAfterWithin(10)
        )
    )
)


val conversionContextFingerprintToString = fingerprint {
    parameters()
    strings("ConversionContext{")
}

val mainActivityConstructorFingerprint = fingerprint {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
    parameters()
    classMatcher {
        className(".MainActivity", StringMatchType.EndsWith)
    }
}

val mainActivityClass = findClassDirect {
    mainActivityConstructorFingerprint().declaredClass!!
}

val mainActivityOnBackPressedFingerprint = fingerprint {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returns("V")
    parameters()
    methodMatcher { name = "onBackPressed" }
    classMatcher { className(".MainActivity", StringMatchType.EndsWith) }
}

internal object YouTubeActivityOnCreateFingerprint : Fingerprint(
    definingClass = YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE,
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
)

val seekbarFingerprint = fingerprint {
    returns("V")
    strings("timed_markers_width")
}

internal object PlaybackSpeedOnItemClickParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    parameters = listOf("L", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(name = "getSupportFragmentManager", location = MatchFirst()),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        methodCall(
            returnType = "L",
            parameters = listOf("Ljava/lang/String;"),
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
        opcode(Opcode.CHECK_CAST, location = MatchAfterImmediately()),
    ),
    custom = {
        declaredClass {
            methodCount(8)
        }
    }
)

internal object SpeedLimiterParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        string("Playback rate: %f"),
        literal(0.25f),
        literal(4.0f),
    )
)

internal object SpeedLimiterFingerprint : Fingerprint(
    classFingerprint = SpeedLimiterParentFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        literal(0.25f),
        literal(4.0f),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("F"),
            returnType = "V"
        )
    ),
//    custom = { method, _ ->
//        method.parameterTypes.firstOrNull() == "F"
//    }
)

val videoQualityChangedFingerprint = fingerprint {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    opcodes(
        Opcode.IGET,
        Opcode.CONST_4,
        Opcode.IF_NE,
        Opcode.NEW_INSTANCE,
        Opcode.IGET_OBJECT,
    )
    methodMatcher {
        addUsingNumber(2)
        addUsingField {
            field {
                // VIDEO_QUALITY_SETTING_UNKNOWN Enum
                declaredClass { usingStrings("VIDEO_QUALITY_SETTING_UNKNOWN") }
                modifiers = Modifier.STATIC
                name = "a"
            }
        }
    }
}

val VideoQualityClass = findClassDirect {
    videoQualityChangedFingerprint().invokes.single {
        it.name == "<init>"
    }.declaredClass!!
}

val VideoQualityReceiver = findMethodDirect {
    val videoQualityClassName = VideoQualityClass().name
    videoQualityChangedFingerprint().invokes.single { it.paramCount == 1 && it.paramTypeNames[0] == videoQualityClassName }
}

internal object WatchNextResponseParserFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Ljava/lang/Object;"),
    returnType = "Ljava/util/List;",
    filters = listOf(
        literal(49399797L),
        opcode(Opcode.SGET_OBJECT),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            location = MatchAfterImmediately()
        ),
        literal(51779735L),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/Object;",
            location = MatchAfterWithin(5)
        ),
        opcode(
            Opcode.CHECK_CAST,
            location = MatchAfterImmediately()
        ),
        literal(46659098L),
    )
)

internal object InitializePlaybackSpeedValuesFingerprint : Fingerprint(
    parameters = listOf("[L", "I"),
    filters = listOf(
        string("menu_item_playback_speed"),
    )
)
