package io.github.nexalloy.revanced.instagram.download;

import app.morphe.extension.shared.Logger;

/**
 * Thin adapter so the ported InstaEclipse hooks keep their one-line logging calls
 * while writing through NexAlloy's shared {@link Logger}.
 */
final class ModuleLog {

    private ModuleLog() {}

    static void line(String message) {
        Logger.printInfo(() -> message);
    }
}
