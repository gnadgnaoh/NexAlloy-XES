package io.github.nexalloy.revanced.facebook.download

import android.view.MenuItem
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.hook.ScopedFrameworkHook

val DownloadPhoto = patch(
    name = "Download any photo",
    description = "Shows Save photo on every photo, even where the poster turned saving off, and saves the largest size.",
) {
    val gates = ::photoSaveGateMethodsFingerprint.dexMethodList.map { it.toMethod() }
    check(gates.isNotEmpty()) { "no photo menu reading can_viewer_download" }
    val getBooleanValue = classLoader.loadClass(TREE_JNI).getDeclaredMethod("getBooleanValue", Int::class.javaPrimitiveType)
    val saveAction = ::newSavePhotoActionFingerprint.method
    useCdnResize()

    val gate = ScopedFrameworkHook(getBooleanValue, after = { param ->
        if (param.args[0] == CAN_VIEWER_DOWNLOAD) param.result = true
    })
    gates.forEach { menu ->
        deoptimize(menu)
        gate.installOn(menu)
    }

    saveAction.hookMethod {
        after { param ->
            val own = param.result as? MenuItem.OnMenuItemClickListener ?: return@after
            val photo = param.args[0]
            param.result = MenuItem.OnMenuItemClickListener { item ->
                val saved = runCatching {
                    MediaSaver.savePhoto(appContext, GraphModel.string(photo, "id"), PhotoSize.photo(photo))
                }.getOrDefault(false)
                saved || own.onMenuItemClick(item)
            }
        }
    }
}
