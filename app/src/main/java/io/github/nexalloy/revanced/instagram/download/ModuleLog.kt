package io.github.nexalloy.revanced.instagram.download

import app.morphe.extension.shared.Logger

internal object ModuleLog {

    fun line(message: String) {
        Logger.printInfo { message }
    }
}
