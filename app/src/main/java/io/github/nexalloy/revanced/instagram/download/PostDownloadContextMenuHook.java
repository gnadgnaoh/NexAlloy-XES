package io.github.nexalloy.revanced.instagram.download;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.widget.Toast;


import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import io.github.nexalloy.R;

/**
 * Injects a "Download" entry into the feed-post three-dots (⋮) menu.
 *
 * Hook A — static add-button method on MediaOptionsOverflowMenuCreator
 *   Found via DexKit: findClass("MediaOptionsOverflowMenuCreator") → findMethod(declaredClass, void).
 *   Hooks every addButton call; injects our Download entry once per menu popup.
 *
 * Hook B — options click handler
 *   Found via DexKit: void method with sole param MediaOption$Option (stable, unobfuscated type).
 *   Fires on every option tap; we handle DOWNLOAD and trigger the download.
 *
 * Carousel index: read from the first int field on thisObject whose value fits [0, urlCount).
 *   Tries a known field name first (fast path), then falls back to scanning all int fields
 *   so a rename in a future build doesn't break it.
 */
public class PostDownloadContextMenuHook {



    // ── Resolved at install time ──────────────────────────────────────────────

    // com.instagram.feed.media.mediaoption.MediaOption$Option — stable public enum
    private static Class<?> mediaOptionEnumClass;
    private static Object   downloadOptionValue;     // MediaOption$Option.DOWNLOAD
    private static Object   copyLinkOptionValue;     // MediaOption$Option.COPY_LINK (#117)

    // Obfuscated creator class found via "MediaOptionsOverflowMenuCreator" string
    private static Class<?> menuCreatorClass;

    // Static "add one button to list" method on menuCreatorClass — no hardcoded name
    private static Method   addButtonMethod;
    private static Object   enumNormalValue;

    // Param indices — resolved once when addButtonMethod is found
    private static int idxEnum   = 0;
    private static int idxOption = 1;
    private static int idxSelf   = 2;
    private static int idxText   = 3;
    private static int idxList   = 4;

    // ── Guards ────────────────────────────────────────────────────────────────

    private static final ThreadLocal<Boolean> sAddingDownload =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final Set<Object> processedCreators =
            Collections.newSetFromMap(new WeakHashMap<>());

    // ── Entry point ──────────────────────────────────────────────────────────

    // ── Step 1: MediaOption$Option.DOWNLOAD ──────────────────────────────────

    public static void loadMediaOptionEnum(ClassLoader cl) {
        try {
            mediaOptionEnumClass = cl.loadClass(
                    "com.instagram.feed.media.mediaoption.MediaOption$Option");
            Object[] values = (Object[]) mediaOptionEnumClass.getMethod("values").invoke(null);
            for (Object v : values) {
                String name = v.toString();
                if (downloadOptionValue == null && name.equals("DOWNLOAD")) {
                    downloadOptionValue = v;
                } else if (copyLinkOptionValue == null && name.equals("COPY_LINK")) {
                    copyLinkOptionValue = v;
                }
            }
            if (downloadOptionValue == null)
                ModuleLog.line("(NA|Post) ❌ DOWNLOAD enum value not found");
            if (copyLinkOptionValue == null)
                ModuleLog.line("(NA|Post) ❌ COPY_LINK enum value not found");
        } catch (Throwable t) {
            ModuleLog.line("(NA|Post) ❌ loadMediaOptionEnum: " + t);
        }
    }

    // ── Step 2: pick the add-button method out of the menu creator's void methods ─
    //
    // The creator class is located by string and its void methods listed by the patch. The
    // right one is static and takes the option enum plus the ArrayList to add to; the other
    // parameters (button-type enum, the creator itself, the label) are identified by type so
    // the indices survive a reordering of the signature.

    /**
     * Selects the add-button method from {@code candidates} and records the parameter layout.
     *
     * @return the method the patch should hook, or {@code null} when none of the candidates fit
     */
    public static Method resolveAddButtonMethod(List<Method> candidates) {
        for (Method m : candidates) {
            try {
                if (!Modifier.isStatic(m.getModifiers())) continue;

                Class<?>[] p = m.getParameterTypes();
                if (p.length < 4) continue;

                int eIdx = -1, oIdx = -1, sIdx = -1, tIdx = -1, lIdx = -1;
                for (int i = 0; i < p.length; i++) {
                    if (mediaOptionEnumClass != null && p[i] == mediaOptionEnumClass) {
                        oIdx = i;
                    } else if (ArrayList.class.isAssignableFrom(p[i])) {
                        lIdx = i;
                    } else if (p[i] == m.getDeclaringClass()) {
                        sIdx = i;
                    } else if (CharSequence.class.isAssignableFrom(p[i])) {
                        tIdx = i;
                    } else if (p[i].isEnum() && eIdx < 0 && oIdx < 0) {
                        eIdx = i;
                    }
                }

                if (oIdx < 0 || lIdx < 0) continue;

                addButtonMethod = m;
                addButtonMethod.setAccessible(true);
                menuCreatorClass = m.getDeclaringClass();
                idxEnum   = eIdx >= 0 ? eIdx : 0;
                idxOption = oIdx;
                idxSelf   = sIdx >= 0 ? sIdx : 2;
                idxText   = tIdx >= 0 ? tIdx : 3;
                idxList   = lIdx;
                resolveNormalButtonType();
                ModuleLog.line("(NA|Post) add-button: " + m.getDeclaringClass().getName()
                        + "." + m.getName());
                return addButtonMethod;
            } catch (Throwable ignored) {}
        }
        ModuleLog.line("(NA|Post) \u274c add-button method not found among "
                + candidates.size() + " candidate(s)");
        return null;
    }

    /** Picks the "normal" value of the button-type enum, falling back to "action", then first. */
    private static void resolveNormalButtonType() {
        try {
            Class<?> btnTypeEnumClass = addButtonMethod.getParameterTypes()[idxEnum];
            Object[] btnVals = (Object[]) btnTypeEnumClass.getMethod("values").invoke(null);
            Object firstVal = null;
            for (Object v : btnVals) {
                if (firstVal == null) firstVal = v;
                if (enumNormalValue == null && v.toString().equalsIgnoreCase("normal")) {
                    enumNormalValue = v;
                }
            }
            if (enumNormalValue == null) {
                for (Object v : btnVals) {
                    if (v.toString().equalsIgnoreCase("action")) { enumNormalValue = v; break; }
                }
            }
            if (enumNormalValue == null) enumNormalValue = firstVal;
        } catch (Throwable ignored) {}
    }

    /** True once {@link #resolveAddButtonMethod} found everything the injection needs. */
    public static boolean canInjectRows() {
        boolean ready = addButtonMethod != null && enumNormalValue != null
                && (downloadOptionValue != null || copyLinkOptionValue != null);
        if (!ready) ModuleLog.line("(NA|Post) \u274c cannot inject rows \u2014 prerequisites missing");
        return ready;
    }

    // ── Hook A: intercept every addButton call, inject Download once per menu ─

    /** Suppresses Instagram's own Download / Copy link rows; ours are injected instead. */
    public static void onAddButtonBefore(XC_MethodHook.MethodHookParam param) {
        if (Boolean.TRUE.equals(sAddingDownload.get())) return;
        Object opt = param.args[idxOption];
        if (FeatureFlags.enablePostDownload && opt == downloadOptionValue) {
            param.setResult(null);
        } else if (FeatureFlags.copyMediaLink && opt == copyLinkOptionValue) {
            param.setResult(null);
        }
    }

    /** Injects our rows once per menu, on the first option the menu adds. */
    public static void onAddButtonAfter(XC_MethodHook.MethodHookParam param) {
        if (Boolean.TRUE.equals(sAddingDownload.get())) return;

        boolean wantDownload = FeatureFlags.enablePostDownload && downloadOptionValue != null;
        boolean wantCopyLink = FeatureFlags.copyMediaLink && copyLinkOptionValue != null;
        if (!wantDownload && !wantCopyLink) return;

        // Don't react to our own target option types (suppressed in onAddButtonBefore)
        Object opt = param.args[idxOption];
        if (opt == downloadOptionValue || opt == copyLinkOptionValue) return;

        Object self = param.args[idxSelf];
        boolean alreadyProcessed;
        synchronized (processedCreators) {
            alreadyProcessed = processedCreators.contains(self);
            if (!alreadyProcessed) processedCreators.add(self);
        }
        if (alreadyProcessed) return;

        if (wantDownload) injectRow(param, downloadOptionValue, R.string.ig_dl_title);
        if (wantCopyLink) injectRow(param, copyLinkOptionValue, R.string.ig_copy_link_title);
    }

    /** Adds one button (Download / Copy Media Link) to the menu currently being built. */
    private static void injectRow(XC_MethodHook.MethodHookParam param, Object optionValue, int labelResId) {
        Object[] callArgs = new Object[addButtonMethod.getParameterCount()];
        System.arraycopy(param.args, 0, callArgs, 0, callArgs.length);
        callArgs[idxEnum]   = enumNormalValue;
        callArgs[idxOption] = optionValue;
        callArgs[idxText]   = I18n.t(AndroidAppHelper.currentApplication(), labelResId);

        sAddingDownload.set(true);
        try {
            addButtonMethod.invoke(null, callArgs);
        } catch (Throwable t) {
            ModuleLog.line("(NA|Post) ❌ addButton invoke failed: " + t);
        } finally {
            sAddingDownload.set(false);
        }
    }

    // ── Hook B: click handler ─────────────────────────────────────────────────

    /**
     * Narrows the click-dispatcher candidates to the ones worth hooking.
     *
     * Statics are dropped, and so are private methods — those are consistently internal
     * analytics helpers that also receive the tapped option for telemetry, and hooking them
     * fired the download twice. The private exclusion only applies when a public candidate
     * exists too: on a build where the real dispatcher is private with no public sibling,
     * skipping it would break the feature outright, which is worse than the duplicate.
     */
    public static List<Method> selectClickDispatchers(List<Method> candidates) {
        List<Method> instanceMethods = new ArrayList<>();
        for (Method m : candidates) {
            if (!Modifier.isStatic(m.getModifiers())) instanceMethods.add(m);
        }
        boolean hasPublicCandidate = instanceMethods.stream()
                .anyMatch(m -> Modifier.isPublic(m.getModifiers()));

        List<Method> selected = new ArrayList<>();
        for (Method m : instanceMethods) {
            if (hasPublicCandidate && Modifier.isPrivate(m.getModifiers())) continue;
            m.setAccessible(true);
            selected.add(m);
        }
        return selected;
    }

    /** Click dispatch entry point for the patch's hook. */
    public static void onOptionClick(XC_MethodHook.MethodHookParam param) {
        if (!FeatureFlags.enablePostDownload && !FeatureFlags.copyMediaLink) return;
        onOptionClicked(param);
    }

    // ── Hook C: allowlist patch ─────────────────────────────────────────────────
    //
    // IG's newer "SimplifiedMediaOverflowBottomSheet" menu filters the button list
    // down to a hardcoded allowlist of MediaOption$Option values before rendering
    // (DOWNLOAD isn't one of them), silently dropping our injected entry even though
    // Hook A added it successfully. Found via DexKit field-usage matching — the
    // allowlist-builder method is the unique static (boolean)->List method that
    // references these three particular MediaOption$Option constants together.
    // We patch its return value to also include DOWNLOAD so our entry survives.

    /** Adds our options to the allowlist the simplified menu filters against. */
    public static void onAllowlistBuilt(XC_MethodHook.MethodHookParam param) {
        try {
            Object result = param.getResult();
            if (!(result instanceof List<?> original)) return;

            boolean addDownload = FeatureFlags.enablePostDownload && downloadOptionValue != null
                    && !original.contains(downloadOptionValue);
            boolean addCopyLink = FeatureFlags.copyMediaLink && copyLinkOptionValue != null
                    && !original.contains(copyLinkOptionValue);
            if (!addDownload && !addCopyLink) return;

            List<Object> patched = new ArrayList<>(original);
            if (addDownload) patched.add(downloadOptionValue);
            if (addCopyLink) patched.add(copyLinkOptionValue);
            param.setResult(patched);
        } catch (Throwable t) {
            ModuleLog.line("(NA|Post) \u274c allowlist patch failed: " + t);
        }
    }

    // ── Click dispatch ────────────────────────────────────────────────────────

    private static void onOptionClicked(XC_MethodHook.MethodHookParam param) {
        try {
            if (Boolean.TRUE.equals(sAddingDownload.get())) return;

            // Find MediaOption$Option argument
            Object clicked = null;
            for (Object a : param.args) {
                if (a != null && mediaOptionEnumClass != null && mediaOptionEnumClass.isInstance(a)) {
                    clicked = a; break;
                }
            }
            if (clicked == null) {
                for (Object a : param.args) {
                    if (a != null && a.getClass().isEnum()
                            && (a.toString().contains("DOWNLOAD") || a.toString().contains("COPY_LINK"))) {
                        clicked = a; break;
                    }
                }
            }
            if (clicked == null) return;

            String option = clicked.toString();
            boolean isDownload = option.equals("DOWNLOAD") && FeatureFlags.enablePostDownload;
            boolean isCopyLink = option.equals("COPY_LINK") && FeatureFlags.copyMediaLink;
            if (!isDownload && !isCopyLink) return;

            param.setResult(null); // consume the event

            Object thisObj = param.thisObject;

            Context ctx = findContext(thisObj);
            if (ctx == null) {
                ModuleLog.line("(NA|Post) ❌ Context not found in click handler");
                return;
            }

            Object media = findMediaViaMenuCreator(thisObj);
            if (media == null) media = findMedia(thisObj);
            if (media == null) {
                ModuleLog.line("(NA|Post) ❌ Media not found in click handler");
                Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_no_media_for_post), Toast.LENGTH_SHORT).show();
                return;
            }

            if (isCopyLink) triggerCopyLink(ctx, media, thisObj);
            else            triggerDownload(ctx, media, thisObj);
        } catch (Throwable t) {
            ModuleLog.line("(NA|Post) ❌ onOptionClicked: " + t);
        }
    }

    // ── Download dispatch ─────────────────────────────────────────────────────

    private static void triggerDownload(Context ctx, Object media, Object clickHandler) {
        String username = FeedVideoDownloadHook.extractUsernameFromMediaObject(media);
        if (username == null) username = "post";

        String mediaId = "0";
        try {
            Object id = media.getClass().getMethod("getId").invoke(media);
            if (id instanceof String s && !s.isEmpty()) mediaId = s;
        } catch (Throwable ignored) {}

        List<String> urls = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, media);

        // Carousel index: live view scan is primary (reads the actual visible slide, and
        // now only trusts itself when exactly one on-screen carousel matches the target
        // size — see ReelDownloadHook.findCarouselIndexFromView). Field-guessing on
        // clickHandler is the fallback for when the scan is ambiguous or finds nothing;
        // it's unreliable specifically for reels whose DOWNLOAD row was patched into the
        // shared post-style option list (see ReelDownloadHook.installReduceOptionsListPatch),
        // where clickHandler carries no real position field.
        int viewIdx = urls.size() > 1
                ? ReelDownloadHook.findCarouselIndexFromView(ctx, urls.size())
                : -1;
        final int carouselIdx = viewIdx >= 0 ? viewIdx : findCarouselIndex(clickHandler, urls.size());

        final String finalUser = username;
        final String finalId   = mediaId;
        FeedVideoDownloadHook.mainHandler.post(() ->
                FeedVideoDownloadHook.showPostDownloadDialog(ctx, urls, finalUser, finalId, carouselIdx));
    }

    // ── Copy Media Link (#117) ────────────────────────────────────────────────
    //
    // Copies the direct CDN url of the currently-visible slide to the clipboard.
    // Reuses the same URL extraction + carousel-index resolution as the downloader
    // so the copied link always matches the slide the user is looking at.

    private static void triggerCopyLink(Context ctx, Object media, Object clickHandler) {
        List<String> urls = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, media);
        if (urls == null || urls.isEmpty()) {
            Toast.makeText(ctx, I18n.t(ctx, R.string.ig_copy_link_none), Toast.LENGTH_SHORT).show();
            return;
        }
        // Single URL copies straight to clipboard; a carousel shows a per-slide chooser
        // (see showCopyLinkSheet — the visible slide can't be resolved reliably in the feed).
        FeedVideoDownloadHook.mainHandler.post(() -> FeedVideoDownloadHook.showCopyLinkSheet(ctx, urls));
    }

    /**
     * Finds the current carousel slide index from the click handler object.
     * Tries a known field name first (fast path), then scans all int fields for the
     * first value in [0, urlCount) — only the index field will be in that range.
     */
    static int findCarouselIndex(Object obj, int urlCount) {
        if (obj == null || urlCount <= 1) return 0;

        // Fast path: try the currently known field name
        try {
            Field f = obj.getClass().getDeclaredField("A00");
            f.setAccessible(true);
            int v = f.getInt(obj);
            if (v >= 0 && v < urlCount) return v;
        } catch (Throwable ignored) {}

        // Fallback: scan all int fields for a value that fits as a carousel index
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (f.getType() != int.class) continue;
            f.setAccessible(true);
            try {
                int v = f.getInt(obj);
                if (v > 0 && v < urlCount) return v; // v > 0 skips 0-value flags/uninitialised
            } catch (Throwable ignored) {}
        }

        return 0;
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    private static Context findContext(Object obj) {
        if (obj == null) return null;
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (!Context.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                try {
                    Object v = f.get(obj);
                    if (v instanceof Context c) return c;
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /**
     * Preferred media lookup: reads the click handler's own reference to the menu-creator
     * (QpF-equivalent) instance and pulls its Media field directly, instead of the generic
     * BFS in {@link #findMedia}. The generic scan can land on an unrelated Media-typed field
     * elsewhere in the object graph first (e.g. a hosting Fragment's own "created with" post
     * reference, which doesn't update as the user navigates between posts within that
     * Fragment) — the menu-creator's own Media field is always the one that was current
     * when THIS specific menu was built, so it can't go stale that way.
     */
    private static Object findMediaViaMenuCreator(Object clickHandler) {
        if (clickHandler == null || menuCreatorClass == null) return null;
        try {
            Object creator = null;
            Class<?> cls = clickHandler.getClass();
            outer:
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getType() != menuCreatorClass) continue;
                    f.setAccessible(true);
                    Object v = f.get(clickHandler);
                    if (v != null) { creator = v; break outer; }
                }
                cls = cls.getSuperclass();
            }
            if (creator == null) return null;

            Class<?> cCls = creator.getClass();
            while (cCls != null && cCls != Object.class) {
                for (Field f : cCls.getDeclaredFields()) {
                    if (f.getType().getName().equals("com.instagram.feed.media.Media")) {
                        f.setAccessible(true);
                        return f.get(creator);
                    }
                }
                cCls = cCls.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object findMedia(Object obj) {
        return findMediaDepth(obj, 0);
    }

    private static Object findMediaDepth(Object obj, int depth) {
        if (obj == null || depth > 2) return null;
        Class<?> cls = obj.getClass();
        if (cls.isPrimitive() || cls.getName().startsWith("java.") || cls.getName().startsWith("android."))
            return null;

        List<Object> nextLevel = depth < 2 ? new ArrayList<>() : null;

        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType().isPrimitive()) continue;
                f.setAccessible(true);
                try {
                    Object v = f.get(obj);
                    if (v == null) continue;
                    String name = v.getClass().getName();
                    if (name.equals("com.instagram.feed.media.Media")) return v;
                    if (nextLevel != null && !name.startsWith("java.") && !name.startsWith("android."))
                        nextLevel.add(v);
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }

        if (nextLevel != null) {
            for (Object child : nextLevel) {
                Object found = findMediaDepth(child, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }
}
