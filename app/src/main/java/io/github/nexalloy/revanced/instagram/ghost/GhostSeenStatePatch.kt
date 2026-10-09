package io.github.nexalloy.revanced.instagram.ghost

import android.content.Context
import android.content.SharedPreferences
import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

val GhostSeenState = patch(
    name = "Ghost seen state",
    description = "Blocks DM read receipts from being sent, while chats you open are still marked as read on this device.",
) {
    val keepLocally = runCatching {
        SeenModel.init(::seenMarkerClassFingerprint.clazz)
        LocalSeenStore.init(appContext)
    }.onFailure { Logger.printException({ "Ghost: local seen store unavailable, chats may turn unread after a refresh" }, it) }
        .isSuccess

    val senderHooked = runCatching {
        ::seenMarkerSendFingerprint.hookMethod {
            before { param -> blockSeenSend(param, keepLocally) }
        }
    }.onFailure { Logger.printException({ "Ghost: seen sender not found, blocking mark_thread_seen instead" }, it) }
        .isSuccess

    if (!senderHooked) {
        ::seenStateFingerprint.hookMethod {
            before { param ->
                Logger.printDebug { "Ghost: DM seen state blocked" }
                param.result = null
            }
        }
        return@patch
    }

    runCatching {
        val recompute = ::dmBadgeRecomputeFingerprint.method
        val badgeClass = recompute.declaringClass
        DmBadge.init(badgeClass)
        recompute.hookMethod {
            before { param ->
                DmBadge.capture(param.thisObject)
                DmBadge.beforeRecompute(param.thisObject, param.args.getOrNull(0) as? String)
            }
        }
        badgeClass.declaredConstructors.forEach { ctor ->
            ctor.hookMethod { after { param -> DmBadge.capture(param.thisObject) } }
        }
        badgeClass.declaredMethods
            .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) && it != recompute }
            .forEach { m -> m.hookMethod { before { param -> DmBadge.capture(param.thisObject) } } }
    }.onFailure { Logger.printException({ "Ghost: DM badge cache not found, the badge clears on the next server event" }, it) }

    // Keep the DM tab badge hidden when the count drops to 0 during its animation (see BadgeDot).
    runCatching {
        ::badgeDotFinalizerFingerprint.hookMethod {
            before { param -> BadgeDot.before(param) }
            after { param -> BadgeDot.after(param) }
        }
    }.onFailure { Logger.printException({ "Ghost: badge dot finalizer not found, a dot may stay until refresh" }, it) }

    if (!keepLocally) return@patch

    runCatching {
        ::threadSeenMarkerGetterFingerprint.hookMethod {
            after { param -> keepNewerLocalMarker(param) }
        }
    }.onFailure { Logger.printException({ "Ghost: seen getter hook failed, chats may turn unread after a refresh" }, it) }
}

private fun blockSeenSend(param: MethodHookParam, keepLocally: Boolean) {
    val mutation = param.args.getOrNull(2)
    val threadId = mutation?.let(SeenModel::threadIdOf)

    if (threadId != null && GhostSeenBypass.consume(threadId)) {
        Logger.printDebug { "Ghost: DM seen sent on request (thread $threadId)" }
        return
    }

    param.result = null

    val session: Any? = runCatching { SeenModel.sessionOf(param.thisObject) }.getOrNull()
    val viewerId: String? = runCatching { session?.let(SeenModel::userIdOf) }.getOrNull()
    if (keepLocally && mutation != null && threadId != null && viewerId != null) {
        runCatching {
            val marker = SeenModel.markerOf(mutation) ?: return@runCatching
            val itemId = SeenModel.itemIdOf(marker) ?: return@runCatching
            LocalSeenStore.put(viewerId, threadId, itemId, SeenModel.timestampOf(marker))
        }.onFailure { Logger.printException({ "Ghost: could not remember seen marker" }, it) }
    }

    val callback = param.args.getOrNull(1)
    if (callback != null) SeenModel.reportSuccessLater(param.method as Method, callback)

    if (session != null && viewerId != null) {
        DmBadge.noteBlocked(viewerId)
        DmBadge.refreshLater(viewerId, session)
    }

    Logger.printDebug { "Ghost: DM seen blocked, marked read locally (thread $threadId)" }
}

private fun keepNewerLocalMarker(param: MethodHookParam) {
    val userId = param.args.getOrNull(0) as? String ?: return
    if (!LocalSeenStore.hasViewer(userId)) return
    val threadId = SeenModel.threadIdOf(param.thisObject ?: return) ?: return
    val seen = LocalSeenStore.get(userId, threadId) ?: return

    val current = param.result
    if (current != null && !SeenModel.isOlderThan(current, seen.itemId)) return

    SeenModel.markerFor(threadId, seen)?.let { param.result = it }
}

object GhostSeenBypass {
    private const val TTL_MS = 30_000L
    private val allowed = ConcurrentHashMap<String, Long>()

    @JvmStatic
    fun allow(threadId: String) {
        allowed[threadId] = System.currentTimeMillis() + TTL_MS
    }

    @JvmStatic
    fun consume(threadId: String): Boolean {
        if (allowed.isEmpty()) return false
        val until = allowed.remove(threadId) ?: return false
        return until >= System.currentTimeMillis()
    }
}

private object SeenModel {
    private const val DIRECT_THREAD_KEY = "com.instagram.model.direct.DirectThreadKey"
    private const val USER_SESSION = "com.instagram.common.session.UserSession"
    private const val PROBE_MICROS = 4_242_424_242L
    private val NONE = Any()

    private lateinit var markerClass: Class<*>
    private lateinit var factory: Method
    private lateinit var compareMethod: Method
    private lateinit var itemIdField: Field
    private var timestampField: Field? = null
    private var itemArg = -1

    private val threadKeyGetters = ConcurrentHashMap<Class<*>, Any>()
    private val threadKeyIdFields = ConcurrentHashMap<Class<*>, Any>()
    private val sessionFields = ConcurrentHashMap<Class<*>, Any>()
    private val markerFields = ConcurrentHashMap<Class<*>, Any>()
    private val builtMarkers = ConcurrentHashMap<String, Any>()

    @Volatile
    private var successMethod: Method? = null

    fun init(clazz: Class<*>) {
        markerClass = clazz

        factory = clazz.declaredMethods.single { m ->
            val p = m.parameterTypes
            Modifier.isStatic(m.modifiers) && m.returnType == clazz && p.size == 5 &&
                p[1] == String::class.java && p[2] == String::class.java && p[3] == String::class.java &&
                p[4] == Long::class.javaPrimitiveType
        }.apply { isAccessible = true }

        compareMethod = hierarchy(clazz).flatMap { it.declaredMethods.asSequence() }.first { m ->
            !Modifier.isStatic(m.modifiers) && !Modifier.isAbstract(m.modifiers) &&
                m.returnType == Int::class.javaPrimitiveType &&
                m.parameterTypes.contentEquals(arrayOf<Class<*>>(String::class.java))
        }.apply { isAccessible = true }

        val ids = listOf("900000000000000000000000000000001", "900000000000000000000000000000002", "900000000000000000000000000000003")
        val probe = factory.invoke(null, null, ids[0], ids[1], ids[2], PROBE_MICROS)!!
        itemArg = ids.indices.single { (compareMethod.invoke(probe, ids[it]) as Int) == 0 }
        val fields = hierarchy(clazz).flatMap { instanceFields(it).asSequence() }.toList()
        itemIdField = fields.first { it.type == String::class.java && it.get(probe) == ids[itemArg] }
        timestampField = fields.firstOrNull { it.type == Long::class.javaPrimitiveType && it.getLong(probe) == PROBE_MICROS }

        Logger.printDebug { "Ghost: seen model ready (${clazz.name}, item arg #$itemArg)" }
    }

    fun itemIdOf(marker: Any): String? = runCatching { itemIdField.get(marker) as? String }.getOrNull()

    fun timestampOf(marker: Any): Long = runCatching { timestampField?.getLong(marker) }.getOrNull() ?: 0L

    fun isOlderThan(marker: Any, itemId: String): Boolean {
        val current = itemIdOf(marker) ?: return false
        if (current == itemId) return false
        val cmp = runCatching { compareMethod.invoke(marker, itemId) as Int }.getOrElse { compareIds(current, itemId) }
        return cmp < 0
    }

    fun compareIds(a: String, b: String): Int {
        val x = a.toBigIntegerOrNull()
        val y = b.toBigIntegerOrNull()
        if (x != null && y != null) return x.compareTo(y)
        return if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
    }

    private fun String.toBigIntegerOrNull(): BigInteger? =
        if (isNotEmpty() && all { it in '0'..'9' }) BigInteger(this) else null

    fun markerFor(threadId: String, seen: LocalSeenStore.Seen): Any? {
        builtMarkers[threadId]?.let { if (itemIdOf(it) == seen.itemId) return it }
        return runCatching {
            val args = Array(3) { if (it == itemArg) seen.itemId else threadId }
            factory.invoke(null, null, args[0], args[1], args[2], seen.micros)!!
        }.onSuccess { builtMarkers[threadId] = it }
            .onFailure { Logger.printException({ "Ghost: could not build seen marker" }, it) }
            .getOrNull()
    }

    fun markerOf(mutation: Any): Any? {
        val field = cached(markerFields, mutation.javaClass) { type ->
            hierarchy(type).flatMap { instanceFields(it).asSequence() }.firstOrNull { markerClass.isAssignableFrom(it.type) }
        } as? Field ?: return null
        return field.get(mutation)
    }

    fun threadIdOf(obj: Any): String? = runCatching {
        val getter = cached(threadKeyGetters, obj.javaClass) { type ->
            hierarchy(type).flatMap { it.declaredMethods.asSequence() }.firstOrNull { m ->
                !Modifier.isStatic(m.modifiers) && m.parameterTypes.isEmpty() && m.returnType.name == DIRECT_THREAD_KEY
            }?.apply { isAccessible = true }
        } as? Method ?: return null
        val key = getter.invoke(obj) ?: return null
        val idField = cached(threadKeyIdFields, key.javaClass) { type ->
            val probeId = "nx_thread_probe"
            val probeKey = type.getDeclaredConstructor(String::class.java, List::class.java)
                .apply { isAccessible = true }.newInstance(probeId, null)
            instanceFields(type).firstOrNull { it.type == String::class.java && it.get(probeKey) == probeId }
        } as? Field ?: return null
        idField.get(key) as? String
    }.getOrNull()

    fun sessionOf(owner: Any?): Any? {
        owner ?: return null
        val field = cached(sessionFields, owner.javaClass) { type ->
            hierarchy(type).flatMap { instanceFields(it).asSequence() }.firstOrNull { it.type.name == USER_SESSION }
        } as? Field ?: return null
        return field.get(owner)
    }

    fun userIdOf(session: Any): String? = session.javaClass.getField("userId").get(session) as? String

    fun viewerIdOf(owner: Any?): String? = sessionOf(owner)?.let(::userIdOf)

    fun reportSuccessLater(sender: Method, callback: Any) {
        val method = successMethod ?: sender.parameterTypes[1].methods.single { m ->
            !Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE && m.parameterTypes.size == 2 &&
                m.parameterTypes[1] == String::class.java && !m.parameterTypes[0].isPrimitive
        }.also { successMethod = it }

        Background.executor.schedule({
            runCatching { method.invoke(callback, null, null) }
                .onFailure { Logger.printException({ "Ghost: could not complete seen mutation" }, it) }
        }, 250, TimeUnit.MILLISECONDS)
    }

    private inline fun cached(cache: ConcurrentHashMap<Class<*>, Any>, type: Class<*>, find: (Class<*>) -> Any?): Any? {
        val hit = cache[type] ?: (runCatching { find(type) }.getOrNull() ?: NONE).also { cache[type] = it }
        return hit.takeIf { it !== NONE }
    }

}

private fun hierarchy(start: Class<*>): Sequence<Class<*>> =
    generateSequence(start) { it.superclass }.takeWhile { it != Any::class.java }

private fun instanceFields(clazz: Class<*>): List<Field> =
    clazz.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }

private object Background {
    val executor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nx-ghost-seen").apply { isDaemon = true } }
    }
}

private object DmBadge {
    private const val PLUGIN = "com.instagram.notifications.badging.plugin.BadgingPluginImpl"
    private const val REASON = "thread_unread_state_changed"
    private const val DELAY_MS = 600L

    private lateinit var badgeClass: Class<*>
    private lateinit var refreshMethod: Method
    private lateinit var cacheField: Field
    private var countMethod: Method? = null

    @Volatile
    private var ready = false
    private val caches = ConcurrentHashMap<String, WeakReference<Any>>()
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val forcing = ThreadLocal<String?>()
    private val blockedAt = ConcurrentHashMap<String, Long>() // viewer -> last blocked receipt

    private const val SEEN_REASON = "thread_seen_state_changed"
    private const val SEEN_WINDOW_MS = 5_000L

    fun init(clazz: Class<*>) {
        badgeClass = clazz
        refreshMethod = clazz.declaredMethods.single { m ->
            !Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE &&
                m.parameterTypes.contentEquals(arrayOf<Class<*>>(Long::class.javaObjectType, String::class.java))
        }.apply { isAccessible = true }
        cacheField = instanceFields(clazz).single { Map::class.java.isAssignableFrom(it.type) }
        countMethod = clazz.declaredMethods.firstOrNull { m ->
            !Modifier.isStatic(m.modifiers) && m.returnType == Int::class.javaObjectType &&
                m.parameterTypes.contentEquals(arrayOf<Class<*>>(String::class.java))
        }?.apply { isAccessible = true }
        ready = true
    }

    fun capture(cache: Any?) {
        if (!ready || cache == null) return
        val userId = SeenModel.viewerIdOf(cache) ?: return
        if (caches[userId]?.get() !== cache) caches[userId] = WeakReference(cache)
    }

    fun beforeRecompute(cache: Any?, reason: String?) {
        cache ?: return
        forcing.get()?.let { clearSequence(cache, it); return }
        if (reason != SEEN_REASON) return
        val viewerId = SeenModel.viewerIdOf(cache) ?: return
        val at = blockedAt[viewerId] ?: return
        if (System.currentTimeMillis() - at > SEEN_WINDOW_MS) return
        clearSequence(cache, viewerId)
        Logger.printDebug { "Ghost: $SEEN_REASON recompute let through for $viewerId" }
    }

    fun noteBlocked(viewerId: String) {
        blockedAt[viewerId] = System.currentTimeMillis()
    }

    fun refreshLater(viewerId: String, session: Any) {
        if (!ready) return
        val sessionRef = WeakReference(session)
        val task = Background.executor.schedule({ refresh(viewerId, sessionRef.get()) }, DELAY_MS, TimeUnit.MILLISECONDS)
        pending.put(viewerId, task)?.cancel(false)
    }

    fun refresh(viewerId: String, session: Any?) {
        pending.remove(viewerId)
        val cache = caches[viewerId]?.get() ?: session?.let(::lookUp)?.also(::capture) ?: run {
            Logger.printInfo { "Ghost: DM badge cache not available for $viewerId" }
            return
        }
        runCatching {
            clearSequence(cache, viewerId)
            forcing.set(viewerId)
            try {
                refreshMethod.invoke(cache, null, REASON)
            } finally {
                forcing.remove()
            }
            val unread = countMethod?.let { runCatching { it.invoke(cache, viewerId) }.getOrNull() }
            Logger.printInfo { "Ghost: DM badge recomputed locally, unread chats: $unread" }
        }.onFailure { Logger.printException({ "Ghost: could not refresh the DM badge" }, it) }
    }

    private fun lookUp(session: Any): Any? = runCatching {
        val base = badgeClass.classLoader!!.loadClass(PLUGIN).superclass ?: return null
        val forSession = base.declaredMethods.single { m ->
            Modifier.isStatic(m.modifiers) && m.returnType == base &&
                m.parameterTypes.size == 1 && m.parameterTypes[0].isInstance(session)
        }.apply { isAccessible = true }
        val getter = base.declaredMethods.single { m ->
            !Modifier.isStatic(m.modifiers) && m.parameterTypes.isEmpty() &&
                m.returnType.isInterface && m.returnType.isAssignableFrom(badgeClass)
        }.apply { isAccessible = true }
        val plugin = forSession.invoke(null, session) ?: return null
        getter.invoke(plugin)?.takeIf { badgeClass.isInstance(it) }
    }.onFailure { Logger.printException({ "Ghost: DM badge cache lookup failed" }, it) }.getOrNull()

    private fun clearSequence(cache: Any, viewerId: String) {
        @Suppress("UNCHECKED_CAST")
        val entries = cacheField.get(cache) as? MutableMap<Any?, Any?> ?: return
        val entry = entries[viewerId] ?: return
        withoutSequence(entry)?.let { entries[viewerId] = it }
    }

    private fun withoutSequence(entry: Any): Any? {
        val type = entry.javaClass
        val fields = instanceFields(type)
        val seq = fields.singleOrNull { it.type == Long::class.javaObjectType } ?: return null
        if (seq.get(entry) == null) return null

        val copy = runCatching {
            val ctor = type.declaredConstructors.first { c ->
                c.parameterTypes.size == fields.size && c.parameterTypes.count { it == Long::class.javaObjectType } == 1
            }.apply { isAccessible = true }
            val args = ctor.parameterTypes.map { t ->
                if (t == Long::class.javaObjectType) null else fields.single { it.type == t }.get(entry)
            }
            ctor.newInstance(*args.toTypedArray())
        }.getOrNull()
        if (copy != null && seq.get(copy) == null) return copy

        seq.set(entry, null)
        return null
    }
}

private object BadgeDot {
    private const val EXTRA = "nx_badge_was_gone"
    private val textFields = ConcurrentHashMap<Class<*>, Any>() // Field or NONE
    private val NONE = Any()

    private fun badgeOf(handler: Any?): android.widget.TextView? {
        handler ?: return null
        val field = textFields.getOrPut(handler.javaClass) {
            hierarchy(handler.javaClass).flatMap { instanceFields(it).asSequence() }
                .firstOrNull { android.widget.TextView::class.java.isAssignableFrom(it.type) } ?: NONE
        } as? Field ?: return null
        return field.get(handler) as? android.widget.TextView
    }

    fun before(param: MethodHookParam) {
        if (param.args.getOrNull(0) !is Throwable) return // finished normally, not cancelled
        val badge = runCatching { badgeOf(param.thisObject) }.getOrNull() ?: return
        if (badge.visibility == android.view.View.GONE) param.setObjectExtra(EXTRA, badge)
    }

    fun after(param: MethodHookParam) {
        val badge = param.getObjectExtra(EXTRA) as? android.widget.TextView ?: return
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) hide(badge)
        else badge.post { hide(badge) }
    }

    private fun hide(badge: android.widget.TextView) {
        if (badge.visibility == android.view.View.GONE) return
        badge.visibility = android.view.View.GONE
        Logger.printDebug { "Ghost: DM tab dot left by a cancelled badge animation hidden" }
    }
}

private object LocalSeenStore {
    class Seen(val itemId: String, val micros: Long)

    private const val PREFS = "nexalloy_ghost_local_seen"
    private const val MAX_ENTRIES = 5000
    private const val TRIM_TO = 4500

    private val seen = ConcurrentHashMap<String, Seen>()
    private val viewers: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for ((key, value) in p.all) {
            val raw = value as? String ?: continue
            val sep = key.indexOf('|')
            if (sep <= 0) continue
            val itemId = raw.substringBefore('|')
            if (itemId.isEmpty()) continue
            seen[key] = Seen(itemId, raw.substringAfter('|', "").toLongOrNull() ?: 0L)
            viewers += key.substring(0, sep)
        }
        prefs = p
    }

    fun hasViewer(userId: String): Boolean = userId in viewers

    fun get(viewerId: String, threadId: String): Seen? = seen["$viewerId|$threadId"]

    @Synchronized
    fun put(viewerId: String, threadId: String, itemId: String, micros: Long) {
        val key = "$viewerId|$threadId"
        val old = seen[key]
        if (old != null && SeenModel.compareIds(old.itemId, itemId) >= 0) return

        val stamp = if (micros > 0) micros else System.currentTimeMillis() * 1000
        seen[key] = Seen(itemId, stamp)
        viewers += viewerId

        val editor = prefs?.edit() ?: return
        editor.putString(key, "$itemId|$stamp")
        if (seen.size > MAX_ENTRIES) {
            seen.entries.sortedBy { it.value.micros }.take(seen.size - TRIM_TO).forEach {
                seen.remove(it.key)
                editor.remove(it.key)
            }
        }
        editor.apply()
    }
}
