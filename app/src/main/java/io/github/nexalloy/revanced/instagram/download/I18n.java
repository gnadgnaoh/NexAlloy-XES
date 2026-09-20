package io.github.nexalloy.revanced.instagram.download;

import android.content.Context;

import androidx.annotation.StringRes;

import io.github.nexalloy.BuildConfig;

/**
 * Loads string resources from the NexAlloy module APK while running inside the host
 * app (Instagram) process.
 *
 * <p>{@code createPackageContext} inherits the device's current locale, so Android picks
 * the right {@code values-xx} folder without any manual override.
 */
public final class I18n {

    private I18n() {}

    public static String t(Context hostContext, @StringRes int resId, Object... args) {
        try {
            Context moduleContext = hostContext.createPackageContext(
                    BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY);
            return args.length == 0
                    ? moduleContext.getString(resId)
                    : moduleContext.getString(resId, args);
        } catch (Exception e) {
            return "";
        }
    }
}
