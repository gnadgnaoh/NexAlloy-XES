package io.github.nexalloy.revanced.zalo.media

import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

internal const val MEDIA_ITEM_CLASS = "com.zing.zalo.data.mediapicker.model.MediaItem"
internal const val MEDIA_PICKER_VIEW_CLASS = "com.zing.zalo.ui.picker.mediapicker.MediaPickerView"
internal const val QUALITY_CHIP_CLASS = "com.zing.zalo.ui.picker.mediapicker.MediaPickerQualityChip"

internal const val QUALITY_STANDARD = "STANDARD"
internal const val QUALITY_HD = "HD"
internal const val QUALITY_ORIGINAL = "ORIGINAL"

private const val LAST_SELECTION_KEY = "LAST_SELECTION_MEDIA_QUALITY_"
private const val EXTRA_CURRENT_QUALITY = "EXTRA_CURRENT_QUALITY"
private const val EXTRA_SOURCE_START_VIEW = "EXTRA_SOURCE_START_VIEW"
private const val GOOGLE_PHOTOS_URI_PATTERN =
    "content://.*com.google.android.apps.photos.contentprovider/.*"

private fun ClassData.enumField(name: String) = "$descriptor->$name:$descriptor"

private fun MethodData.isStaticNoArgBoolean(owner: String) =
    className == owner && isMethod && Modifier.isStatic(modifiers) &&
        paramTypeNames.isEmpty() && returnTypeName == "boolean"

val mediaQualityEnumFingerprint = findClassDirect {
    findClass {
        matcher {
            superClass = "java.lang.Enum"
            usingEqStrings(QUALITY_STANDARD, QUALITY_HD, QUALITY_ORIGINAL)
            fields {
                add { name = QUALITY_STANDARD }
                add { name = QUALITY_HD }
                add { name = QUALITY_ORIGINAL }
            }
        }
    }.single()
}

private fun DexKitBridge.qualityEnum(): ClassData = mediaQualityEnumFingerprint()

val qualityLabelFingerprint = findMethodDirect {
    val quality = qualityEnum()
    findMethod {
        matcher {
            modifiers = Modifier.STATIC
            paramTypes("int")
            returnType = "java.lang.String"
            addUsingField(quality.enumField(QUALITY_ORIGINAL))
            addUsingField(quality.enumField(QUALITY_HD))
        }
    }.single()
}

val selectedMediaQualityFingerprint = findMethodDirect {
    findMethod {
        matcher {
            modifiers = Modifier.STATIC
            usingEqStrings(LAST_SELECTION_KEY)
            paramCount = 0
            returnType = "int"
        }
    }.single()
}

val originalQualityAvailableFingerprint = findMethodDirect {
    val getter = selectedMediaQualityFingerprint()
    val owner = getter.className
    getter.invokes
        .filter { it.isStaticNoArgBoolean(owner) }
        .distinctBy { it.descriptor }
        .single { candidate ->
            candidate.invokes.filter { it.isStaticNoArgBoolean(owner) }
                .distinctBy { it.descriptor }.size == 2
        }
}

val originalQualityEnabledFingerprint = findMethodDirect {
    val getter = selectedMediaQualityFingerprint()
    val available = originalQualityAvailableFingerprint()
    val owner = getter.className
    val fromGetter = getter.invokes.filter { it.isStaticNoArgBoolean(owner) }
        .map { it.descriptor }.toSet()
    available.invokes
        .filter { it.isStaticNoArgBoolean(owner) && it.descriptor in fromGetter }
        .distinctBy { it.descriptor }
        .single()
}

val originalQualityEntitledFingerprint = findMethodDirect {
    val available = originalQualityAvailableFingerprint()
    val enabled = originalQualityEnabledFingerprint()
    available.invokes
        .filter { it.isStaticNoArgBoolean(available.className) && it.descriptor != enabled.descriptor }
        .distinctBy { it.descriptor }
        .single()
}

val qualityPickerArgumentsFingerprint = findMethodDirect {
    findMethod {
        matcher {
            modifiers = Modifier.STATIC
            usingEqStrings(EXTRA_CURRENT_QUALITY, EXTRA_SOURCE_START_VIEW)
            paramTypes("int", "android.os.Bundle", "java.lang.String", "java.lang.String")
            returnType = "void"
        }
    }.single()
}

private fun DexKitBridge.pickerQualityInitialization(): MethodData {
    val quality = qualityEnum()
    return findMethod {
        matcher {
            declaredClass = MEDIA_PICKER_VIEW_CLASS
            paramTypes("boolean")
            returnType = "void"
            addUsingField(quality.enumField(QUALITY_HD))
            addUsingField(quality.enumField(QUALITY_STANDARD))
        }
    }.single()
}

val pickerQualitySetterFingerprint = findMethodDirect {
    pickerQualityInitialization().invokes
        .filter {
            it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf("int") && it.returnTypeName == "void"
        }
        .distinctBy { it.descriptor }
        .single()
}

val mediaItemApplyQualityFingerprint = findMethodDirect {
    val quality = qualityEnum()
    findMethod {
        matcher {
            declaredClass = MEDIA_ITEM_CLASS
            paramTypes("int")
            returnType = "void"
            addUsingField(quality.enumField(QUALITY_ORIGINAL))
            addUsingField(quality.enumField(QUALITY_HD))
        }
    }.single()
}

val photoSendConversionFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingEqStrings(GOOGLE_PHOTOS_URI_PATTERN)
            paramCount = 0
            returnType = "void"
            addUsingField {
                declaredClass = MEDIA_ITEM_CLASS
                type = "boolean"
            }
        }
    }.single()
}

val qualityChipSetTextFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass = QUALITY_CHIP_CLASS
            name = "setText"
            paramTypes("java.lang.CharSequence")
            returnType = "void"
        }
    }.single()
}

internal const val BIG_FILE_EXPIRED = "BIG_FILE_EXPIRED"
internal const val BIG_FILE_NOT_EXPIRED = "BIG_FILE_NOT_EXPIRED"

val chatMediaStateEnumFingerprint = findClassDirect {
    findClass {
        matcher {
            superClass = "java.lang.Enum"
            usingEqStrings(BIG_FILE_EXPIRED, BIG_FILE_NOT_EXPIRED)
            fields {
                add { name = BIG_FILE_EXPIRED }
                add { name = BIG_FILE_NOT_EXPIRED }
            }
        }
    }.single()
}

val chatMediaStateClassifierFingerprint = findMethodDirect {
    val state = chatMediaStateEnumFingerprint()
    val d = state.descriptor
    findMethod {
        matcher {
            modifiers = Modifier.STATIC
            returnType = state.name
            addUsingField("$d->$BIG_FILE_EXPIRED:$d")
            addUsingField("$d->$BIG_FILE_NOT_EXPIRED:$d")
        }
    }.single()
}
