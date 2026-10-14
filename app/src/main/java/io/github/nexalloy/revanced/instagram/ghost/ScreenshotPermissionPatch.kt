package io.github.nexalloy.revanced.instagram.ghost

import android.view.Window
import android.view.WindowManager
import app.morphe.extension.shared.Logger
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val ScreenshotPermission = patch(
    name = "Screenshot permission",
    description = "Allows screenshots in DMs, stories, and reels by removing FLAG_SECURE.",
) {
    Window::class.java.getDeclaredMethod("setFlags", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        .hookMethod {
            before { param ->
                param.args[0] = (param.args[0] as Int) and WindowManager.LayoutParams.FLAG_SECURE.inv()
                param.args[1] = (param.args[1] as Int) and WindowManager.LayoutParams.FLAG_SECURE.inv()
                Logger.printDebug { "Ghost: FLAG_SECURE stripped from Window.setFlags" }
            }
        }

    Window::class.java.getDeclaredMethod("addFlags", Int::class.javaPrimitiveType)
        .hookMethod {
            before { param ->
                param.args[0] = (param.args[0] as Int) and WindowManager.LayoutParams.FLAG_SECURE.inv()
                Logger.printDebug { "Ghost: FLAG_SECURE stripped from Window.addFlags" }
            }
        }
}
