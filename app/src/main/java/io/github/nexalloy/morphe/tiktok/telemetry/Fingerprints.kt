package io.github.nexalloy.morphe.tiktok.telemetry

import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.tiktok.shared.cacheable
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

/*
 * Ported from HushFeed "Disable telemetry" (itself forked from ReVanced, with Npth coverage from
 * kveld) and kveld "Unified Telemetry & Tracker Silencer" (GPL-3.0).
 *
 * The SDK facades (AppLog, AppsFlyer, Firebase, MonitorCrash, Npth/AppsFlyer startup tasks) keep
 * their names and are looked up by name in the patch. Only the three senders below are R8-named;
 * Hush pins two of them by parameter count and parameter position, which is dropped here: each
 * one is already unique on both builds by its log/protocol strings and its return type alone.
 * Verified with DexKit on TikTok Asia 47.0.3 and Global 47.1.4 (base + df_a_dex split).
 */

internal const val APP_LOG_CLASS = "com.bytedance.applog.AppLog"
internal const val LEGACY_APP_LOG_CLASS = "com.ss.android.common.applog.AppLog"
internal const val APP_LOG_PRIORITY_CALLBACK_CLASS = "com.bytedance.applog.priority.PriorityCallbackImpl"
internal const val APP_LOG_PRIORITY_RESPONSE_CLASS = "com.bytedance.applog.priority.PriorityHttpResponse"
internal const val APPS_FLYER_LIB_CLASS = "com.appsflyer.AppsFlyerLib"
internal const val FIREBASE_ANALYTICS_CLASS = "com.google.firebase.analytics.FirebaseAnalytics"
internal const val MONITOR_CRASH_CLASS = "com.bytedance.crash.MonitorCrash"

/** Static void entry points of the AppLog facade, every overload. */
internal val APP_LOG_ENTRY_POINTS = setOf("onEvent", "onEventV3", "onMiscEvent", "flush", "flushAsync", "onActivityPause")

/** The same on TikTok's older AppLog wrapper (kveld). */
internal val LEGACY_APP_LOG_ENTRY_POINTS = setOf("onEvent", "onEventV3", "onMiscEvent", "sendEvent")

/** Lego startup tasks that start the crash/ANR reporter and AppsFlyer. */
internal val TELEMETRY_STARTUP_TASKS = listOf(
    "com.ss.android.ugc.aweme.legoImp.task.NpthCoreInitTask",
    "com.ss.android.ugc.aweme.legoImp.task.NpthSecondInitTask",
    "com.ss.android.ugc.aweme.legoImp.task.InitAppsFlyer",
    "com.ss.android.ugc.aweme.legoImp.task.InitAppsFlyerHolder\$Background",
    "com.ss.android.ugc.aweme.legoImp.task.InitAppsFlyerHolder\$Main",
)

/**
 * The AppLog pack send: hands a pack to the log host and returns the HTTP status, which the pack
 * worker and the real-time sender read as "delivered" on 200 (LX/03R3.LJFF on Asia 47.0.3). The
 * response magic tag and the forward header only meet in here and in one JSONObject builder.
 */
val appLogPackSendFingerprints = findMethodListDirect {
    cacheable {
        findMethod {
            matcher {
                usingStrings(listOf("ss_app_log", "applog_forward"), StringMatchType.Equals)
                returnType = "int"
            }
        }
    }
}

/**
 * The AppLog forward send: deletes the rows flagged for forwarding and posts them through the SDK's
 * network client, never through the pack send (LX/0Aom.LJII on Asia 47.0.3). Its own log line.
 */
val appLogForwardSendFingerprints = findMethodListDirect {
    cacheable {
        findMethod {
            matcher {
                usingStrings(listOf("trySendForward start requestId={}, url={}"), StringMatchType.Equals)
                returnType = "void"
            }
        }
    }
}

/**
 * The install SDK's activation check: one fetch of the log host's app_alert_check path per start,
 * with the advertising id, carrier, SIM region and time zone in the query, answering whether the
 * reply said success (static, LX/0fwb.LIZ on Asia 47.0.3). Device registration is a different job.
 */
val installActiveCheckFingerprints = findMethodListDirect {
    cacheable {
        findMethod {
            matcher {
                usingStrings(listOf("Register#active http error = "), StringMatchType.Equals)
                returnType = "boolean"
                modifiers(Modifier.STATIC)
            }
        }
    }
}

/**
 * AppsFlyerLib declares its API abstract; the SDK's own implementation (com.appsflyer.internal.*,
 * renamed by AppsFlyer's obfuscator per SDK version) is the one to silence.
 */
val appsFlyerEventFingerprints = findMethodListDirect {
    cacheable {
        listOf("logEvent", "logLocation").flatMap { method ->
            findMethod {
                matcher {
                    declaredClass { superClass(APPS_FLYER_LIB_CLASS) }
                    name = method
                    returnType = "void"
                }
            }
        }
    }
}
