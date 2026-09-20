package io.github.nexalloy.revanced.instagram.download;

import android.content.Context;

import androidx.annotation.StringRes;

import io.github.nexalloy.BuildConfig;

/**
 * Loads string resources from the NexAlloy module APK while running inside the host
 * app (Instagram) process.
 *
 * <p>{@code createPackageContext} inherits the device's current locale, so Android picks
 * the right {@code values-xx} folder without any manual override. That call is not cheap
 * and these strings are read from menu builders and click handlers, so the context is
 * created once and kept.
 */
public final class I18n {

    private static volatile Context moduleContext;

    private I18n() {}

    public static String t(Context hostContext, @StringRes int resId, Object... args) {
        try {
            Context module = moduleContext(hostContext);
            if (module == null) return "";
            return args.length == 0
                    ? module.getString(resId)
                    : module.getString(resId, args);
        } catch (Exception e) {
            return "";
        }
    }

    private static Context moduleContext(Context hostContext) {
        Context cached = moduleContext;
        if (cached != null) return cached;
        synchronized (I18n.class) {
            if (moduleContext == null) {
                try {
                    moduleContext = hostContext.createPackageContext(
                            BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY);
                } catch (Exception e) {
                    ModuleLog.line("(NA|I18n) cannot reach module resources: " + e);
                }
            }
            return moduleContext;
        }
    }
}
