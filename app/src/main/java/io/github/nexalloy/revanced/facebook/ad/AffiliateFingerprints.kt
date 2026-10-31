package io.github.nexalloy.revanced.facebook.ad

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.revanced.facebook.download.DexStream
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

private const val AFFILIATE_EYEBROW = "AFFILIATE_EYEBROW"
private const val FLOATING_CARD_PLUGIN =
    "com.facebook.feedback.comments.plugins.indicatorpill.organicaffiliatefloatingcta.OrganicAffiliateFloatingCtaPlugin"
private const val OP_SGET_OBJECT = 0x62
private const val OP_SPUT_OBJECT = 0x69
private val OP_INVOKE_STATIC = setOf(0x71, 0x77)

val affiliateReelCardCheckFingerprint = findMethodDirect {
    val overlay = findClass { matcher { addMethod { name = "<clinit>"; usingEqStrings(AFFILIATE_EYEBROW) } } }
        .single { Modifier.isFinal(it.modifiers) && it.superClass?.name == "java.lang.Enum" }
    val clinit = DexStream.of(overlay.methods.single { it.name == "<clinit>" }) ?: error("Could not read ${overlay.name}.<clinit>")
    val named = clinit.indexOfString(AFFILIATE_EYEBROW)
    val eyebrow = clinit.items.drop(named + 1).filterIsInstance<DexStream.Item.Field>()
        .first { it.opcode == OP_SPUT_OBJECT && it.field.typeName == overlay.name }.field
    val users = findMethod { matcher { addUsingField(eyebrow.descriptor) } }
    val checks = users.flatMap { m ->
        val items = DexStream.of(m)?.items ?: return@flatMap emptyList()
        items.indices.mapNotNull { at ->
            val read = items[at] as? DexStream.Item.Field ?: return@mapNotNull null
            if (read.opcode != OP_SGET_OBJECT || read.field.descriptor != eyebrow.descriptor) return@mapNotNull null
            val add = items.getOrNull(at + 1) as? DexStream.Item.Call ?: return@mapNotNull null
            if (add.callee.name != "add") return@mapNotNull null
            (at - 1 downTo maxOf(0, at - 4)).asSequence().mapNotNull { items[it] as? DexStream.Item.Call }
                .firstOrNull()?.takeIf { it.opcode in OP_INVOKE_STATIC && it.callee.returnTypeName == "boolean" }?.callee
        }
    }.distinctBy { it.descriptor }
    checks.singleOrNull() ?: error("Expected 1 reel product card check, found ${checks.size}")
}

val affiliateFooterIdFingerprint = findMethodDirect {
    val checks = findMethod {
        matcher { name("isFooterHidden$", StringMatchType.StartsWith); returnType = "boolean" }
    }
    val lookups = checks.flatMap { m ->
        DexStream.of(m)?.calls().orEmpty().filter { it.opcode in OP_INVOKE_STATIC && it.callee.returnTypeName == "java.lang.String" }
            .map { it.callee }
    }.distinctBy { it.descriptor }
    lookups.singleOrNull() ?: error("Expected 1 footer id lookup, found ${lookups.size}")
}

val affiliateCommentCardFingerprint = findMethodDirect {
    val gates = getClassData(FLOATING_CARD_PLUGIN)?.methods.orEmpty().filter { m ->
        m.isMethod && Modifier.isStatic(m.modifiers) && m.paramTypeNames.isNotEmpty() && m.returnTypeName == "boolean"
    }
    gates.singleOrNull() ?: error("Expected 1 floating card gate, found ${gates.size}")
}

val affiliateCtaFilterFingerprint = findMethodDirect {
    run {
        val ctaEnum = findClass {
            matcher { superClass = "java.lang.Enum"; addMethod { name = "<clinit>"; usingEqStrings("SHOP_SIMILAR", AFFILIATE_EYEBROW, "STOREFRONT") } }
        }.single().name
        findMethod {
            matcher {
                returnType = "java.util.List"; paramTypes("java.util.List")
                addInvoke { declaredClass = "java.util.LinkedHashSet"; name = "<init>" }
            }
        }.filter { m ->
            getClassData(m.className)?.methods?.firstOrNull { it.name == "<clinit>" }
                ?.usingFields?.any { it.field.typeName == ctaEnum } == true
        }.single()
    }
}
