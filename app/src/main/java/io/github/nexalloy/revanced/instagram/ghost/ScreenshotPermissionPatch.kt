package io.github.nexalloy.revanced.instagram.ghost

import android.view.Window
import android.view.WindowManager
import app.morphe.extension.shared.Logger
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

/**
 * Strips FLAG_SECURE from every Window.setFlags / Window.addFlags call so the user can
 * take screenshots even when Instagram would normally block them.
 *
 * Instagram sets FLAG_SECURE on several windows (DM threads, stories, reels) which causes
 * the system to show "App doesn't allow screenshots". We intercept both entry points before
 * the flag reaches the WindowManager so no patching of Instagram's internal classes is required.
 */
val ScreenshotPermission = patch(
    name = "Screenshot permission",
    description = "Allows screenshots in DMs, stories, and reels by removing FLAG_SECURE.",
) {
    // Hook Window.setFlags(int flags, int mask)
    Window::class.java.getDeclaredMethod("setFlags", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        .hookMethod {
            before { param ->
                param.args[0] = (param.args[0] as Int) and WindowManager.LayoutParams.FLAG_SECURE.inv()
                param.args[1] = (param.args[1] as Int) and WindowManager.LayoutParams.FLAG_SECURE.inv()
                Logger.printDebug { "Ghost: FLAG_SECURE stripped from Window.setFlags" }
            }
        }

    // Hook Window.addFlags(int flags)
    Window::class.java.getDeclaredMethod("addFlags", Int::class.javaPrimitiveType)
        .hookMethod {
            before { param ->
                param.args[0] = (param.args[0] as Int) and WindowManager.LayoutParams.FLAG_SECURE.inv()
                Logger.printDebug { "Ghost: FLAG_SECURE stripped from Window.addFlags" }
            }
        }
}
