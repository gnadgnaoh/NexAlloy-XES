package io.github.nexalloy.revanced.zalo.media

import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.zalo.ads.remoteConfigIntGetterFingerprint
import java.lang.reflect.Modifier

private const val ALLOW_SEND_ORIGINAL_KEY = "media_picker@pick_quality@allow_send_original_photo"

private val FORCED_TRUE_EXTRAS = setOf(
    "bol_extra_photo_hd",
    "bol_extra_photo_original",
)

val PreferOriginalPhotoQuality = patch(
    name = "Prefer original photo quality",
    description = "Sends photos in Zalo's existing Original quality by default, including " +
        "photos shared in from other apps, and skips " +
        "the client-side Z Cloud entitlement check. Photos taken with the in-app Zalo " +
        "camera stay HD. Server upload limits, account restrictions and video quality " +
        "are unchanged.",
) {
    val qualityEnum = ::mediaQualityEnumFingerprint.clazz
    val original = qualityValue(qualityEnum, QUALITY_ORIGINAL)
    val hd = qualityValue(qualityEnum, QUALITY_HD)
    val mediaItemClass = classLoader.loadClass(MEDIA_ITEM_CLASS)

    ::selectedMediaQualityFingerprint.hookMethod(XC_MethodReplacement.returnConstant(original))
    ::originalQualityEnabledFingerprint.hookMethod(XC_MethodReplacement.returnConstant(true))
    ::originalQualityEntitledFingerprint.hookMethod(XC_MethodReplacement.returnConstant(true))
    ::originalQualityAvailableFingerprint.hookMethod(XC_MethodReplacement.returnConstant(true))

    ::qualityPickerArgumentsFingerprint.hookMethod {
        before { param -> param.args[0] = original }
    }

    ::pickerQualitySetterFingerprint.hookMethod {
        before { param -> if (param.args[0] == hd) param.args[0] = original }
    }

    ::mediaItemApplyQualityFingerprint.hookMethod {
        before { param ->
            if (!isVideoItem(param.thisObject)) param.args[0] = original
        }
    }

    val applyQuality = ::mediaItemApplyQualityFingerprint.method
    ::photoSendConversionFingerprint.hookMethod {
        before { param ->
            val runnable = param.thisObject ?: return@before
            for (item in mediaItemsIn(runnable, mediaItemClass)) {
                if (isVideoItem(item)) continue
                runCatching { applyQuality.invoke(item, original) }
            }
        }
    }

    val qualityLabel = ::qualityLabelFingerprint.method
    ::qualityChipSetTextFingerprint.hookMethod {
        before { param ->
            val label = runCatching { qualityLabel.invoke(null, original) as? CharSequence }
                .getOrNull()
            if (!label.isNullOrEmpty()) param.args[0] = label
        }
    }

    ::remoteConfigIntGetterFingerprint.hookMethod {
        before { param ->
            if (param.args.getOrNull(0) == ALLOW_SEND_ORIGINAL_KEY) param.result = 1
        }
    }

    android.os.BaseBundle::class.java.declaredMethods
        .filter { it.name == "getBoolean" && it.parameterTypes.firstOrNull() == String::class.java }
        .forEach { method ->
            method.hookMethod {
                before { param ->
                    if (param.args[0] in FORCED_TRUE_EXTRAS) param.result = true
                }
            }
        }

    Logger.printInfo { "[Zalo] Prefer original photo quality: ORIGINAL=$original HD=$hd" }
}

private fun qualityValue(enumClass: Class<*>, name: String): Int {
    val constant = enumClass.enumConstants?.firstOrNull { (it as Enum<*>).name == name } as? Enum<*>
        ?: error("Quality enum ${enumClass.name} has no $name")
    val payload = enumClass.declaredFields.firstOrNull {
        it.type == Int::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers) && !it.isSynthetic
    }
    return payload?.run { isAccessible = true; getInt(constant) } ?: constant.ordinal
}

private fun isVideoItem(item: Any?): Boolean {
    var type: Class<*>? = item?.javaClass ?: return false
    while (type != null && type != Any::class.java) {
        if (type.simpleName.contains("Video", ignoreCase = true)) return true
        type = type.superclass
    }
    return false
}

private fun mediaItemsIn(runnable: Any, mediaItemClass: Class<*>): List<Any> =
    runnable.javaClass.declaredFields
        .asSequence()
        .filter { !Modifier.isStatic(it.modifiers) }
        .mapNotNull { field -> runCatching { field.isAccessible = true; field.get(runnable) }.getOrNull() }
        .filterIsInstance<List<*>>()
        .flatMap { list -> list.asSequence().filterNotNull().filter(mediaItemClass::isInstance) }
        .toList()
