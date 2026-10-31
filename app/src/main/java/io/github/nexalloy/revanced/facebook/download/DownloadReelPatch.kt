package io.github.nexalloy.revanced.facebook.download

import android.content.Context
import app.morphe.extension.shared.Logger
import io.github.nexalloy.R
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

val DownloadReel = patch(
    name = "Download any reel",
    description = "Adds a Download row to every reel's More menu, at the best quality.",
) {
    val hdField = ::videoSourceHdUriFieldFingerprint.field.accessible()
    val sdField = ::videoSourceSdUriFieldFingerprint.field.accessible()
    val manifestField = ::videoSourceManifestFieldFingerprint.field.accessible()
    val builder = ::reelMoreSheetBuilderFingerprint.method
    val lookup = ::reelPlayerStateLookupFingerprint.method.apply { isAccessible = true }
    val row = ::reelRowConstructorFingerprint.constructor.apply { isAccessible = true }
    val wrap = ::reelIconWrapFingerprint.method.apply { isAccessible = true }
    val iconField = ::reelDownloadIconFieldFingerprint.field.accessible()

    val params = builder.parameterTypes
    val storeAt = params.indexOfFirst { lookup.declaringClass.isAssignableFrom(it) }
    val originAt = params.indexOfFirst { it.name == PLAYER_ORIGIN }
    val idAt = params.indexOfFirst { it == String::class.java }
    val listAt = params.indexOfFirst { it == java.util.List::class.java }
    check(storeAt >= 0 && originAt >= 0 && idAt >= 0 && listAt >= 0) { "More sheet builder parameters moved" }
    val contextAt = params.indexOfFirst { type -> type.declaredFields.count { it.type == Context::class.java } == 1 }
    val contextField = params.getOrNull(contextAt)?.declaredFields?.single { it.type == Context::class.java }?.accessible()

    val rowParams = row.parameterTypes
    val click = rowParams.singleOrNull { type ->
        type.isInterface && Modifier.isPublic(type.modifiers) && type.declaredMethods.size == 1 &&
            type.declaredMethods.single().let { it.parameterCount == 0 && it.returnType == Void.TYPE }
    } ?: error("row takes no click interface")
    val titleAt = rowParams.indexOf(CharSequence::class.java)
    val rowIdAt = rowParams.lastIndexOf(CharSequence::class.java).takeIf { it != titleAt }

    deoptimize(builder)
    builder.hookMethod {
        before { param ->
            runCatching {
                val videoId = param.args[idAt] as? String ?: return@runCatching
                val state = lookup.invoke(param.args[storeAt] ?: return@runCatching, param.args[originAt] ?: return@runCatching, videoId)
                    ?: return@runCatching
                val context = contextField?.get(param.args[contextAt]) as? Context ?: appContext
                val onClick = Proxy.newProxyInstance(click.classLoader, arrayOf(click)) { proxy, method, args ->
                    when (method.name) {
                        "equals" -> proxy === args?.getOrNull(0)
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> "NexAlloy download row"
                        else -> saveReel(context, videoId, state, hdField, sdField, manifestField).let { null }
                    }
                }
                val icon = wrap.invoke(null, iconField.get(null))
                val args = rowParams.mapIndexed { i, type ->
                    when {
                        type == click -> onClick
                        i == titleAt -> MediaSaver.t(context, R.string.fb_dl_download)
                        i == rowIdAt -> "nexalloy_download_reel"
                        type.isInstance(icon) -> icon
                        else -> null
                    }
                }.toTypedArray()
                @Suppress("UNCHECKED_CAST")
                (param.args[listAt] as MutableList<Any?>).add(row.newInstance(*args))
            }.onFailure { Logger.printException({ "Facebook: could not add the reel Download row" }, it) }
        }
    }
}

private fun sourceOf(state: Any): Any? {
    var current: Any? = state
    repeat(3) {
        val host = current ?: return null
        val fields = host.javaClass.declaredFields
        fields.singleOrNull { it.type.name == VIDEO_DATA_SOURCE }?.let { return it.accessible().get(host) }
        current = fields.firstOrNull { it.type.name == VIDEO_PLAYER_PARAMS }?.accessible()?.get(host)
    }
    return null
}

private fun saveReel(
    context: Context, videoId: String, state: Any,
    hdField: java.lang.reflect.Field, sdField: java.lang.reflect.Field, manifestField: java.lang.reflect.Field,
) {
    val ok = runCatching {
        val source = sourceOf(state) ?: PlayerSources.byId(videoId)?.let { s ->
            return@runCatching MediaSaver.saveVideo(context, videoId, s.manifest, listOfNotNull(s.hd))
        } ?: return@runCatching false
        val urls = listOfNotNull(hdField.get(source)?.toString(), sdField.get(source)?.toString())
        MediaSaver.saveVideo(context, videoId, manifestField.get(source) as? String, urls)
    }.getOrDefault(false)
    if (!ok) MediaSaver.toast(context, MediaSaver.t(context, R.string.fb_dl_no_video))
}
