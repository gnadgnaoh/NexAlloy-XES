package io.github.nexalloy.revanced.instagram.download;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records which hooks actually resolved their target, so a failure to find an obfuscated
 * method is visible in the log instead of failing silently. InstaEclipse surfaced this in
 * its own UI; NexAlloy has no equivalent screen, so the state is only kept in memory and
 * logged once per hook.
 */
public final class FeatureStatusTracker {

    private static final Map<String, Boolean> HOOKED =
            Collections.synchronizedMap(new LinkedHashMap<>());

    private FeatureStatusTracker() {}

    public static void setHooked(String feature) {
        if (HOOKED.put(feature, Boolean.TRUE) == null) {
            ModuleLog.line("(NA|DL) hooked: " + feature);
        }
    }

    /** Kept for call-site compatibility; the string resource is only used by InstaEclipse's UI. */
    public static void setEnabled(String feature, int labelRes) {
        setHooked(feature);
    }

    public static boolean isHooked(String feature) {
        return Boolean.TRUE.equals(HOOKED.get(feature));
    }
}
