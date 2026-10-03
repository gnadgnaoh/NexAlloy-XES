package io.github.nexalloy.morphe.tiktok.externalbrowser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.morphe.extension.shared.Logger
import io.github.nexalloy.callMethodOrNull
import io.github.nexalloy.findClassOrNull
import io.github.nexalloy.getObjectFieldOrNull
import io.github.nexalloy.hookMethod
import io.github.nexalloy.morphe.tiktok.shared.TikTokServices
import io.github.nexalloy.morphe.tiktok.shared.realMatches
import io.github.nexalloy.patch

private const val TAG = "[TikTok external links]"

/** InteractStickerStruct.getType() of a story's website link sticker. */
private const val LINK_STICKER_TYPE = 106

/*
 * Ported from HushFeed "Open external links directly" (GPL-3.0). See externalbrowser/Fingerprints.kt.
 *
 * Left out on purpose:
 *  - HushFeed's SparkActivity.onCreate fallback: it decides by SparkContext.seclinkConfig /
 *    defaultParams, and on 47.x SparkContext has neither field (only url and containerId are
 *    kept), so it can never fire on these builds.
 *  - kveld's AnchorInfoStruct.getOpenSystemBrowser -> true: its only reader is the ad landing
 *    page opener (AdOpenBaseUtils), and ads are removed by the ad patches anyway.
 *  - kveld's redirect of *every* SparkThird page: only links that went through TikTok's
 *    external-link check (a seclink scene, today the profile bio) are sent out, so logins,
 *    payments and partner pages that need the in-app container keep working.
 */
val OpenLinksInBrowser = patch(
    name = "Open external links in browser",
    description = "Opens website links from profiles and story link stickers in your phone's " +
        "browser instead of TikTok's in-app browser.",
) {
    TikTokServices.init(classLoader)

    val installed = mutableListOf<String>()
    val skipped = mutableListOf<String>()

    fun optional(label: String, block: () -> Unit) {
        runCatching(block)
            .onSuccess { installed += label }
            .onFailure { skipped += "$label (${it.javaClass.simpleName}: ${it.message})" }
    }

    optional("bioLinks") {
        val thirdContext = SPARK_THIRD_CONTEXT_CLASS.findClassOrNull(classLoader) ?: error("$SPARK_THIRD_CONTEXT_CLASS not found")
        val routers = ::sparkThirdRouterOpenFingerprints.dexMethodList.realMatches()
        check(routers.isNotEmpty()) { "SparkThird router not found" }
        routers.forEach { router ->
            router.toMethod().hookMethod {
                before { param ->
                    val context = param.args.firstOrNull { it is Context } as? Context ?: return@before
                    val third = param.args.firstOrNull { thirdContext.isInstance(it) } ?: return@before
                    if (!ExternalBrowser.isCheckedExternalLink(third)) return@before
                    if (ExternalBrowser.open(context, third.getObjectFieldOrNull("url") as? String)) param.result = null
                }
            }
        }
    }

    optional("storyLinkStickers") {
        val sticker = INTERACT_STICKER_CLASS.findClassOrNull(classLoader) ?: error("$INTERACT_STICKER_CLASS not found")
        val sheets = ::storyLinkSheetFingerprints.dexMethodList.realMatches()
        check(sheets.isNotEmpty()) { "story link sheet not found" }
        sheets.forEach { sheet ->
            sheet.toMethod().hookMethod {
                before { param ->
                    val struct = param.args.firstOrNull { sticker.isInstance(it) } ?: return@before
                    if (struct.callMethodOrNull("getType") != LINK_STICKER_TYPE) return@before
                    val url = struct.callMethodOrNull("getUrlLinkSticker")?.callMethodOrNull("getFullURL") as? String
                    val context = ExternalBrowser.contextOf(param.thisObject) ?: appContext
                    if (ExternalBrowser.open(context, url)) param.result = null
                }
            }
        }
    }

    check(installed.isNotEmpty()) { "no link hook could be installed: ${skipped.joinToString("; ")}" }
    Logger.printInfo { "$TAG installed=[${installed.joinToString()}] skipped=[${skipped.joinToString("; ")}]" }
}

internal object ExternalBrowser {

    /** A SparkThirdContext whose URL went through TikTok's external-link check (bio_url today). */
    fun isCheckedExternalLink(third: Any): Boolean {
        val scene = third.getObjectFieldOrNull("seclinkConfig")?.callMethodOrNull("getScene") as? String
        if (!scene.isNullOrBlank()) return true
        val params = third.getObjectFieldOrNull("defaultParams") as? Map<*, *> ?: return false
        return !(params["sec_link_scene"] as? String).isNullOrBlank()
    }

    /** The first Context held by [owner] or one of its superclasses (a component's host). */
    fun contextOf(owner: Any?): Context? {
        var type: Class<*>? = owner?.javaClass ?: return null
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (!Context::class.java.isAssignableFrom(field.type)) continue
                val value = runCatching { field.isAccessible = true; field.get(owner) }.getOrNull()
                if (value is Context) return value
            }
            type = type.superclass
        }
        return null
    }

    /**
     * Opens [source] in the system browser: through TikTok's own IMainService.openSystemBrowser
     * first, then a plain browser intent. False leaves TikTok's in-app browser to open it.
     */
    fun open(context: Context, source: String?): Boolean {
        val target = LinkTarget.resolve(source) ?: return false
        if (TikTokServices.openSystemBrowser(context, target)) return true

        val view = Intent(Intent.ACTION_VIEW, Uri.parse(target)).addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is Activity) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val toBrowser = Intent(view).apply {
            selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        }
        return listOf(toBrowser, view).any { intent ->
            runCatching { context.startActivity(intent) }
                .onFailure { Logger.printDebug { "$TAG browser intent failed: ${it.message}" } }
                .isSuccess
        }
    }
}
