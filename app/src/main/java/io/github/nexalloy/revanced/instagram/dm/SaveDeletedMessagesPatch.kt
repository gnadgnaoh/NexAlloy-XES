package io.github.nexalloy.revanced.instagram.dm

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch
import org.luckypray.dexkit.wrap.DexField
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// =============================================================================================
// MEDIA SETTINGS - change these values directly, then rebuild the module.
// =============================================================================================

/**
 * How long photos / videos / voice of messages that were NOT unsent stay cached, counted from
 * when the message was SENT (also the download window: older messages aren't downloaded).
 * Stored in Instagram's cache folder. Default 48.
 */
private const val PENDING_MEDIA_HOURS = 48

/**
 * Delete media of UNSENT messages this many days after they were unsent.
 * 0 = never. The message text always stays. Default 0.
 */
private const val KEPT_MEDIA_MAX_AGE_DAYS = 30

/**
 * Max storage for media of UNSENT messages, in MB (D11). Above it, media of the oldest unsent
 * messages is deleted first. 0 = unlimited. The message text always stays. Default 1024 (1 GB).
 */
private const val KEPT_MEDIA_MAX_MB = 1024

/** Safety cap for the cache of normal (not unsent) messages, in MB. 0 = unlimited. Default 1024. */
private const val PENDING_MEDIA_MAX_MB = 1024

/** Cleanup runs at app start, then every N hours or every M downloads. Defaults 3 / 20. */
private const val PRUNE_EVERY_HOURS = 3
private const val PRUNE_EVERY_DOWNLOADS = 20

/** Largest single file that will be downloaded, in MB. 0 = no limit. Default 100. */
private const val MAX_MEDIA_FILE_MB = 100

// =============================================================================================

val SaveDeletedMessages = patch(
    name = "Save deleted messages",
    description = "Keeps direct messages visible after the person who sent them unsends. " +
        "Blocks Instagram from dropping the message from the conversation and from deleting it " +
        "locally. Photos, videos and voice messages are cached when they arrive so they still " +
        "open after an unsend. Unsending your own messages still works.",
) {
    val message = MessageAccess(
        classLoader = classLoader,
        itemId = ::messageItemIdField.dexField,
        clientContext = ::messageClientContextField.dexField,
        userId = ::messageUserIdField.dexField,
        sentByViewer = ::messageSentByViewerField.dexField,
        hideInThread = ::messageHideInThreadField.dexField,
        timestamp = runCatching { ::messageTimestampField.dexField }.getOrNull(),
        mediaFields = listOfNotNull(
            runCatching { ::messageMediaField.dexField }.getOrNull(),
            runCatching { ::messageRavenMediaField.dexField }.getOrNull(),
        ),
        voiceMedia = runCatching { ::messageVoiceMediaField.dexField }.getOrNull(),
    )
    val kept = KeptMessages()

    val keptMedia = KeptMediaIds(appContext)
    val voicePlayable = runCatching { ::voicePlayableMethod.method.apply { isAccessible = true } }
        .onFailure { Logger.printException({ "SaveDeletedMessages: voice source builder unresolved" }, it) }
        .getOrNull()
    val media = UnsentMediaCache(appContext, message, keptMedia, voicePlayable)
    keptMedia.onEvict = { ids -> media.drop(ids) }

    val shadow = ShadowStore(
        appContext,
        message,
        toRow = runCatching { ::messageToRowMethod.method.apply { isAccessible = true } }
            .onFailure { Logger.printException({ "F5: row serializer unresolved" }, it) }
            .getOrNull(),
        fromBlob = runCatching { ::messageFromBlobMethod.method.apply { isAccessible = true } }
            .onFailure { Logger.printException({ "F5: row parser unresolved" }, it) }
            .getOrNull(),
        tableGetter = runCatching { ::messageTableGetterMethod.method.apply { isAccessible = true } }
            .onFailure { Logger.printException({ "F5: table getter unresolved" }, it) }
            .getOrNull(),
    )
    fun warmUp(holder: Any?) {
        if (shadow.table == null) message.sessionOf(holder)?.let(shadow::tableFrom)
    }

    val recent = RecentMessages(message)

    fun keepMedia(target: Any, vararg extraIds: String?) {
        keptMedia.add(message.itemId(target), message.clientContext(target), *extraIds)
        media.onKept(target)
        shadow.save(target) // F5 backup
    }

    val findThread = runCatching { ::threadStateLookupMethod.method.apply { isAccessible = true } }
        .onFailure { Logger.printException({ "SaveDeletedMessages: thread lookup unresolved" }, it) }
        .getOrNull()
    val findMessage = runCatching { ::threadFindMessageMethod.method.apply { isAccessible = true } }
        .onFailure { Logger.printException({ "SaveDeletedMessages: message lookup unresolved" }, it) }
        .getOrNull()

    ::threadRemoveMessageMethod.hookMethod {
        before { param ->
            if (kept.mentionedIn(param.args)) {
                param.result = null
                return@before
            }
            val store = param.thisObject
            val threadKey = param.args?.getOrNull(0)
            warmUp(store)
            val thread = if (findThread != null && findMessage != null && store != null && threadKey != null)
                runCatching { findThread.invoke(store, threadKey) }.getOrNull() else null

            if (thread != null) {
                for (arg in param.args.orEmpty()) {
                    val id = (arg as? String)?.takeIf { it.isNotEmpty() } ?: continue
                    val target = runCatching { findMessage!!.invoke(thread, id) }.getOrNull() ?: continue
                    if (!message.isFromSomeoneElse(target)) continue

                    message.unhide(target)
                    kept.protect(message.itemId(target), message.clientContext(target), id)
                    threadKey?.let { message.setThreadKeyIfMissing(target, it) }
                    keepMedia(target, id)
                    Logger.printDebug { "SaveDeletedMessages: kept unsent message $id" }
                    param.result = null
                    return@before
                }
            }

            for (arg in param.args.orEmpty()) {
                val id = (arg as? String)?.takeIf { it.isNotEmpty() } ?: continue
                val target = recent.get(id) ?: continue
                if (!message.isFromSomeoneElse(target)) continue

                message.unhide(target)
                kept.protect(message.itemId(target), message.clientContext(target), id)
                threadKey?.let { message.setThreadKeyIfMissing(target, it) }
                keepMedia(target, id)
                Logger.printDebug { "SaveDeletedMessages: kept unsent message $id (recent cache)" }
                param.result = null
                return@before
            }
        }
    }

    ::listRemoveMessageMethod.hookMethod {
        before { param ->
            val id = param.args?.getOrNull(0) as? String ?: return@before
            val messages = param.args?.getOrNull(1) as? Collection<*> ?: return@before

            val target = messages.firstOrNull { message.hasId(it, id) } ?: return@before
            if (!message.isFromSomeoneElse(target)) return@before

            message.unhide(target)
            kept.protect(message.itemId(target), message.clientContext(target), id)
            keepMedia(target, id)
            Logger.printDebug { "SaveDeletedMessages: kept unsent message $id" }
            param.result = false
        }
    }

    ::messageRowDeleteMethod.hookMethod {
        before { param ->
            if (shadow.table == null) shadow.table = param.thisObject
            if (kept.mentionedIn(param.args)) param.result = null
        }
    }

    ::messageParseMethod.hookMethod {
        after { param ->
            val parsed = param.result ?: return@after
            if (!message.isFromSomeoneElse(parsed)) return@after
            message.unhide(parsed)
            recent.put(parsed) // C6
            if (keptMedia.contains(message.itemId(parsed), message.clientContext(parsed))) {
                media.redirectKept(parsed)
            } else {
                media.prefetch(parsed)
            }
        }
    }

    runCatching {
        ::voiceLocalPathMethod.hookMethod {
            after { param ->
                if (!(param.result as? String).isNullOrEmpty()) return@after
                media.localVoicePath(param.thisObject)?.let { param.result = it }
            }
        }
    }.onFailure {
        Logger.printException({ "SaveDeletedMessages: voice hook unavailable, unsent voice messages won't play" }, it)
    }

    runCatching {
        ::messageToRowMethod.hookMethod {
            after { param ->
                shadow.table = param.thisObject
                val msg = param.args?.getOrNull(0) ?: return@after
                if (!keptMedia.contains(message.itemId(msg), message.clientContext(msg))) return@after
                shadow.saveRow(msg, param.result as? ContentValues)
            }
        }
    }.onFailure { Logger.printException({ "F5: row serializer hook failed" }, it) }

    val threadListFields: List<Field> = runCatching {
        ::threadMergeMethod.method.declaringClass.declaredFields
            .filter { java.util.List::class.java.isAssignableFrom(it.type) && !Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }
    }.getOrDefault(emptyList())

    runCatching {
        ::threadMergeMethod.hookMethod {
            after { param ->
                val args = param.args ?: return@after
                val thread = args.getOrNull(0)
                warmUp(thread)
                @Suppress("UNCHECKED_CAST")
                val cached = args.getOrNull(1) as? MutableList<Any?> ?: return@after
                val server = args.getOrNull(2) as? List<*>
                val key = cached.firstNotNullOfOrNull { message.threadKey(it) }
                    ?: server.orEmpty().firstNotNullOfOrNull { message.threadKey(it) }
                    ?: thread?.let(::findThreadKey)
                    ?: return@after
                val backups = shadow.itemIdsOfThread(key)
                if (backups.isEmpty()) return@after
                val ownList = thread != null && threadListFields.any {
                    runCatching { it.get(thread) }.getOrNull() === cached
                }
                if (!ownList) return@after
                shadow.restoreInto(cached, key) { restoredMsg ->
                    // Protect it again for the original in-memory hooks (lost on restart).
                    kept.protect(message.itemId(restoredMsg), message.clientContext(restoredMsg))
                }
            }
        }
    }.onFailure { Logger.printException({ "F5: merge hook unavailable" }, it) }


}

private class MessageAccess(
    private val classLoader: ClassLoader,
    itemId: DexField,
    clientContext: DexField,
    userId: DexField,
    sentByViewer: DexField,
    hideInThread: DexField,
    timestamp: DexField?,
    mediaFields: List<DexField>,
    voiceMedia: DexField?,
) {
    private fun resolve(ref: DexField?): Field? = ref?.let {
        runCatching {
            classLoader.loadClass(it.className)
                .getDeclaredField(it.name)
                .apply { isAccessible = true }
        }.getOrNull()
    }

    private val messageClass: Class<*> = classLoader.loadClass(itemId.className)

    private val itemIdField = resolve(itemId)
    private val clientContextField = resolve(clientContext)
    private val userIdField = resolve(userId)
    private val sentByViewerField = resolve(sentByViewer)
    private val hideInThreadField = resolve(hideInThread)

    private val timestampField = resolve(timestamp)
    private val media = mediaFields.mapNotNull(::resolve)
    private val voiceField = resolve(voiceMedia)

    fun timestampMicros(candidate: Any?): Long? =
        runCatching { timestampField?.get(candidate)?.toString()?.toLongOrNull() }.getOrNull()

    fun mediaObjects(candidate: Any?): List<Any> =
        if (!messageClass.isInstance(candidate)) emptyList()
        else media.mapNotNull { runCatching { it.get(candidate) }.getOrNull() }

    private val threadKeyField: Field? = run {
        var cls: Class<*>? = messageClass
        while (cls != null && cls != Any::class.java) {
            cls.declaredFields.firstOrNull { it.type.name == DIRECT_THREAD_KEY_CLASS }
                ?.let { return@run it.apply { isAccessible = true } }
            cls = cls.superclass
        }
        null
    }

    fun threadKey(candidate: Any?): Any? =
        if (!messageClass.isInstance(candidate)) null
        else runCatching { threadKeyField?.get(candidate) }.getOrNull()

    fun setThreadKeyIfMissing(candidate: Any, key: Any) {
        runCatching { if (threadKeyField?.get(candidate) == null) threadKeyField?.set(candidate, key) }
    }

    private val sessionFields = ConcurrentHashMap<Class<*>, Field>()
    private val noSessionField: MutableSet<Class<*>> = ConcurrentHashMap.newKeySet()

    fun sessionOf(holder: Any?): Any? {
        holder ?: return null
        if (holder.javaClass.name == USER_SESSION_CLASS) return holder
        val type = holder.javaClass
        if (type in noSessionField) return null
        val field = sessionFields[type] ?: run {
            var cls: Class<*>? = type
            var found: Field? = null
            while (found == null && cls != null && cls != Any::class.java) {
                found = cls.declaredFields.firstOrNull { it.type.name == USER_SESSION_CLASS }
                cls = cls.superclass
            }
            found?.apply { isAccessible = true }?.also { sessionFields[type] = it }
        }
        if (field == null) {
            noSessionField += type
            return null
        }
        return runCatching { field.get(holder) }.getOrNull()
    }

    fun voiceObject(candidate: Any?): Any? =
        if (!messageClass.isInstance(candidate)) null
        else runCatching { voiceField?.get(candidate) }.getOrNull()

    fun itemId(candidate: Any?): String? = readString(itemIdField, candidate)

    fun clientContext(candidate: Any?): String? = readString(clientContextField, candidate)

    fun hasId(candidate: Any?, id: String): Boolean {
        if (!messageClass.isInstance(candidate)) return false
        return itemId(candidate) == id || clientContext(candidate) == id
    }

    fun isFromSomeoneElse(candidate: Any?): Boolean {
        if (!messageClass.isInstance(candidate)) return false
        if (itemId(candidate) == null) return false
        if (runCatching { sentByViewerField?.get(candidate) as? Boolean }.getOrNull() == true) {
            rememberViewer(readString(userIdField, candidate))
            return false
        }
        val sender = readString(userIdField, candidate)
        return sender == null || sender != viewerUserId
    }

    fun unhide(candidate: Any?) {
        if (!messageClass.isInstance(candidate)) return
        runCatching { hideInThreadField?.set(candidate, false) }
    }

    private fun readString(field: Field?, target: Any?): String? =
        runCatching { (field?.get(target) as? CharSequence)?.toString()?.takeIf { it.isNotEmpty() } }
            .getOrNull()

    @Volatile
    private var viewerUserId: String? = null

    private fun rememberViewer(userId: String?) {
        if (!userId.isNullOrEmpty()) viewerUserId = userId
    }
}

private class KeptMessages {
    private val ids: MutableMap<String, Boolean> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) =
                size > 2000
        }
    )

    fun protect(vararg candidates: String?) {
        candidates.forEach { id -> if (!id.isNullOrEmpty()) ids.put(id, true) }
    }

    fun mentionedIn(args: Array<Any?>?): Boolean {
        args ?: return false
        return args.any { arg -> (arg as? String)?.let { ids.containsKey(it) } == true }
    }
}

private class RecentMessages(private val message: MessageAccess) {
    private val map: MutableMap<String, Any> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Any>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>) = size > MAX
        }
    )

    fun put(msg: Any) {
        message.itemId(msg)?.let { map[it] = msg }
        message.clientContext(msg)?.let { map[it] = msg }
    }

    fun get(id: String): Any? = map[id]

    private companion object {
        const val MAX = 600 // ~300 messages, two keys each
    }
}


private class KeptMediaIds(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("nexalloy_dm_kept_media", Context.MODE_PRIVATE)
    private val ids = ConcurrentHashMap<String, Long>()

    @Volatile
    var onEvict: ((List<String>) -> Unit)? = null

    init {
        for ((key, value) in prefs.all) ids[key] = (value as? Long) ?: 0L
    }

    fun add(vararg candidates: String?) {
        val now = System.currentTimeMillis()
        val edit = prefs.edit()
        var changed = false
        for (id in candidates) {
            if (id.isNullOrEmpty() || ids.containsKey(id)) continue
            ids[id] = now
            edit.putLong(id, now)
            changed = true
        }
        if (!changed) return
        var evicted: List<String> = emptyList()
        if (ids.size > MAX_ENTRIES) {
            evicted = ids.entries.sortedBy { it.value }.take(ids.size - TRIM_TO).map { it.key }
            evicted.forEach { ids.remove(it); edit.remove(it) }
        }
        edit.apply()
        if (evicted.isNotEmpty()) onEvict?.invoke(evicted)
    }

    fun contains(vararg candidates: String?): Boolean =
        candidates.any { !it.isNullOrEmpty() && ids.containsKey(it) }

    private companion object {
        const val MAX_ENTRIES = 5000
        const val TRIM_TO = 4500
    }
}

private class UnsentMediaCache(
    context: Context,
    private val message: MessageAccess,
    private val kept: KeptMediaIds,
    private val voicePlayable: java.lang.reflect.Method?,
) {
    private val pendingRetentionMs = TimeUnit.HOURS.toMillis(PENDING_MEDIA_HOURS.coerceAtLeast(1).toLong())
    private val keptMaxBytes = if (KEPT_MEDIA_MAX_MB <= 0) Long.MAX_VALUE else KEPT_MEDIA_MAX_MB.toLong() * 1024 * 1024

    private enum class Kind(val fileName: String) { IMAGE("image"), VIDEO("video") }

    private class UrlSlot(val holder: Any, val field: Field, val url: String, val kind: Kind)

    private val pendingRoot = File(context.cacheDir, DIR_NAME)
    private val keptRoot = File(context.filesDir, DIR_NAME)

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nexalloy-unsent-media").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
    }
    private val scheduled: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    private val voiceOwners: MutableMap<Any, String> = Collections.synchronizedMap(java.util.WeakHashMap())

    @Volatile private var lastPrune = 0L
    private var downloadsSincePrune = 0 // worker thread only

    init {
        worker.execute {
            runCatching { migrateFromOldLayout() }
            runCatching { prune() }
        }
    }

    private fun keptDir(id: String) = File(keptRoot, id)
    private fun pendingDir(id: String) = File(pendingRoot, id)

    private fun dirOf(id: String): File = keptDir(id).takeIf { it.isDirectory } ?: pendingDir(id)

    @Synchronized
    fun promote(id: String) {
        val from = pendingDir(id)
        val to = keptDir(id)
        if (!from.isDirectory) return
        to.parentFile?.mkdirs()
        val moved = if (to.exists()) {
            from.listFiles().orEmpty().forEach { f -> f.renameTo(File(to, f.name)) || run { f.copyTo(File(to, f.name), true); f.delete() } }
            from.deleteRecursively()
            true
        } else {
            from.renameTo(to) || runCatching { from.copyRecursively(to, true); from.deleteRecursively() }.isSuccess
        }
        if (moved) {
            to.setLastModified(System.currentTimeMillis()) // D10/D11 age = time it was kept
            Logger.printDebug { "SaveDeletedMessages: media of $id moved to permanent storage" }
        }
    }

    fun onKept(msg: Any) {
        val id = message.itemId(msg) ?: return
        message.voiceObject(msg)?.let { voiceOwners[it] = id } // voice needs this right away
        worker.execute {
            runCatching {
                promote(id)
                redirect(msg)
            }.onFailure { Logger.printDebug { "SaveDeletedMessages: keeping media of $id failed: $it" } }
        }
    }

    fun redirectKept(msg: Any) {
        val id = message.itemId(msg) ?: return
        if (keptDir(id).isDirectory) redirect(msg) else onKept(msg)
    }

    fun prefetch(msg: Any) {
        val id = message.itemId(msg) ?: return
        val sentMs = message.timestampMicros(msg)?.div(1000)
        if (sentMs != null && System.currentTimeMillis() - sentMs > pendingRetentionMs) return
        val voice = message.voiceObject(msg)
        if (voice != null) voiceOwners[voice] = id
        if (!scheduled.add(id)) return

        worker.execute {
            if (keptDir(id).isDirectory) return@execute // already kept and stored
            val dir = pendingDir(id)
            if (voice != null) {
                runCatching { downloadVoice(id, voice, dir) }
                    .onFailure { Logger.printDebug { "SaveDeletedMessages: voice cache failed for $id: $it" } }
            } else {
                val slots = runCatching { collect(msg) }.getOrNull().orEmpty()
                if (slots.isEmpty()) return@execute
                for ((kind, url) in pickDownloads(slots)) {
                    val out = localFile(dir, kind, url)
                    if (out.exists() && out.length() > 0) continue
                    runCatching { download(url, out) }
                        .onFailure { Logger.printDebug { "SaveDeletedMessages: media cache failed for $id: $it" } }
                }
            }
            if (dir.isDirectory) dir.setLastModified(sentMs ?: System.currentTimeMillis())

            // Unsent while we were still downloading: keep the files and patch the message.
            if (kept.contains(id)) {
                promote(id)
                redirect(msg)
            }
            downloadsSincePrune++
            maybePrune()
        }
    }

    fun redirect(msg: Any) {
        val id = message.itemId(msg) ?: return
        message.voiceObject(msg)?.let { voice ->
            voiceOwners[voice] = id
            return
        }
        val dir = dirOf(id)
        if (!dir.isDirectory) return
        val files = dir.listFiles().orEmpty()
        if (files.isEmpty()) return

        var swapped = 0
        for (slot in collect(msg)) {
            val local = files.firstOrNull { it.name.startsWith(slot.kind.fileName + ".") && it.length() > 0 }
                ?: continue
            val uri = Uri.fromFile(local).toString()
            if (runCatching { slot.field.set(slot.holder, uri) }.isSuccess) swapped++
        }
        if (swapped > 0) Logger.printDebug { "SaveDeletedMessages: $id now uses $swapped cached media link(s)" }
    }

    fun drop(itemIds: Collection<String>) {
        if (itemIds.isEmpty()) return
        worker.execute {
            itemIds.forEach { id ->
                if (id.isEmpty() || id.contains('/')) return@forEach
                keptDir(id).deleteRecursively()
                pendingDir(id).deleteRecursively()
            }
        }
    }

    fun localVoicePath(voice: Any?): String? {
        voice ?: return null
        val id = voiceOwners[voice] ?: return null
        if (!kept.contains(id)) return null
        val files = dirOf(id).listFiles().orEmpty().filter { it.length() > 0 }
        // "video.*" = audio grabbed by the generic media walk of an older build.
        return (files.firstOrNull { it.name.startsWith("voice.") }
            ?: files.firstOrNull { it.name.startsWith("video.") })
            ?.absolutePath
    }

    private fun downloadVoice(id: String, voice: Any, dir: File) {
        if (dir.listFiles().orEmpty().any { it.name.startsWith("voice.") && it.length() > 0 }) return
        val source = voicePlayable?.invoke(null, voice) ?: error("no voice source")
        val url = firstHttpString(source) ?: error("voice source has no URL")
        val ext = Uri.parse(url).path.orEmpty().substringAfterLast('.', "").lowercase()
            .takeIf { it.length in 2..4 } ?: "mp4"
        download(url, File(dir, "voice.$ext"))
        Logger.printDebug { "SaveDeletedMessages: cached voice message $id" }
    }

    private fun firstHttpString(obj: Any): String? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null && !isPlatform(cls.name)) {
            for (field in cls.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.type != String::class.java) continue
                val value = runCatching { field.isAccessible = true; field.get(obj) as? String }.getOrNull()
                if (value != null && value.startsWith("http")) return value
            }
            cls = cls.superclass
        }
        return null
    }

    private fun collect(msg: Any): List<UrlSlot> {
        val out = ArrayList<UrlSlot>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        for (media in message.mediaObjects(msg)) walk(media, 0, seen, out)
        return out
    }

    private fun walk(node: Any?, depth: Int, seen: MutableSet<Any>, out: MutableList<UrlSlot>) {
        if (node == null || depth > MAX_DEPTH || seen.size > MAX_NODES || !seen.add(node)) return
        when (node) {
            is String, is Number, is Boolean, is Char, is Enum<*> -> return
            is Collection<*> -> { node.forEach { walk(it, depth + 1, seen, out) }; return }
            is Map<*, *> -> { node.values.forEach { walk(it, depth + 1, seen, out) }; return }
            is Array<*> -> { node.forEach { walk(it, depth + 1, seen, out) }; return }
        }
        val type = node.javaClass
        if (type.isArray || isPlatform(type.name)) return
        // User objects are shared app-wide (UserCache); never touch avatars.
        if (type.name.startsWith("com.instagram.user.") || type.simpleName == "User") return

        var cls: Class<*>? = type
        while (cls != null && !isPlatform(cls.name)) {
            for (field in cls.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                val value = runCatching { field.isAccessible = true; field.get(node) }.getOrNull() ?: continue
                if (value is String) {
                    val kind = kindOf(value) ?: continue
                    out += UrlSlot(node, field, value, kind)
                } else {
                    walk(value, depth + 1, seen, out)
                }
            }
            cls = cls.superclass
        }
    }

    private fun isPlatform(name: String) =
        name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.") ||
            name.startsWith("kotlin.") || name.startsWith("dalvik.")

    private fun kindOf(url: String): Kind? {
        if (!url.startsWith("http")) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (!host.endsWith("cdninstagram.com") && !host.contains("fbcdn.net")) return null
        val path = (uri.path ?: return null).lowercase()
        if (path.contains("t51.2885-19")) return null // profile pictures
        return when {
            path.endsWith(".mp4") || path.endsWith(".m4a") || path.endsWith(".aac") -> Kind.VIDEO
            path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".webp") ||
                path.endsWith(".heic") || path.endsWith(".png") -> Kind.IMAGE
            else -> null
        }
    }

    private fun pickDownloads(slots: List<UrlSlot>): List<Pair<Kind, String>> {
        val picks = ArrayList<Pair<Kind, String>>(2)
        slots.filter { it.kind == Kind.IMAGE }.maxByOrNull { imageArea(it.url) }
            ?.let { picks += Kind.IMAGE to it.url }
        slots.firstOrNull { it.kind == Kind.VIDEO }?.let { picks += Kind.VIDEO to it.url }
        return picks
    }

    private val sizeHint = Regex("""_[sp](\d+)x(\d+)""")

    private fun imageArea(url: String): Long {
        val stp = runCatching { Uri.parse(url).getQueryParameter("stp") }.getOrNull()
            ?: return Long.MAX_VALUE // no resize instruction = original size
        val m = sizeHint.find(stp) ?: return Long.MAX_VALUE
        return m.groupValues[1].toLong() * m.groupValues[2].toLong()
    }

    private fun localFile(dir: File, kind: Kind, url: String): File {
        val path = Uri.parse(url).path.orEmpty()
        val ext = path.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 }
            ?: if (kind == Kind.VIDEO) "mp4" else "jpg"
        return File(dir, "${kind.fileName}.$ext")
    }

    private fun download(url: String, out: File) {
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val length = conn.contentLengthLong
            if (length > MAX_FILE_BYTES) error("too large ($length bytes)")
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_FILE_BYTES) error("too large")
                        output.write(buffer, 0, n)
                    }
                }
            }
            if (!tmp.renameTo(out)) error("rename failed")
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }

    private fun maybePrune() {
        val now = System.currentTimeMillis()
        if (downloadsSincePrune < PRUNE_EVERY_DOWNLOADS && now - lastPrune < PRUNE_INTERVAL_MS) return
        runCatching { prune() }
    }

    private fun prune() {
        lastPrune = System.currentTimeMillis()
        downloadsSincePrune = 0
        prunePending()
        pruneKept()
    }

    private fun prunePending() {
        val now = System.currentTimeMillis()
        val dirs = pendingRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        for (dir in dirs) {
            when {
                kept.contains(dir.name) -> promote(dir.name) // kept while we weren't looking
                now - dir.lastModified() > pendingRetentionMs -> dir.deleteRecursively()
            }
        }
        var total = pendingRoot.listFiles()?.filter { it.isDirectory }.orEmpty().sumOf(::sizeOf)
        if (total <= PENDING_MAX_BYTES) return
        for (dir in pendingRoot.listFiles()?.filter { it.isDirectory }.orEmpty().sortedBy { it.lastModified() }) {
            total -= sizeOf(dir)
            dir.deleteRecursively()
            if (total <= PENDING_MAX_BYTES) break
        }
    }

    private fun pruneKept() {
        val now = System.currentTimeMillis()
        var dirs = keptRoot.listFiles()?.filter { it.isDirectory }.orEmpty()

        dirs.filter { !kept.contains(it.name) }.forEach { it.deleteRecursively() }
        dirs = dirs.filter { it.exists() }

        if (KEPT_MEDIA_MAX_AGE_DAYS > 0) {
            val maxAge = TimeUnit.DAYS.toMillis(KEPT_MEDIA_MAX_AGE_DAYS.toLong())
            dirs.filter { now - it.lastModified() > maxAge }.forEach {
                it.deleteRecursively()
                Logger.printDebug { "SaveDeletedMessages: kept media of ${it.name} expired (D10)" }
            }
            dirs = dirs.filter { it.exists() }
        }

        var total = dirs.sumOf(::sizeOf)
        if (total <= keptMaxBytes) return
        for (dir in dirs.sortedBy { it.lastModified() }) {
            total -= sizeOf(dir)
            dir.deleteRecursively()
            Logger.printDebug { "SaveDeletedMessages: kept media of ${dir.name} removed, over $KEPT_MEDIA_MAX_MB MB (D11)" }
            if (total <= keptMaxBytes) break
        }
    }

    private fun sizeOf(dir: File): Long = dir.listFiles().orEmpty().sumOf { it.length() }

    private fun migrateFromOldLayout() {
        val dirs = keptRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        for (dir in dirs) {
            if (kept.contains(dir.name)) continue
            val to = pendingDir(dir.name)
            to.parentFile?.mkdirs()
            val mtime = dir.lastModified()
            if (to.exists() || !dir.renameTo(to)) dir.deleteRecursively() else to.setLastModified(mtime)
        }
    }

    private companion object {
        const val DIR_NAME = "nexalloy_unsent_media"
        const val MAX_DEPTH = 8
        const val MAX_NODES = 4000
        // 0 in the settings = no limit.
        val MAX_FILE_BYTES = if (MAX_MEDIA_FILE_MB <= 0) Long.MAX_VALUE else MAX_MEDIA_FILE_MB.toLong() * 1024 * 1024
        val PENDING_MAX_BYTES = if (PENDING_MEDIA_MAX_MB <= 0) Long.MAX_VALUE else PENDING_MEDIA_MAX_MB.toLong() * 1024 * 1024
        val PRUNE_INTERVAL_MS = TimeUnit.HOURS.toMillis(PRUNE_EVERY_HOURS.toLong())
    }
}

private const val DIRECT_THREAD_KEY_CLASS = "com.instagram.model.direct.DirectThreadKey"
private const val USER_SESSION_CLASS = "com.instagram.common.session.UserSession"

private fun compareIds(a: String, b: String): Int {
    val byLength = a.length - b.length
    return if (byLength != 0) byLength else a.compareTo(b)
}

private fun insertIfMissing(list: MutableList<Any?>, item: Any, message: MessageAccess): Boolean {
    val id = message.itemId(item)
    if (list.any { it === item || (id != null && message.itemId(it) == id) }) return false
    insertSorted(list, item, message)
    return true
}

private fun insertSorted(list: MutableList<Any?>, item: Any, message: MessageAccess) {
    val id = message.itemId(item) ?: run { list.add(item); return }
    for (i in list.indices) {
        val other = message.itemId(list[i]) ?: continue
        if (compareIds(other, id) > 0) {
            list.add(i, item)
            return
        }
    }
    list.add(item)
}

private fun findThreadKey(root: Any): Any? {
    var level = listOf(root)
    repeat(3) {
        val next = ArrayList<Any>()
        for (node in level) {
            if (node.javaClass.name == DIRECT_THREAD_KEY_CLASS) return node
            var cls: Class<*>? = node.javaClass
            while (cls != null && cls != Any::class.java) {
                for (f in cls.declaredFields) {
                    if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                    val v = runCatching { f.isAccessible = true; f.get(node) }.getOrNull() ?: continue
                    if (v.javaClass.name == DIRECT_THREAD_KEY_CLASS) return v
                    val n = v.javaClass.name
                    if (!n.startsWith("java.") && !n.startsWith("android.") && !n.startsWith("kotlin.")) next += v
                }
                cls = cls.superclass
            }
        }
        level = next
    }
    return null
}

private fun threadIdsOf(key: Any): List<String> {
    val out = ArrayList<String>(2)
    var cls: Class<*>? = key.javaClass
    while (cls != null && cls != Any::class.java) {
        for (f in cls.declaredFields) {
            if (Modifier.isStatic(f.modifiers) || f.type != String::class.java) continue
            runCatching { f.isAccessible = true; (f.get(key) as? String) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let(out::add)
        }
        cls = cls.superclass
    }
    return out
}

private class ShadowStore(
    context: Context,
    private val message: MessageAccess,
    private val toRow: java.lang.reflect.Method?,
    private val fromBlob: java.lang.reflect.Method?,
    private val tableGetter: java.lang.reflect.Method?,
) {
    @Volatile
    var table: Any? = null
        set(value) {
            if (value == null || field != null) return
            field = value
            flushPending()
        }

    private val root = File(context.filesDir, "nexalloy_kept_messages")

    fun tableFrom(session: Any) {
        if (table != null) return
        val getter = tableGetter ?: return
        runCatching { getter.invoke(null, session) }
            .onSuccess {
                if (it != null) {
                    table = it
                    Logger.printDebug { "SaveDeletedMessages: message table ready (from UserSession)" }
                }
            }
            .onFailure { Logger.printDebug { "SaveDeletedMessages: table getter failed: $it" } }
    }

    private val index = ConcurrentHashMap<String, ConcurrentHashMap<String, File>>()

    private val pending: MutableList<Any> = Collections.synchronizedList(ArrayList())

    private val rebuilt = ConcurrentHashMap<String, Any>()

    init {
        root.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val files = dir.listFiles { f -> f.name.endsWith(".bin") && f.length() > 0 }.orEmpty()
            if (files.isEmpty()) return@forEach
            val map = index.getOrPut(dir.name) { ConcurrentHashMap() }
            files.forEach { map[it.name.removeSuffix(".bin")] = it }
        }
    }

    fun has(itemId: String?): Boolean =
        itemId != null && index.values.any { it.containsKey(itemId) }

    fun save(msg: Any) {
        if (has(message.itemId(msg))) return
        val writer = toRow ?: return // serializer fingerprint missing: nothing we can do
        val t = table
        if (t == null) {
            synchronized(pending) {
                pending += msg
                while (pending.size > MAX_PENDING) pending.removeAt(0)
            }
            return
        }
        val row = runCatching { writer.invoke(t, msg, ByteArrayOutputStream()) as? ContentValues }
            .onFailure { Logger.printDebug { "SaveDeletedMessages: backup failed: $it" } }
            .getOrNull()
        saveRow(msg, row)
    }

    fun saveRow(msg: Any, row: ContentValues?) {
        row ?: return
        val itemId = message.itemId(msg) ?: return
        if (has(itemId)) return
        val blob = row.getAsByteArray("message") ?: return
        val threadId = row.getAsString("thread_id")?.takeIf { it.isNotEmpty() }
            ?: message.threadKey(msg)?.let { threadIdsOf(it).firstOrNull() }
            ?: return
        runCatching {
            val dir = File(root, safeName(threadId)).apply { mkdirs() }
            val file = File(dir, safeName(itemId) + ".bin")
            val tmp = File(dir, file.name + ".part")
            tmp.writeBytes(blob)
            if (!tmp.renameTo(file)) error("rename failed")
            index.getOrPut(dir.name) { ConcurrentHashMap() }[itemId] = file
        }.onSuccess {
            Logger.printDebug { "SaveDeletedMessages: backed up $itemId (thread $threadId)" }
        }.onFailure {
            Logger.printDebug { "SaveDeletedMessages: backup write failed for $itemId: $it" }
        }
    }

    fun count(): Int = index.values.sumOf { it.size }

    fun restoreInto(cached: MutableList<Any?>, threadKey: Any, onRestored: (Any) -> Unit = {}): Int {
        if (index.isEmpty()) return 0
        val t = table ?: return 0
        val parser = fromBlob ?: return 0
        val entries = threadIdsOf(threadKey).firstNotNullOfOrNull { index[safeName(it)] } ?: return 0
        if (entries.isEmpty()) return 0

        val present = cached.mapNotNullTo(HashSet()) { message.itemId(it) }
        var inserted = 0
        for ((itemId, file) in entries) {
            if (itemId in present) continue
            val restored = rebuilt[itemId] ?: runCatching { parser.invoke(t, file.readBytes()) }
                .onFailure { Logger.printDebug { "SaveDeletedMessages: restore failed for $itemId: $it" } }
                .getOrNull()?.also { rebuilt[itemId] = it }
                ?: continue
            message.setThreadKeyIfMissing(restored, threadKey)
            message.unhide(restored)
            if (insertIfMissing(cached, restored, message)) {
                inserted++
                onRestored(restored)
            }
        }
        return inserted
    }

    fun itemIdsOfThread(threadKey: Any): List<String> =
        threadIdsOf(threadKey).flatMap { index[safeName(it)]?.keys?.toList().orEmpty() }.distinct()

    fun drop(itemIds: Collection<String>) {
        if (itemIds.isEmpty()) return
        val wanted = itemIds.toHashSet()
        for ((thread, entries) in index) {
            for (id in entries.keys.filter { it in wanted }) {
                entries.remove(id)?.delete()
                rebuilt.remove(id)
            }
            if (entries.isEmpty()) {
                index.remove(thread)
                File(root, thread).delete() // only succeeds when empty
            }
        }
    }

    private fun flushPending() {
        val items = synchronized(pending) { ArrayList(pending).also { pending.clear() } }
        items.forEach(::save)
    }

    private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private companion object {
        const val MAX_PENDING = 200
    }
}

