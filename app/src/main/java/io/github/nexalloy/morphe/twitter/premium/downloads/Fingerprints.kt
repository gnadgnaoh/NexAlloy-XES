package io.github.nexalloy.morphe.twitter.premium.downloads

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.findFieldDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.opcodeEnum
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData

internal const val SUBSCRIPTIONS_PACKAGE = "com.x.subscriptions."
private const val MODELS_PACKAGE = "com.x.models."
private const val URT_POST_PACKAGE = "com.x.urt.items.post."
private const val VIDEO_TAB_PACKAGE = "com.x.video.tab."

private const val BOOLEAN = "boolean"
private const val VOID = "void"

private const val TIMELINE_DOWNLOAD_STRING = "download_video_to_offline"
private const val VIDEO_TAB_DOWNLOAD_STRING = "subscriptions_watermarked_video_download_enabled"

private const val PREMIUM_BASIC = "feature/premium_basic"
private const val PREMIUM_PLUS = "feature/premium_plus"
private const val BLUE_VERIFIED = "feature/twitter_blue_verified"
private const val OFFLINE_VIDEO_FEATURE = "subscriptions_feature_offline_video"

private const val DOWNLOADABLE_LABEL = ", isDownloadable="

private fun MethodData.has(flag: AccessFlags) = (modifiers and flag.modifier) != 0

private fun DexKitBridge.methodsUsing(vararg strings: String): List<MethodData> =
    findMethod { matcher { usingStrings(strings.toList(), StringMatchType.Equals) } }

private fun <T> requireCount(label: String, expected: Int, items: List<T>, describe: (T) -> String): List<T> {
    if (items.size != expected) {
        throw Exception(
            "Expected $expected $label, found ${items.size}: " +
                items.joinToString { describe(it) }.ifEmpty { "<none>" },
        )
    }
    return items
}

private fun MethodData.isSubscriptionCheck(): Boolean =
    className.startsWith(SUBSCRIPTIONS_PACKAGE) && returnTypeName == BOOLEAN

// region Download handlers

internal val timelineDownloadHandlerFingerprint = findMethodDirect {
    val matches = methodsUsing(TIMELINE_DOWNLOAD_STRING)
        .filter { it.className.startsWith(URT_POST_PACKAGE) }
    requireCount("NewX timeline download handler", 1, matches) { it.descriptor }.single()
}

internal val videoTabDownloadHandlersFingerprint = findMethodListDirect {
    val matches = methodsUsing(VIDEO_TAB_DOWNLOAD_STRING).filter { method ->
        method.className.startsWith(VIDEO_TAB_PACKAGE) &&
            method.returnTypeName == VOID &&
            method.paramCount == 1 &&
            method.invokes.any { it.isSubscriptionCheck() } &&
            method.invokes.any {
                it.className.startsWith(MODELS_PACKAGE) &&
                    it.returnTypeName == BOOLEAN &&
                    it.paramCount == 0
            }
    }
    if (matches.isEmpty()) matches
    else requireCount("NewX video-tab download handlers", 2, matches) { it.descriptor }
}

internal val handlerSubscriptionChecksFingerprint = findMethodListDirect {
    val handlers = buildList {
        add(timelineDownloadHandlerFingerprint(this@findMethodListDirect))
        addAll(videoTabDownloadHandlersFingerprint(this@findMethodListDirect))
    }

    val callees = handlers
        .flatMap { it.invokes }
        .filter { it.isSubscriptionCheck() }
        .distinctBy { it.descriptor }

    if (callees.isEmpty()) throw Exception("No subscription checks found in NewX download handlers")

    callees.flatMap { callee -> resolveImplementations(callee) }.distinctBy { it.descriptor }
}

private fun DexKitBridge.resolveImplementations(callee: MethodData): List<MethodData> {
    if (!callee.has(AccessFlags.ABSTRACT)) return listOf(callee)

    val owner = callee.declaredClass ?: return emptyList()
    val isInterface = (owner.modifiers and AccessFlags.INTERFACE.modifier) != 0
    val implementors = findClass {
        matcher {
            if (isInterface) addInterface(callee.className) else superClass(callee.className)
        }
    }
    return implementors.flatMap { impl ->
        impl.methods.filter { m ->
            m.name == callee.name &&
                m.paramTypeNames == callee.paramTypeNames &&
                m.returnTypeName == BOOLEAN &&
                !m.has(AccessFlags.ABSTRACT)
        }
    }
}

private fun DexKitBridge.subscriptionGate(label: String, vararg strings: String, extra: (MethodData) -> Boolean) =
    methodsUsing(*strings)
        .filter { it.isSubscriptionCheck() && extra(it) }
        .let { requireCount(label, 1, it) { m -> m.descriptor }.single() }

internal val hasAnyPremiumFingerprint = findMethodDirect {
    subscriptionGate("NewX premium subscription checker", PREMIUM_BASIC, PREMIUM_PLUS, BLUE_VERIFIED) { true }
}

internal val offlinePremiumFingerprint = findMethodDirect {
    subscriptionGate("NewX offline-video premium checker", BLUE_VERIFIED, PREMIUM_PLUS) { method ->
        method.paramCount == 0 && PREMIUM_BASIC !in method.usingStrings.map { it.toString() }
    }
}

internal val offlineVideoEnabledFingerprint = findMethodDirect {
    subscriptionGate("NewX offline-video feature gate", OFFLINE_VIDEO_FEATURE) { it.paramCount == 0 }
}

private fun DexKitBridge.mediaToString(modelName: String): MethodData {
    val matches = methodsUsing("$modelName(mediaId=").filter {
        it.name == "toString" && it.paramCount == 0 && it.className.startsWith(MODELS_PACKAGE)
    }
    return requireCount("NewX $modelName toString()", 1, matches) { it.descriptor }.single()
}

private fun MethodData.ownBooleanReads(owner: String): List<FieldData> =
    instructions
        .filter { it.opcodeEnum == Opcode.IGET_BOOLEAN }
        .mapNotNull { it.fieldRef }
        .filter { it.className == owner && it.typeName == BOOLEAN }
        .distinctBy { it.descriptor }

private fun DexKitBridge.downloadableField(modelName: String): FieldData {
    val toString = mediaToString(modelName)
    val owner = toString.className
    val instructions = toString.instructions

    val labelIndex = instructions.indexOfFirst { it.string == DOWNLOADABLE_LABEL }
    if (labelIndex >= 0) {
        val nextLabel = instructions.withIndex()
            .firstOrNull { (i, ins) -> i > labelIndex && ins.string?.startsWith(", ") == true }
            ?.index ?: instructions.size
        val candidate = instructions.subList(labelIndex + 1, nextLabel).firstOrNull { ins ->
            ins.opcodeEnum == Opcode.IGET_BOOLEAN &&
                ins.fieldRef?.let { it.className == owner && it.typeName == BOOLEAN } == true
        }?.fieldRef
        if (candidate != null) return candidate
    }

    val namedAccessor = toString.declaredClass!!.methods.firstOrNull {
        it.name == "isDownloadable" && it.paramCount == 0 && it.returnTypeName == BOOLEAN
    }
    val namedReads = namedAccessor?.ownBooleanReads(owner).orEmpty()
    if (namedReads.size == 1) return namedReads.single()

    throw Exception("Unable to resolve isDownloadable field of NewX $modelName ($owner)")
}

private fun DexKitBridge.downloadableAccessors(modelName: String): List<MethodData> {
    val field = downloadableField(modelName)
    val owner = field.className
    val clazz = findClass { matcher { className(owner) } }.single()
    val accessors = clazz.methods.filter { method ->
        !method.has(AccessFlags.STATIC) &&
            !method.has(AccessFlags.ABSTRACT) &&
            method.isMethod &&
            method.paramCount == 0 &&
            method.returnTypeName == BOOLEAN &&
            method.ownBooleanReads(owner).map { it.descriptor } == listOf(field.descriptor)
    }
    if (accessors.isEmpty()) throw Exception("No isDownloadable accessor found for NewX $modelName ($owner)")
    return accessors
}

internal val videoDownloadableField = findFieldDirect { downloadableField("MediaContentVideo") }
internal val gifDownloadableField = findFieldDirect { downloadableField("MediaContentGif") }
internal val imageDownloadableField = findFieldDirect { downloadableField("MediaContentImage") }

internal val videoDownloadableAccessors = findMethodListDirect { downloadableAccessors("MediaContentVideo") }
internal val gifDownloadableAccessors = findMethodListDirect { downloadableAccessors("MediaContentGif") }
internal val imageDownloadableAccessors = findMethodListDirect { downloadableAccessors("MediaContentImage") }
