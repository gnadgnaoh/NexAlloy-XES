package io.github.nexalloy.revanced.instagram.download;

/**
 * Runtime switches for the Instagram download patches.
 *
 * <p>NexAlloy decides which patches run before they are installed (each {@code patch { }}
 * is gated by its own preference), so these flags are simply set to {@code true} by the
 * Kotlin wrapper when the corresponding patch is applied. They stay as fields rather than
 * constants because the ported hooks check them on every click — a patch can therefore be
 * turned off at runtime without unhooking.
 */
public final class FeatureFlags {

    private FeatureFlags() {}

    // ── Download entry points ────────────────────────────────────────────────
    public static boolean enableReelDownload    = false;
    public static boolean enablePostDownload    = false;
    public static boolean enableStoryDownload   = false;
    public static boolean enableProfileDownload = false;
    public static boolean saveInstants          = false;
    public static boolean uploadInstants        = false;
    public static boolean copyMediaLink         = false;
    public static boolean cacheStories          = false;

    // ── Destination options ──────────────────────────────────────────────────

    /**
     * Absolute folder path to save into, e.g. {@code /sdcard/Pictures/IG}.
     * Empty (the default) means MediaStore {@code Download/NexAlloy}.
     */
    public static String  downloaderCustomPath    = "";

    /** Creates a per-author sub-folder inside the destination. */
    public static boolean downloaderUsernameFolder = false;

    /** Appends {@code _yyyyMMdd_HHmmss} to every saved filename. */
    public static boolean downloaderAddTimestamp   = false;
}
