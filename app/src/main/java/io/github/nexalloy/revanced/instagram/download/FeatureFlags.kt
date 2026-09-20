package io.github.nexalloy.revanced.instagram.download

/**
 * Runtime switches for the Instagram download patches.
 *
 * NexAlloy decides which patches run before they are installed (each `patch { }`
 * is gated by its own preference), so these flags are simply set to `true` by the
 * Kotlin wrapper when the corresponding patch is applied. They stay as fields rather than
 * constants because the ported hooks check them on every click — a patch can therefore be
 * turned off at runtime without unhooking.
 */
object FeatureFlags {

    // ── Download entry points ────────────────────────────────────────────────
    @JvmField var enableReelDownload = false
    @JvmField var enablePostDownload = false
    @JvmField var enableStoryDownload = false
    @JvmField var enableProfileDownload = false
    @JvmField var saveInstants = false
    @JvmField var uploadInstants = false
    @JvmField var copyMediaLink = false

    // ── Destination options ──────────────────────────────────────────────────

    /**
     * Absolute folder path to save into, e.g. `/sdcard/Pictures/IG`.
     * Empty (the default) means MediaStore `Download/NexAlloy`.
     */
    @JvmField var downloaderCustomPath: String = ""

    /** Creates a per-author sub-folder inside the destination. */
    @JvmField var downloaderUsernameFolder = false

    /** Appends `_yyyyMMdd_HHmmss` to every saved filename. */
    @JvmField var downloaderAddTimestamp = false
}
