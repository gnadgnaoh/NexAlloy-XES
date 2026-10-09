package io.github.nexalloy.morphe.youtube.layout.flyout

import android.app.Dialog
import android.widget.PopupWindow
import android.widget.TextView
import app.morphe.extension.youtube.patches.components.ChannelPageFlyoutFilter
import app.morphe.extension.youtube.patches.utils.FlyoutUtils
import io.github.nexalloy.morphe.shared.misc.litho.filter.addLithoFilter
import io.github.nexalloy.morphe.shared.misc.proto.hookElement
import io.github.nexalloy.morphe.youtube.layout.captions.StartVideoInformerFingerprint
import io.github.nexalloy.morphe.youtube.layout.hide.general.ContextualMenuItemBuilderFingerprint
import io.github.nexalloy.morphe.youtube.layout.hide.general.contextualMenuItemTextFingerprint
import io.github.nexalloy.morphe.youtube.layout.hide.general.contextualMenuItemTextViewField
import io.github.nexalloy.morphe.youtube.misc.litho.filter.LithoFilter
import io.github.nexalloy.morphe.youtube.misc.proto.elementProtoParserHookPatch
import io.github.nexalloy.morphe.youtube.video.information.VideoInformationPatch
import io.github.nexalloy.patch
import io.github.nexalloy.scopedHook
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Field

lateinit var protocolBufferField: Field
lateinit var protocolBufferFieldClass: Class<*>
lateinit var flyoutMenuVideoIdField: Field
lateinit var flyoutMenuVideoIdClass: Class<*>

/* unused */
val flyoutPatch = patch(
    description = "Provides shared flyout menu hooks.",
) {
    dependsOn(
        LithoFilter,
        VideoInformationPatch,
        elementProtoParserHookPatch,
    )

    protocolBufferField = ::ProtocolBufferField.field
    protocolBufferFieldClass = ::ProtocolBufferFieldClass.clazz
    flyoutMenuVideoIdField = ::FlyoutMenuVideoIdField.field
    flyoutMenuVideoIdClass = ::FlyoutMenuVideoIdClass.clazz

    FeedFlyoutBufferObjectFingerprint.hookMethod {
        before {
            val map = it.args[1] as Map<*, *>
            FlyoutUtils.extractFlyoutIdFromMap(wrapMap(map))
        }
    }

    FeedBottomSheetFlyoutFingerprint.hookMethod {
        after {
            FlyoutUtils.setBottomSheetFlyout(it.result as? Dialog)
        }
    }

    ::FeedPopupWindowFlyout.dexMethodList.forEach {
        it.hookMethod(
            scopedHook(
                DexMethod($$"Landroid/widget/PopupWindow;->setOnDismissListener(Landroid/widget/PopupWindow$OnDismissListener;)V").toMethod(),
                {
                    before {
                        FlyoutUtils.setPopupWindowFlyout(it.thisObject as PopupWindow)
                    }
                })
        )
    }

    StartVideoInformerFingerprint.hookMethod {
        before {
            FlyoutUtils.resetVideoMarkedAsForKids()
        }
    }

    hookElement(FlyoutUtils::onCommentsLoaded)
    addLithoFilter(ChannelPageFlyoutFilter())

    // Track and initialize flyout menu buttons generically.
    val getSubMessageMethod = ::getSubMessageReference.method
    val enumIntField = ::enumIntField.field
    val enumMethodCall = ::enumMethodCall.method

    FeedFlyoutButtonsInitializerFingerprint.hookMethod(scopedHook(::getSubMessageReference.method) {
        after {
            val enumValue = enumMethodCall(null, enumIntField.get(it.result))
            FlyoutUtils.setCurrentButtonInfo(enumValue as Enum<*>, it.result)
        }
    })

    ContextualMenuItemBuilderFingerprint.hookMethod(scopedHook(::contextualMenuItemTextFingerprint.member) {
        val textViewField = ::contextualMenuItemTextViewField.field
        after {
            val textView = textViewField.get(outerParam.thisObject) as TextView?

            val messageLite = it.args[0]
            val enumValue = enumMethodCall(null, enumIntField.get(getSubMessageMethod(messageLite)))
            FlyoutUtils.setCurrentButtonInfo(enumValue as Enum<*>, textView)
        }
    })
}

private fun wrapMap(map: Map<*, *>): Map<*, *> {
    val newMap = mutableMapOf<Any?, Any?>()
    val tag = "com.google.android.libraries.youtube.innertube.endpoint.tag"
    val sender_view = "com.google.android.libraries.youtube.rendering.elements.sender_view"
    newMap[tag] = wrap(map[tag])
    newMap[sender_view] = map[sender_view]
    return newMap
}

private fun wrap(obj: Any?): Any? {
    if (obj == null) return null

    return if (flyoutMenuVideoIdClass.isAssignableFrom(obj::class.java)) {
        FlyoutUtils.FlyoutMenuVideoIdInterface { flyoutMenuVideoIdField.get(obj) as String? }
    } else if (protocolBufferFieldClass.isAssignableFrom(obj::class.java)) {
        FlyoutUtils.ProtocolBufferFieldInterface { protocolBufferField.get(obj) as ByteArray? }
    } else {
        obj
    }
}
