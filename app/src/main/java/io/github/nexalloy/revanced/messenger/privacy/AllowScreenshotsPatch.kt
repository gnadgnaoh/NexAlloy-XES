package io.github.nexalloy.revanced.messenger.privacy

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import app.morphe.extension.shared.Logger
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Modifier

val AllowScreenshots = patch(
    name = "Allow screenshots",
    description = "Lets you screenshot protected chat media, including view-once and Quicksnap, " +
        "and stops the screenshot notice to the other person.",
) {
    val secure = WindowManager.LayoutParams.FLAG_SECURE

    Window::class.java
        .getDeclaredMethod("setFlags", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        .hookMethod {
            before { param ->
                param.args[0] = (param.args[0] as Int) and secure.inv()
                param.args[1] = (param.args[1] as Int) and secure.inv()
            }
        }

    Window::class.java
        .getDeclaredMethod("addFlags", Int::class.javaPrimitiveType)
        .hookMethod {
            before { param -> param.args[0] = (param.args[0] as Int) and secure.inv() }
        }

    appContext.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            // Chạy lúc app hoạt động → giữ để không làm sập app.
            runCatching { activity.window.clearFlags(secure) }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    })

    "com.facebook.screenshot.ScreenshotContentObserver".findClassOrNull(classLoader)
        ?.getDeclaredMethod("onChange", Boolean::class.javaPrimitiveType, Uri::class.java)
        ?.hookMethod { before { param -> param.result = null } }
        ?: Logger.printInfo { "Messenger: ScreenshotContentObserver not present, screenshot notice not blocked" }

    Activity::class.java.declaredMethods
        .filter { it.name == "registerScreenCaptureCallback" }
        .forEach { m -> m.hookMethod { before { param -> param.result = null } } }

    if (Build.VERSION.SDK_INT >= 35) {
        // Class ẩn, một số ROM có thể không có → dò bằng findClassOrNull.
        "android.view.ScreenRecordingCallbacks".findClassOrNull(null)?.declaredMethods
            ?.filter { !Modifier.isAbstract(it.modifiers) && (it.name == "addCallback" || it.name == "notifyCallbacks") }
            ?.forEach { m ->
                m.isAccessible = true
                val result: Any? = if (m.returnType == Int::class.javaPrimitiveType) 0 else null
                m.hookMethod { before { param -> param.result = result } }
            }
    }
}
