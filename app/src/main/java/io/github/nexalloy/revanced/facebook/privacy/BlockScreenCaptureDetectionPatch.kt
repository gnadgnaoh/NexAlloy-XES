package io.github.nexalloy.revanced.facebook.privacy

import android.app.Activity
import android.os.Build
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Modifier

val BlockScreenCaptureDetection = patch(
    name = "Block screenshot detection",
    description = "Stops Facebook from detecting screenshots and screen recordings.",
) {
    runCatching {
        Activity::class.java.declaredMethods
            .filter { it.name == "registerScreenCaptureCallback" }
            .forEach { m -> m.hookMethod { before { param -> param.result = null } } }
    }

    if (Build.VERSION.SDK_INT >= 35) {
        runCatching {
            Class.forName("android.view.ScreenRecordingCallbacks").declaredMethods
                .filter { !Modifier.isAbstract(it.modifiers) && (it.name == "addCallback" || it.name == "notifyCallbacks") }
                .forEach { m ->
                    m.isAccessible = true
                    val result: Any? = if (m.returnType == Int::class.javaPrimitiveType) 0 else null
                    m.hookMethod { before { param -> param.result = result } }
                }
        }
    }

    runCatching {
        Class.forName("android.os.FileObserver").declaredMethods
            .filter { !Modifier.isAbstract(it.modifiers) && (it.name == "startWatching" || it.name == "onEvent") }
            .forEach { m -> m.hookMethod { before { param -> param.result = null } } }
    }
}
