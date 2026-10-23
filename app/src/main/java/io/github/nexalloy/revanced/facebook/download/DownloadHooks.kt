package io.github.nexalloy.revanced.facebook.download

import android.net.Uri
import android.os.Build
import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.callStaticMethod
import io.github.nexalloy.hookMethod
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.util.concurrent.atomic.AtomicBoolean

internal fun deoptimize(member: Member) {
    runCatching { XposedBridge::class.java.callStaticMethod("deoptimizeMethod", member) }
}

internal fun Field.accessible() = apply { isAccessible = true }

private val sourcesRecorded = AtomicBoolean()

internal fun PatchExecutor.recordPlayerSources() {
    val id = ::videoPlayerParamsVideoIdFieldFingerprint.field.accessible()
    val hd = ::videoSourceHdUriFieldFingerprint.field.accessible()
    val manifest = ::videoSourceManifestFieldFingerprint.field.accessible()
    val constructors = classLoader.loadClass(VIDEO_PLAYER_PARAMS).declaredConstructors
    check(constructors.isNotEmpty()) { "$VIDEO_PLAYER_PARAMS has no constructor" }
    if (!sourcesRecorded.compareAndSet(false, true)) return
    constructors.forEach { c ->
        c.hookMethod { after { param -> runCatching { PlayerSources.remember(param.thisObject, id, hd, manifest) } } }
    }
}

internal object PhotoSize {
    private val MOST = Regex("mx(\\d+)x(\\d+)")
    private val ASKED = Regex("[spk](\\d+)x(\\d+)")
    private val IMAGES = listOf("imageHigh", "image", "imageMedium", "imageLow")

    @Volatile
    var resizer: ((Uri, Int, Int) -> Uri?)? = null

    private fun size(value: String?, pattern: Regex) = value?.let { pattern.find(it) }?.let {
        val w = it.groupValues[1].toIntOrNull() ?: 0
        val h = it.groupValues[2].toIntOrNull() ?: 0
        if (w > 0 && h > 0) w.toLong() * h else null
    }

    private fun area(image: Any?) = GraphModel.int(image, "width").toLong() * GraphModel.int(image, "height")

    private fun resized(address: String?): String? {
        val uri = runCatching { Uri.parse(address) }.getOrNull()?.takeIf { it.isHierarchical } ?: return null
        val most = MOST.find(uri.getQueryParameter("cstp") ?: return null) ?: return null
        val answer = runCatching {
            resizer?.invoke(uri, most.groupValues[1].toInt(), most.groupValues[2].toInt())
        }.getOrNull()
        return answer?.takeIf { it != uri }?.toString()
    }

    private fun better(own: String?, ownArea: Long, fullSource: String?): String? {
        if (Build.VERSION.SDK_INT < 31) return own
        val full = resized(fullSource) ?: return own
        val asked = size(Uri.parse(full).getQueryParameter("ctp"), ASKED) ?: 0
        return if (own != null && asked <= ownArea) own else full
    }

    fun story(image: Any?): String? {
        val own = GraphModel.string(image, "uri")?.takeIf { it.isNotEmpty() } ?: return null
        val held = area(image).takeIf { it > 0 } ?: size(runCatching { Uri.parse(own).getQueryParameter("ctp") }.getOrNull(), ASKED) ?: 0
        return better(own, held, own)
    }

    fun photo(photo: Any?): String? {
        if (!GraphModel.isLive(photo)) return null
        val largest = IMAGES.map { GraphModel.tree(photo, it) }
            .filter { !GraphModel.string(it, "uri").isNullOrEmpty() }
            .maxByOrNull { area(it) }
        return better(GraphModel.string(largest, "uri"), largest?.let(::area) ?: 0, GraphModel.string(GraphModel.tree(photo, IMAGES[0]), "uri"))
    }
}

private val cdnResizerSet = AtomicBoolean()

internal fun PatchExecutor.useCdnResize() {
    if (cdnResizerSet.get()) return
    runCatching {
        val modifier = ::cdnModifierFieldFingerprint.field.accessible()
        val resize = ::cdnResizeMethodFingerprint.method.apply { isAccessible = true }
        val options = ::cdnOptionsConstructorFingerprint.constructor.apply { isAccessible = true }
        val scale = ::cdnScaleFieldFingerprint.field.accessible()
        val tier = ::cdnTierFieldFingerprint.field.accessible()
        PhotoSize.resizer = { address, width, height ->
            modifier.get(null)?.let { resize.invoke(it, address, options.newInstance(scale.get(null), tier.get(null), width, height)) as? Uri }
        }
        cdnResizerSet.set(true)
    }.onFailure { Logger.printInfo { "Facebook: photo address modifier not found, saving the largest copy the model holds: ${it.message}" } }
}
