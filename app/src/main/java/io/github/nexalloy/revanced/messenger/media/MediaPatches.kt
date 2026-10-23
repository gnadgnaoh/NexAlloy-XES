package io.github.nexalloy.revanced.messenger.media

import android.graphics.BitmapFactory
import app.morphe.extension.shared.Logger
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

private const val MEDIA_TRANSCODER = "com.facebook.msys.mci.transcoder.DefaultMediaTranscoder"

val SaveAnyStory = patch(
    name = "Save any story",
    description = "Adds Save to the More options menu on other people's stories, so you can keep their photo or video.",
) {
    val isOwn = ::storyOwnCheckFingerprint.method.apply { isAccessible = true }
    val ownFlag = ::storyOwnFlagFingerprint.field.apply { isAccessible = true }
    val controller = ::storyCardControllerFingerprint.method.apply { isAccessible = true }
    val labelOf = ::storySaveLabelOfFingerprint.method.apply { isAccessible = true }
    val icon = ::storySaveIconFingerprint.field.apply { isAccessible = true }
    val addItem = ::storyMenuAddItemFingerprint.method.apply { isAccessible = true }
    StorySaveNumbers.load(appContext, classLoader)

    val menus = ThreadLocal<Any?>()
    ::storyMenuBuilderFingerprint.hookMethod {
        before { menus.remove() }
        after { menus.remove() }
    }
    ::storyMenuFactoryFingerprint.hookMethod { after { param -> menus.set(param.result) } }

    ::storyMenuHandlerInitFingerprint.hookMethod {
        before { param ->
            val menu = menus.get() ?: return@before
            menus.remove()
            val fragment = param.args[0] ?: return@before
            val numbers = StorySaveNumbers.get() ?: return@before
            runCatching {
                if (isOwn.invoke(fragment) == true || ownFlag.getBoolean(fragment)) return@runCatching
                val card = controller.invoke(null, fragment) ?: return@runCatching
                if (numbers.saveable.none { it.isInstance(card) }) return@runCatching
                val label = labelOf.invoke(null, fragment, numbers.label) as String
                addItem.invoke(null, icon.get(null), fragment, menu, label, numbers.id)
            }.onFailure { Logger.printInfo { "Messenger: couldn't add Save to the story menu: $it" } }
        }
    }
}

val SendPhotosAtOriginalQuality = patch(
    name = "Send photos at original quality",
    description = "With HD on, sends a JPEG photo at its full size instead of Messenger's smaller copy. " +
        "Location and camera details are still removed. Photos over 20 MB are still made smaller.",
    use = false,
) {
    OriginalPhoto.context = appContext
    val transcoder = MEDIA_TRANSCODER.findClassOrNull(classLoader) ?: error("DefaultMediaTranscoder not found")
    val methods = transcoder.declaredMethods
    val d = Double::class.javaPrimitiveType

    methods.single { it.name == "transcodeImage" && it.parameterTypes.size == 5 }.hookMethod {
        before { param ->
            val bytes = OriginalPhoto.sync(param.args[0] as? String, param.args[1] as Double, param.args[2] as Double, param.args[4] as? Map<*, *>)
            if (bytes != null) param.result = bytes
        }
    }
    methods.single { it.name == "transcodeImageAsync" && it.parameterTypes.size == 6 }.hookMethod {
        before { param ->
            val handled = OriginalPhoto.async(
                param.args[0] as? String, param.args[1] as Double, param.args[2] as Double,
                param.args[4] as? Map<*, *>, param.args[5],
            )
            if (handled) param.result = null
        }
    }

    methods.firstOrNull { it.name == "getTranscodeConfig" && it.parameterTypes.isEmpty() }?.hookMethod {
        after { param -> OriginalPhoto.transcodeConfig(param.result) }
    }
    methods.firstOrNull { it.name == "decodeBitmap" && it.parameterTypes.contentEquals(arrayOf(String::class.java, d, d)) }?.hookMethod {
        before { param ->
            OriginalPhoto.decodeLimits(param.args[0] as? String, param.args[1] as Double, param.args[2] as Double)?.let {
                param.args[1] = it.first
                param.args[2] = it.second
            }
        }
        after { OriginalPhoto.clearDecode() }
    }
    BitmapFactory::class.java.getDeclaredMethod("decodeFile", String::class.java).hookMethod {
        before { param ->
            if (OriginalPhoto.hasDecode()) param.result = OriginalPhoto.decodeFile(param.args[0] as? String)
        }
    }
}

val SendVideosWithoutReencoding = patch(
    name = "Send videos without re-encoding",
    description = "Sends a video as the original file instead of a recompressed copy, when Messenger allows it. " +
        "Videos over 25 MB, edited videos and some formats are still compressed.",
    use = false,
) {
    val sizeField = ::videoSizeFieldFingerprint.field.apply { isAccessible = true }
    val inWorker = ThreadLocal<MutableList<Pair<Any, Long>>>()

    ::videoTranscodeFingerprint.hookMethod {
        before { inWorker.set(mutableListOf()) }
        after {
            inWorker.get()?.forEach { (meta, real) -> runCatching { sizeField.setLong(meta, real) } }
            inWorker.remove()
        }
    }
    ::videoMetadataReaderFingerprint.hookMethod {
        after { param ->
            val touched = inWorker.get() ?: return@after
            val meta = param.result ?: return@after
            runCatching {
                val real = sizeField.getLong(meta)
                if (real in 1..MAX_ORIGINAL_VIDEO_BYTES) {
                    touched += meta to real
                    sizeField.setLong(meta, 1)
                }
            }
        }
    }
}

private const val MAX_ORIGINAL_VIDEO_BYTES = 25L * 1024 * 1024
