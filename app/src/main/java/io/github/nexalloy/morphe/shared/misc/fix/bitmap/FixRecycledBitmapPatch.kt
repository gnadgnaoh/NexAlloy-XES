package io.github.nexalloy.morphe.shared.misc.fix.bitmap

import android.graphics.Bitmap
import android.media.MediaMetadata
import app.morphe.extension.shared.patches.FixRecycledBitmapPatch
import io.github.nexalloy.patch
import org.luckypray.dexkit.wrap.DexMethod

val fixRecycledBitmapPatch = patch(
    description = "Fixes recycled bitmap crashes by routing putBitmap through the extension class."
) {
    DexMethod($$"Landroid/media/MediaMetadata$Builder;->putBitmap(Ljava/lang/String;Landroid/graphics/Bitmap;)Landroid/media/MediaMetadata$Builder;").hookMethod {
        val guard = ThreadLocal<Boolean>()
        before {
            if (guard.get() == true) {
                return@before
            }

            guard.set(true)
            try {
                it.result = FixRecycledBitmapPatch.putBitmap(
                    it.thisObject as MediaMetadata.Builder?,
                    it.args[0] as String?,
                    it.args[1] as Bitmap?
                )
            } finally {
                guard.remove()
            }
        }
    }
}
