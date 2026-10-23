package io.github.nexalloy.revanced.facebook.privacy

import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

private fun MethodData.isStringTable(): Boolean =
    Modifier.isStatic(modifiers) && returnTypeName == "java.lang.String" &&
        paramTypeNames == listOf("int")

private fun MethodData.isHookable(): Boolean =
    isMethod && !Modifier.isAbstract(modifiers) && !isStringTable()

val storySeenMutationSenderFingerprint = findMethodListDirect {
    val builders = findMethod {
        matcher { usingEqStrings("story_ids_list", "is_story_peek_view", "surface=story_viewer") }
    }.filter { it.isMethod && !it.isStringTable() }
    val classes = builders.map { it.className }.distinct()
    if (classes.size != 1) return@findMethodListDirect emptyList()

    val cls = classes.single()
    val builderDescriptors = builders.map { it.descriptor }.toSet()
    getClassData(cls)?.methods.orEmpty().filter { m ->
        m.isHookable() && m.descriptor !in builderDescriptors &&
            !Modifier.isStatic(m.modifiers) && m.returnTypeName == "void" &&
            "java.util.Set" in m.paramTypeNames &&
            runCatching { m.invokes.any { it.descriptor in builderDescriptors } }.getOrDefault(false)
    }.distinctBy { it.descriptor }
}

val storySeenHelperFlushFingerprint = findMethodListDirect {
    findMethod {
        matcher { usingEqStrings("on_detach", "top_connections_pog", "TOP_OF_FEED_TRAY") }
    }.filter { m ->
        m.isHookable() && !Modifier.isStatic(m.modifiers) &&
            m.returnTypeName == "void" && m.paramTypeNames == listOf("java.lang.String")
    }.distinctBy { it.descriptor }
        .let { if (it.size == 1) it else emptyList() }
}

val storyViewerNonCriticalSetupFingerprint = findMethodListDirect {
    findMethod { matcher { usingStrings("StoryviewerFragment.initializeNonCriticalControllers") } }
        .filter { it.isHookable() }
        .distinctBy { it.descriptor }
}

val xAnalyticsForegroundUploadFingerprint = findMethodListDirect {
    val handler = getClassData("com.facebook.xanalytics.provider.NativeXAnalyticsAppJobHandler") ?: return@findMethodListDirect emptyList()
    val created = handler.methods.flatMap { it.invokes }.filter { it.isConstructor }.map { it.className }.toSet()
    created.mapNotNull { getClassData(it) }
        .flatMap { cls -> cls.methods.filter { it.name == "run" && it.paramTypeNames.isEmpty() } }
        .filter { run -> run.invokes.any { it.className == "com.facebook.xanalytics.XAnalyticsNative" && it.name == "kickOffUpload" } }
}

val typingRunnableRunsFingerprint = findMethodListDirect {
    findClass {
        matcher {
            fields {
                add { type = "java.util.concurrent.ScheduledFuture" }
                add { type = "java.lang.Runnable" }
            }
        }
    }.filter { cls ->
        cls.fields.count { it.typeName == "java.util.concurrent.ScheduledFuture" } >= 2 &&
            cls.fields.count { it.typeName == "java.lang.Runnable" } >= 2
    }.flatMap { context ->
        context.methods.flatMap { it.invokes }.filter { it.isConstructor }.map { it.className }
    }.distinct().mapNotNull { getClassData(it) }
        .filter { cls ->
            cls.interfaces.any { it.name == "java.lang.Runnable" } && cls.fields.any { it.name == "__redex_internal_original_name" }
        }
        .flatMap { cls -> cls.methods.filter { it.name == "run" && it.paramTypeNames.isEmpty() && it.returnTypeName == "void" } }
}
