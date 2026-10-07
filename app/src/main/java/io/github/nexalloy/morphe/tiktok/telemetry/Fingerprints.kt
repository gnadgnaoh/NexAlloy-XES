package io.github.nexalloy.morphe.tiktok.telemetry

import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.tiktok.shared.cacheable
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

internal const val APP_LOG_CLASS = "com.bytedance.applog.AppLog"
internal const val LEGACY_APP_LOG_CLASS = "com.ss.android.common.applog.AppLog"
internal const val APP_LOG_PRIORITY_CALLBACK_CLASS = "com.bytedance.applog.priority.PriorityCallbackImpl"
internal const val APP_LOG_PRIORITY_RESPONSE_CLASS = "com.bytedance.applog.priority.PriorityHttpResponse"
internal const val APPS_FLYER_LIB_CLASS = "com.appsflyer.AppsFlyerLib"
internal const val FIREBASE_ANALYTICS_CLASS = "com.google.firebase.analytics.FirebaseAnalytics"
internal const val MONITOR_CRASH_CLASS = "com.bytedance.crash.MonitorCrash"

internal val APP_LOG_ENTRY_POINTS = setOf("onEvent", "onEventV3", "onMiscEvent", "flush", "flushAsync", "onActivityPause")

internal val LEGACY_APP_LOG_ENTRY_POINTS = setOf("onEvent", "onEventV3", "onMiscEvent", "sendEvent")

internal val TELEMETRY_STARTUP_TASKS = listOf(
    "com.ss.android.ugc.aweme.legoImp.task.NpthCoreInitTask",
    "com.ss.android.ugc.aweme.legoImp.task.NpthSecondInitTask",
    "com.ss.android.ugc.aweme.legoImp.task.InitAppsFlyer",
    "com.ss.android.ugc.aweme.legoImp.task.InitAppsFlyerHolder\$Background",
    "com.ss.android.ugc.aweme.legoImp.task.InitAppsFlyerHolder\$Main",
)

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
