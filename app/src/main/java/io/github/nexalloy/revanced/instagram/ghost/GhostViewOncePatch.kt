package io.github.nexalloy.revanced.instagram.ghost

import android.content.Context
import android.content.SharedPreferences
import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import io.github.nexalloy.patch
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

val GhostViewOnce = patch(
    name = "Ghost view once",
    description = "Prevents view-once / view-twice seen and replay notifications from being sent, " +
        "while media you opened stays marked as seen on this device.",
) {
    ViewOnceSeenStore.init(appContext)

    ::visualSeenSendFingerprint.hookMethod { before { param -> block(param, "view-once seen", seenCount = 1) } }

    ::visualReplayedSendFingerprint.hookMethod { before { param -> block(param, "view-twice replay", seenCount = 2) } }

    ::permanentMediaSeenSendFingerprint.hookMethod { before { param -> block(param, "permanent media seen", seenCount = 1) } }

    ::directItemParserFingerprintList.dexMethodList.forEach { parser ->
        parser.hookMethod {
            after { param -> param.result?.let(VisualSeenApplier::apply) }
        }
    }
}

private fun block(param: MethodHookParam, what: String, seenCount: Int) {
    param.result = null
    runCatching { remember(param, seenCount) }
        .onFailure { Logger.printException({ "Ghost: could not remember $what" }, it) }
    param.args.getOrNull(1)?.let { reportSuccessLater(param.method as Method, it) }
    Logger.printDebug { "Ghost: $what blocked" }
}

private fun remember(param: MethodHookParam, seenCount: Int) {
    val mutation = param.args.getOrNull(2) ?: return
    val viewerId = MutationModel.viewerIdOf(param.thisObject)
    val itemId = MutationModel.itemIdOf(mutation) ?: run {
        val known = MutationModel.knownItemIdOf(mutation)
        if (known == null) {
            Logger.printInfo { "Ghost: view-once item id not found in ${mutation.javaClass.name}" }
            return
        }
        known
    }
    ViewOnceSeenStore.put(itemId, viewerId, seenCount)
    Logger.printDebug { "Ghost: view-once item $itemId kept as seen locally (count $seenCount)" }
}

@Volatile
private var successMethod: Method? = null

private val executor: ScheduledExecutorService by lazy {
    Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nx-ghost-viewonce").apply { isDaemon = true } }
}

private fun reportSuccessLater(sender: Method, callback: Any) {
    val method = successMethod ?: runCatching {
        sender.parameterTypes[1].methods.single { m ->
            !Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE && m.parameterTypes.size == 2 &&
                m.parameterTypes[1] == String::class.java && !m.parameterTypes[0].isPrimitive
        }
    }.onFailure { Logger.printException({ "Ghost: view-once callback not found" }, it) }
        .getOrNull()?.also { successMethod = it } ?: return

    executor.schedule({
        runCatching { method.invoke(callback, null, null) }
            .onFailure { Logger.printException({ "Ghost: could not complete view-once mutation" }, it) }
    }, 250, TimeUnit.MILLISECONDS)
}

private val NONE = Any()

private fun hierarchy(start: Class<*>): Sequence<Class<*>> =
    generateSequence(start) { it.superclass }.takeWhile { it != Any::class.java }

private fun instanceFields(clazz: Class<*>): List<Field> =
    hierarchy(clazz).flatMap { it.declaredFields.asSequence() }
        .filter { !Modifier.isStatic(it.modifiers) }
        .onEach { it.isAccessible = true }
        .toList()

private object MutationModel {
    private const val USER_SESSION = "com.instagram.common.session.UserSession"

    private class Payload(val field: Field, val compare: Method, val strings: List<Field>)

    private val payloads = ConcurrentHashMap<Class<*>, Any>()
    private val sessionFields = ConcurrentHashMap<Class<*>, Any>()

    fun itemIdOf(mutation: Any): String? {
        val payload = payloads.getOrPut(mutation.javaClass) { findPayload(mutation) ?: NONE } as? Payload ?: return null
        val value = payload.field.get(mutation) ?: return null
        return payload.strings.firstNotNullOfOrNull { f ->
            val s = f.get(value) as? String ?: return@firstNotNullOfOrNull null
            val cmp = runCatching { payload.compare.invoke(value, s) as Int }.getOrNull()
            s.takeIf { cmp == 0 }
        }
    }

    fun knownItemIdOf(mutation: Any): String? =
        instanceFields(mutation.javaClass).asSequence()
            .filter { it.type == String::class.java }
            .mapNotNull { it.get(mutation) as? String }
            .firstOrNull { ViewOnceSeenStore.get(it) != null }

    private fun findPayload(mutation: Any): Payload? {
        for (field in instanceFields(mutation.javaClass)) {
            val type = field.type
            if (type.isPrimitive || type.name.startsWith("java.") || type.isEnum) continue
            val value = field.get(mutation) ?: continue
            val strings = instanceFields(value.javaClass).filter { it.type == String::class.java }
            if (strings.size < 3) continue
            val compare = hierarchy(value.javaClass).flatMap { it.declaredMethods.asSequence() }.firstOrNull { m ->
                !Modifier.isStatic(m.modifiers) && !Modifier.isAbstract(m.modifiers) &&
                    m.returnType == Int::class.javaPrimitiveType &&
                    m.parameterTypes.contentEquals(arrayOf<Class<*>>(String::class.java))
            }?.apply { isAccessible = true } ?: continue
            return Payload(field, compare, strings)
        }
        return null
    }

    fun viewerIdOf(sender: Any?): String? = runCatching {
        sender ?: return null
        val field = sessionFields.getOrPut(sender.javaClass) {
            instanceFields(sender.javaClass).firstOrNull { it.type.name == USER_SESSION } ?: NONE
        } as? Field ?: return null
        val session = field.get(sender) ?: return null
        session.javaClass.getField("userId").get(session) as? String
    }.getOrNull()
}

private object VisualSeenApplier {
    private const val MEDIA = "com.instagram.feed.media.Media"

    private class VisualModel(val seenCount: Field, val seenUsers: Field)
    private class ItemModel(val visualFields: List<Pair<Field, VisualModel>>, val stringFields: List<Field>)

    private val itemModels = ConcurrentHashMap<Class<*>, Any>()
    private val visualModels = ConcurrentHashMap<Class<*>, Any>()

    fun apply(item: Any) {
        if (ViewOnceSeenStore.isEmpty()) return
        runCatching {
            val model = itemModels.getOrPut(item.javaClass) { itemModelOf(item.javaClass) ?: NONE } as? ItemModel ?: return
            val visuals = model.visualFields.mapNotNull { (f, vm) -> f.get(item)?.let { it to vm } }
            if (visuals.isEmpty()) return

            val seen = model.stringFields.firstNotNullOfOrNull { f ->
                (f.get(item) as? String)?.let(ViewOnceSeenStore::get)
            } ?: return

            for ((visual, vm) in visuals) markSeen(visual, vm, seen)
        }.onFailure { Logger.printException({ "Ghost: could not apply local view-once seen state" }, it) }
    }

    private fun markSeen(visual: Any, vm: VisualModel, seen: ViewOnceSeenStore.Seen) {
        val count = vm.seenCount.getInt(visual)
        if (count < seen.count) vm.seenCount.setInt(visual, seen.count)

        val viewer = seen.viewerId ?: return
        val users = vm.seenUsers.get(visual) as? Set<*>
        if (users == null || viewer !in users) {
            val updated = LinkedHashSet<Any?>(users ?: emptySet<Any?>())
            updated += viewer
            vm.seenUsers.set(visual, updated)
        }
    }

    private fun itemModelOf(type: Class<*>): ItemModel? {
        val fields = instanceFields(type)
        val visuals = fields.mapNotNull { f -> visualModelOf(f.type)?.let { f to it } }
        if (visuals.isEmpty()) return null
        return ItemModel(visuals, fields.filter { it.type == String::class.java })
    }

    private fun visualModelOf(type: Class<*>): VisualModel? {
        if (type.isPrimitive || type.isArray || type.isInterface || type.name.startsWith("java.")) return null
        return visualModels.getOrPut(type) {
            val fields = type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
            val ints = fields.filter { it.type == Int::class.javaPrimitiveType }
            val sets = fields.filter { it.type == Set::class.java }
            val hasMedia = fields.any { it.type.name == MEDIA }
            if (ints.size == 1 && sets.size == 1 && hasMedia) {
                VisualModel(ints[0].apply { isAccessible = true }, sets[0].apply { isAccessible = true })
            } else NONE
        } as? VisualModel
    }
}

private object ViewOnceSeenStore {
    class Seen(val viewerId: String?, val count: Int, val at: Long)

    private const val PREFS = "nexalloy_ghost_viewonce_seen"
    private const val MAX_ENTRIES = 3000
    private const val TRIM_TO = 2500

    private val seen = ConcurrentHashMap<String, Seen>()

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for ((itemId, value) in p.all) {
            val parts = (value as? String)?.split('|') ?: continue
            if (parts.size < 3) continue
            seen[itemId] = Seen(parts[0].ifEmpty { null }, parts[1].toIntOrNull() ?: 1, parts[2].toLongOrNull() ?: 0L)
        }
        prefs = p
    }

    fun isEmpty(): Boolean = seen.isEmpty()

    fun get(itemId: String): Seen? = seen[itemId]

    @Synchronized
    fun put(itemId: String, viewerId: String?, count: Int) {
        val old = seen[itemId]
        val entry = Seen(viewerId ?: old?.viewerId, maxOf(count, old?.count ?: 0), System.currentTimeMillis())
        seen[itemId] = entry

        val editor = prefs?.edit() ?: return
        editor.putString(itemId, "${entry.viewerId.orEmpty()}|${entry.count}|${entry.at}")
        if (seen.size > MAX_ENTRIES) {
            seen.entries.sortedBy { it.value.at }.take(seen.size - TRIM_TO).forEach {
                seen.remove(it.key)
                editor.remove(it.key)
            }
        }
        editor.apply()
    }
}
