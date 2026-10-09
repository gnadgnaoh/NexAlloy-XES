package io.github.nexalloy.morphe.youtube.layout.flyout

import io.github.nexalloy.RequireAppVersion
import io.github.nexalloy.SkipTest
import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.InstructionLocation.MatchAfterImmediately
import io.github.nexalloy.morphe.InstructionLocation.MatchAfterWithin
import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.ResourceType
import io.github.nexalloy.morphe.fieldAccess
import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.literal
import io.github.nexalloy.morphe.methodCall
import io.github.nexalloy.morphe.newInstance
import io.github.nexalloy.morphe.opcode
import io.github.nexalloy.morphe.resourceLiteral
import io.github.nexalloy.morphe.string

val ProtocolBufferField = findFieldDirect {
    InteractiveStickerRendererGetEditViewFingerprint.instructionMatches.last().instruction.fieldRef!!
}

val ProtocolBufferFieldClass = findClassDirect {
    ProtocolBufferField().declaredClass
}

val FlyoutMenuVideoIdClass = findClassDirect {
    FlyoutMenuItemMessageFingerprint.instructionMatches[1].instruction.classRef!!
}

val FlyoutMenuVideoIdField = findFieldDirect {
    val messageType = FlyoutMenuVideoIdClass()

    // videoId is the only string field in the class initialized to an empty string.
    Fingerprint(
        definingClass = messageType.descriptor,
        name = "<init>",
        filters = listOf(
            string(""),
            fieldAccess(
                opcode = Opcode.IPUT_OBJECT,
                definingClass = "this",
                type = "Ljava/lang/String;",
                location = MatchAfterWithin(2)
            )
        )
    ).instructionMatches.last().instruction.fieldRef!!
}

internal object FeedBottomSheetFlyoutFingerprint : Fingerprint(
    classFingerprint = Fingerprint(
        parameters = listOf("Landroid/os/Bundle;"),
        filters = listOf(
            string("BaseBottomSheetDialogFragment.peekHeightEnabled"),
            string("BaseBottomSheetDialogFragment.largeFormWidthDp"),
        )
    ),
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Landroid/app/Dialog;",
    parameters = listOf("Landroid/os/Bundle;")
)

val FeedPopupWindowFlyout = findMethodListDirect {
    FeedPopupWindowFlyoutFingerprint.matchAll().map { it.method }
}

@SkipTest
internal object FeedPopupWindowFlyoutFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = $$"Landroid/widget/PopupWindow;->setOnDismissListener(Landroid/widget/PopupWindow$OnDismissListener;)V",
        )
    )
)

internal object FeedFlyoutBufferObjectFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    strings = listOf(
        "com.google.android.libraries.youtube.rendering.elements.sender_view",
        "com.google.android.libraries.youtube.innertube.endpoint.tag",
        "com.google.android.libraries.youtube.innertube.bundle",
        "com.google.android.libraries.youtube.logging.interaction_logger"
    )
)

internal object OnClickLithoButtonBufferObjectFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        opcode(opcode = Opcode.NEW_INSTANCE),
        opcode(opcode = Opcode.INVOKE_DIRECT, location = MatchAfterImmediately()),
        newInstance(type = "Ljava/util/HashMap;", location = MatchAfterWithin(5)),
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            smali = "Ljava/util/HashMap;-><init>(Ljava/util/Map;)V",
            location = MatchAfterWithin(5)
        ),
        string("command_status_callback", location = MatchAfterImmediately())
    )
)

internal object FullHistoryFlyoutBufferObjectFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "innertube_menu_anchor_model"),
        resourceLiteral(ResourceType.ID, "innertube_menu_anchor_tag"),
        opcode(Opcode.MOVE_RESULT_OBJECT),
        resourceLiteral(ResourceType.ID, "innertube_menu_anchor_interaction_logger"),
    ),
    custom = {
        name("onClick")
    }
)

val getSubMessageReference = findMethodDirect {
    FeedFlyoutButtonsInitializerFingerprint.instructionMatches.first().instruction.methodRef!!
}

val enumIntField =findFieldDirect {
    FeedFlyoutButtonsInitializerFingerprint.instructionMatches[6].instruction.fieldRef!!
}

val enumMethodCall = findMethodDirect {
    FeedFlyoutButtonsInitializerFingerprint.instructionMatches[7].instruction.methodRef!!
}

internal object FeedFlyoutButtonsInitializerFingerprint : Fingerprint(
    parameters = listOf("L"),
    filters = listOf(
        opcode(Opcode.INVOKE_STATIC),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "Ljava/lang/CharSequence;",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.IF_NEZ),
        opcode(Opcode.AND_INT_2ADDR, location = MatchAfterWithin(5)),
        fieldAccess(opcode = Opcode.IGET, type = "I", location = MatchAfterWithin(7)),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("I"),
            location = MatchAfterWithin(3)
        ),
        methodCall(opcode = Opcode.INVOKE_DIRECT, name = "<init>"),
        fieldAccess(opcode = Opcode.IPUT_OBJECT, type = "Ljava/lang/Runnable;"),
    ),
    strings = listOf(
        "ElementTransformer cannot be null",
        "Text missing for BottomSheetMenuItem.",
        "Text missing for BottomSheetMenuItem with iconType: ",
    )
)

@SkipTest
@RequireAppVersion(maxVersion = "21.05.000")
internal object FeedFlyoutButtonsInitializerOnItemClickFingerprint : Fingerprint(
    classFingerprint = FeedFlyoutButtonsInitializerFingerprint,
    name = "onItemClick"
)

internal object InteractiveStickerRendererGetEditViewFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf(),
    filters = listOf(
        string("getEditView called without setting interactiveStickerRenderer"),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "[B"
        ) // The only byte array accessed in the method.
    )
)

internal object FlyoutMenuItemMessageFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("Ljava/lang/String;", "Lcom/google/protobuf/MessageLite;"),
    filters = listOf(
        literal(42357),
        opcode(Opcode.INSTANCE_OF, location = MatchAfterWithin(10)),
        string("downloads_page_downloads_item_section_identifier")
    )
)

@SkipTest
internal object SingularGeneratedExtensionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.CONSTRUCTOR, AccessFlags.STATIC),
    filters = listOf(
        methodCall(name = "registerDefaultInstance"),
        fieldAccess(opcode = Opcode.SGET_OBJECT, type = "L", location = MatchAfterWithin(2)),
        string(""),
        literal(125983101),
        methodCall(name = "newSingularGeneratedExtension")
    )
)

