package io.github.nexalloy.morphe.tiktok.activestatus

import io.github.nexalloy.morphe.findFieldListDirect
import io.github.nexalloy.morphe.findMethodDirect

internal const val REPORT_PATH = "/tiktok/v1/activity_status/report"
internal const val FETCH_PATH = "/tiktok/v1/activity_status/fetch"
internal const val FETCH_AS_REPORT = "fetch_as_report"

val activityStatusReportFingerprint = findMethodDirect {
    findMethod { matcher { addAnnotation { usingStrings(REPORT_PATH) } } }.single()
}

val activityStatusFetchFingerprint = findMethodDirect {
    findMethod { matcher { addAnnotation { usingStrings(FETCH_PATH) } } }.single()
}

val activityStatusApiHoldersFingerprint = findFieldListDirect {
    val api = activityStatusReportFingerprint().declaredClassName
    check(activityStatusFetchFingerprint().declaredClassName == api) { "report and fetch live in different APIs" }
    findField { matcher { type = api } }.also { check(it.isNotEmpty()) { "no holder of $api" } }
}
