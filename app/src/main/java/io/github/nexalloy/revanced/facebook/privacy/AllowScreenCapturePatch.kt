package io.github.nexalloy.revanced.facebook.privacy

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val AllowScreenCapture = patch(
    name = "Allow screenshots & recording",
    description = "Removes FLAG_SECURE so screenshots and screen recordings work everywhere in Facebook.",
) {
    val secure = WindowManager.LayoutParams.FLAG_SECURE

    runCatching {
        Window::class.java.getDeclaredMethod("setFlags", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .hookMethod {
                before { param ->
                    param.args[0] = (param.args[0] as Int) and secure.inv()
                    param.args[1] = (param.args[1] as Int) and secure.inv()
                }
            }
    }
    runCatching {
        Window::class.java.getDeclaredMethod("addFlags", Int::class.javaPrimitiveType)
            .hookMethod {
                before { param -> param.args[0] = (param.args[0] as Int) and secure.inv() }
            }
    }

    runCatching {
        appContext.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                runCatching { activity.window.clearFlags(secure) }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
