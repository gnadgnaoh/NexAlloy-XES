package io.github.nexalloy.revanced.instagram.download;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;


import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import io.github.nexalloy.R;

public class ReelDownloadHook {

    private static Class<?> controllerClass;
    private static Method   hookMethod;

    private static Method buttonAdderMethod;
    private static Field  activityField;

    // Cached field path to the carousel position holder on the controller.
    // The position holder is identified structurally: a non-framework object field
    // whose class has exactly ONE int field (survives obfuscation renames).
    private static Field cachedOuterField = null;
    private static Field cachedInnerField = null;

    /**
     * Binds the options-builder resolved by the patch, so {@link #onOptionsBuilt} knows which
     * controller class it is looking at. The patch hooks the method itself.
     */
    public static void bindOptionsBuilder(Method target) {
        if (target == null) return;
        target.setAccessible(true);
        hookMethod = target;
        controllerClass = target.getDeclaringClass();
        FeatureStatusTracker.setHooked("ReelDownload");
        ModuleLog.line("(NA|Reel) options builder: "
                + target.getDeclaringClass().getName() + "." + target.getName());
    }

    /**
     * True when {@code adder} exposes the reel-menu button-adder method
     * {@code (Context, View.OnClickListener, String, int)} — the same shape
     * {@link #onOptionsBuilt} invokes. Identifies the correct options-builder overload
     * structurally, so it survives obfuscated renames across Instagram versions.
     */
    public static boolean hasButtonAdderMethod(Class<?> adder) {
        if (adder == null) return false;
        for (Method m : adder.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 4
                    && Context.class.isAssignableFrom(p[0])
                    && View.OnClickListener.class.isAssignableFrom(p[1])
                    && p[2] == String.class
                    && p[3] == int.class) return true;
        }
        return false;
    }

    /**
     * Fallback index resolver: structurally locates the carousel position holder on the
     * controller. The holder is the unique non-framework field whose class has exactly
     * ONE int field — this property survives obfuscation renames across IG versions.
     * Values outside [0, 200) are excluded to filter out config constants.
     * Result is cached after first resolution.
     */
    private static int findReelCarouselIndex(Object controller) {
        if (controller == null) return 0;

        if (cachedOuterField != null && cachedInnerField != null) {
            try {
                Object holder = cachedOuterField.get(controller);
                if (holder != null) return cachedInnerField.getInt(holder);
            } catch (Throwable ignored) {}
            cachedOuterField = null;
            cachedInnerField = null;
        }

        int bestIdx = Integer.MAX_VALUE;
        Field bestOuter = null;
        Field bestInner = null;

        Class<?> c = controller.getClass();
        while (c != null && c != Object.class) {
            for (Field outerF : c.getDeclaredFields()) {
                if (outerF.getType().isPrimitive()) continue;
                String pkg = outerF.getType().getName();
                if (pkg.startsWith("android.") || pkg.startsWith("java.")
                        || pkg.startsWith("androidx.") || pkg.startsWith("kotlin.")) continue;
                outerF.setAccessible(true);
                Object nested;
                try { nested = outerF.get(controller); } catch (Throwable ignored) { continue; }
                if (nested == null) continue;

                Field singleIntField = null;
                int intCount = 0;
                Class<?> nc = nested.getClass();
                while (nc != null && nc != Object.class) {
                    String npkg = nc.getName();
                    if (npkg.startsWith("android.") || npkg.startsWith("java.")
                            || npkg.startsWith("androidx.") || npkg.startsWith("kotlin.")) break;
                    for (Field nf : nc.getDeclaredFields()) {
                        if (nf.getType() != int.class) continue;
                        intCount++;
                        singleIntField = nf;
                        if (intCount > 1) break;
                    }
                    if (intCount > 1) break;
                    nc = nc.getSuperclass();
                }

                if (intCount == 1 && singleIntField != null) {
                    singleIntField.setAccessible(true);
                    try {
                        int idx = singleIntField.getInt(nested);
                        if (idx >= 0 && idx < 200 && idx < bestIdx) {
                            bestIdx   = idx;
                            bestOuter = outerF;
                            bestInner = singleIntField;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            c = c.getSuperclass();
        }

        if (bestOuter != null) {
            cachedOuterField = bestOuter;
            cachedInnerField = bestInner;
            return bestIdx;
        }
        return 0;
    }

    /**
     * Primary index resolver: walks the activity's live view hierarchy for a
     * ViewPager / ViewPager2 / ReboundViewPager / horizontal RecyclerView whose adapter
     * item count equals {@code carouselSize} and returns its current data index.
     * Multiple unrelated carousels can coincidentally share the same item count (e.g.
     * two feed posts both showing 4 photos) — trusting the first DFS hit in that case
     * previously misattributed the index to the wrong post. So every match is collected
     * and the result is only trusted when exactly one candidate matches; otherwise the
     * caller falls back to the data-layer field.
     *
     * @return current position [0, carouselSize), or -1 if not found / ambiguous
     */
    static int findCarouselIndexFromView(Context ctx, int carouselSize) {
        if (!(ctx instanceof Activity)) return -1;
        try {
            View root = ((Activity) ctx).getWindow().getDecorView();
            List<Integer> matches = new java.util.ArrayList<>();
            collectCarouselMatches(root, carouselSize, matches);
            return matches.size() == 1 ? matches.get(0) : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Returns adapter item count, trying RecyclerView-style then PagerAdapter-style. */
    private static int adapterCount(Object adapter) {
        try { return (int) adapter.getClass().getMethod("getItemCount").invoke(adapter); } catch (Throwable ignored) {}
        try { return (int) adapter.getClass().getMethod("getCount").invoke(adapter); } catch (Throwable ignored) {}
        return -1;
    }

    /**
     * Recursive DFS over the view tree, collecting the resolved index of every carousel
     * whose adapter size matches — does not stop at the first hit. ViewPager / ViewPager2 /
     * ReboundViewPager are AndroidX / Instagram common-UI classes — stable names, no obfuscation.
     */
    private static void collectCarouselMatches(View view, int carouselSize, List<Integer> out) {
        String cn = view.getClass().getName();

        // ViewPager / ViewPager2 / ReboundViewPager and any subclass
        if (cn.contains("ViewPager")) {
            try {
                Object adapter = view.getClass().getMethod("getAdapter").invoke(view);
                if (adapter != null && adapterCount(adapter) == carouselSize) {
                    // Standard pagers: getCurrentItem()
                    // ReboundViewPager (Instagram looping carousel): getCurrentDataIndex()
                    for (String getter : new String[]{
                            "getCurrentItem", "getCurrentDataIndex",
                            "getCurrentWrappedDataIndex", "getCurrentRawDataIndex"}) {
                        try {
                            int cur = (int) view.getClass().getMethod(getter).invoke(view);
                            if (cur >= 0) { out.add(cur); break; }
                        } catch (NoSuchMethodException ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Horizontal RecyclerView (carousel, not the vertical feed list)
        if (cn.contains("RecyclerView")) {
            try {
                Object adapter = view.getClass().getMethod("getAdapter").invoke(view);
                if (adapter != null && adapterCount(adapter) == carouselSize) {
                    Object lm = view.getClass().getMethod("getLayoutManager").invoke(view);
                    if (lm != null) {
                        try {
                            int orientation = (int) lm.getClass().getMethod("getOrientation").invoke(lm);
                            if (orientation != 0 /* HORIZONTAL */) lm = null;
                        } catch (Throwable ignored) {}
                        if (lm != null) {
                            Integer pos = null;
                            try {
                                int p = (int) lm.getClass()
                                        .getMethod("findFirstCompletelyVisibleItemPosition").invoke(lm);
                                if (p >= 0) pos = p;
                            } catch (Throwable ignored) {}
                            if (pos == null) {
                                try {
                                    int p = (int) lm.getClass()
                                            .getMethod("findFirstVisibleItemPosition").invoke(lm);
                                    if (p >= 0) pos = p;
                                } catch (Throwable ignored) {}
                            }
                            if (pos != null) out.add(pos);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectCarouselMatches(vg.getChildAt(i), carouselSize, out);
            }
        }
    }

    public static void onOptionsBuilt(XC_MethodHook.MethodHookParam param) {
        try {
            Object controller  = param.thisObject;
            Object media       = param.args[0];
            Object buttonAdder = param.args[1];

            if (activityField == null) {
                for (Field f : controller.getClass().getDeclaredFields()) {
                    if (Activity.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        activityField = f;
                        break;
                    }
                }
            }
            if (activityField == null) {
                ModuleLog.line("(NA|Reel) ❌ no Activity field on controller");
                return;
            }

            Activity activity = (Activity) activityField.get(controller);
            if (activity == null) return;

            if (buttonAdderMethod == null) {
                for (Method m : buttonAdder.getClass().getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != 4) continue;
                    if (!Context.class.isAssignableFrom(p[0])) continue;
                    if (!View.OnClickListener.class.isAssignableFrom(p[1])) continue;
                    if (p[2] != String.class) continue;
                    if (p[3] != int.class) continue;
                    m.setAccessible(true);
                    buttonAdderMethod = m;
                    break;
                }
            }
            if (buttonAdderMethod == null) {
                ModuleLog.line("(NA|Reel) ❌ buttonAdderMethod not found");
                return;
            }

            int icon = resolveDownloadIcon(activity);
            final Activity actCopy      = activity;
            final Object mediaCopy      = media;
            final Object controllerCopy = controller;

            buttonAdderMethod.invoke(buttonAdder, activity,
                    (View.OnClickListener) v -> startReelDownload(actCopy, mediaCopy, controllerCopy),
                    I18n.t(activity, R.string.ig_dl_title), icon);

        } catch (Throwable t) {
            ModuleLog.line("(NA|Reel) ❌ onOptionsBuilt: " + t);
        }
    }

    private static void startReelDownload(Context ctx, Object media, Object controller) {
        String username = FeedVideoDownloadHook.extractUsernameFromMediaObject(media);
        if (username == null) username = "reel";

        String mediaId = "0";
        try {
            Object id = media.getClass().getMethod("getId").invoke(media);
            if (id instanceof String s && !s.isEmpty()) mediaId = s;
        } catch (Throwable ignored) {}

        String videoUrl = FeedVideoDownloadHook.bestVideoUrlFromMedia(media);

        if (videoUrl != null) {
            final String fn        = FeedVideoDownloadHook.buildFilename(username, "reel", mediaId, true);
            final String finalUrl  = videoUrl;
            final String finalUser = username;
            Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_downloading_reel), Toast.LENGTH_SHORT).show();
            FeedVideoDownloadHook.executor.submit(() -> {
                try {
                    boolean delegated = FeedVideoDownloadHook.downloadAndSave(ctx, finalUrl, fn, true, finalUser);
                    if (!delegated) {
                        FeedVideoDownloadHook.mainHandler.post(() ->
                                Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_reel_saved), Toast.LENGTH_SHORT).show());
                    }
                } catch (Throwable e) {
                    FeedVideoDownloadHook.mainHandler.post(() ->
                            Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_reel_failed, e.getMessage()), Toast.LENGTH_SHORT).show());
                }
            });
            return;
        }

        // No direct video — may be a photo carousel reel
        List<String> allUrls = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, media);
        if (allUrls.isEmpty()) {
            Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_reel_url_not_found), Toast.LENGTH_SHORT).show();
            return;
        }

        // Primary: live view state (always correct). Fallback: data-layer field (stale at slide 0).
        int viewIndex    = findCarouselIndexFromView(ctx, allUrls.size());
        int currentIndex = viewIndex >= 0 ? viewIndex : findReelCarouselIndex(controller);

        final String finalUsername = username;
        final String finalMediaId  = mediaId;
        final int    finalIndex    = currentIndex;
        FeedVideoDownloadHook.mainHandler.post(() ->
                FeedVideoDownloadHook.showPostDownloadDialog(ctx, allUrls, finalUsername, finalMediaId, finalIndex));
    }

    /** Reads the icon drawable ID from MediaOption$Option.DOWNLOAD enum value. */
    private static int resolveDownloadIcon(Context ctx) {
        try {
            Class<?> optionClass = ctx.getClassLoader()
                    .loadClass("com.instagram.feed.media.mediaoption.MediaOption$Option");
            for (Object val : (Object[]) optionClass.getMethod("values").invoke(null)) {
                if (val.toString().contains("DOWNLOAD")) {
                    Field f = val.getClass().getField("iconDrawable");
                    return (int) f.get(val);
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }
}
