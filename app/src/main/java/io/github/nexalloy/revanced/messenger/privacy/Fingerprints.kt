package io.github.nexalloy.revanced.messenger.privacy

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect

val plainReadReceiptRunnableFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name = "run"
            returnType = "void"
            paramCount = 0
            usingEqStrings(listOf("android_messaging_mark_read_start"))
        }
    }.single()
}

val encryptedReadReceiptFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            usingEqStrings(listOf("markAsReadThreadWithThreadIdentifier"))
        }
    }.single()
}

val plainTypingRunnablesFingerprint = findMethodListDirect {
    findMethod {
        matcher {
            name = "run"
            returnType = "void"
            paramCount = 0
            usingEqStrings(listOf("/t_st"))
        }
    }
}

val encryptedTypingFingerprint = findMethodDirect {
    findMethod {
        matcher {
            paramTypes("java.lang.String", "boolean")
            usingEqStrings(listOf("setTypingIndicatorForThreadWithThreadIdentifier"))
        }
    }.single()
}

val montageMarkReadHandlerFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramTypes("com.facebook.messaging.montage.model.MontageCard", "boolean")
            usingEqStrings(listOf("MontageMsysMarkReadHandler"))
        }
    }.single()
}

internal const val FB_USER_SESSION = "com.facebook.auth.usersession.FbUserSession"
internal const val THREAD_KEY = "com.facebook.messaging.model.threadkey.ThreadKey"
private const val SEND_MESSAGE_EVENT = "com.facebook.xapp.messaging.composer.send.event.SendMessageEvent"

val chatSeenBroadcastFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramTypes(FB_USER_SESSION, null, "java.lang.String")
            usingEqStrings("MARK_THREAD_AS_SEEN_JOB", "broadcast_message_seen_internal")
        }
    }.single { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.paramTypeNames[1] == it.declaredClassName }
}

val replyDispatchFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramCount = 15
            usingEqStrings("AdapterSender", "meta_ai_invocation")
        }
    }.single {
        java.lang.reflect.Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames[0] == FB_USER_SESSION && it.paramTypeNames[11] == SEND_MESSAGE_EVENT
    }
}

private const val LAT_STATUS_JOB = "com.facebook.attribution.LatStatusJob"

val attributionUploadFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass(LAT_STATUS_JOB)
            returnType = "void"
            paramTypes(LAT_STATUS_JOB, FB_USER_SESSION)
            usingEqStrings(
                "Failure while creating and sending attribution state through AttributionIdUpdate.",
                "com.google.android.gms.ads.identifier.internal.IAdvertisingIdService",
            )
        }
    }.single()
}

val adEntryEventFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramTypes(null, FB_USER_SESSION, "java.lang.String", "java.lang.String", "java.lang.String", "java.lang.String")
            usingEqStrings("ad_id", "page_id")
        }
    }.single { java.lang.reflect.Modifier.isStatic(it.modifiers) }
}

val inboxVisibilityTrackerFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramTypes(null, "boolean")
            usingEqStrings("InboxCustomizedImpressionTracker", "inbox2_vr")
        }
    }.single { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
}

val inboxVisibilityEventInitFingerprint = findMethodDirect {
    val code = inboxVisibilityTrackerFingerprint().instructions
    val name = code.indexOfFirst { it.string == "inbox2_vr" }
    code.drop(name + 1).first { it.opcode == OP_INVOKE_DIRECT }.methodRef!!
        .also { check(it.isConstructor && it.paramTypeNames == listOf("java.lang.String")) }
}

val inboxVisibilityLogFingerprint = findMethodDirect {
    val event = inboxVisibilityEventInitFingerprint().declaredClassName
    val code = inboxVisibilityTrackerFingerprint().instructions
    val name = code.indexOfFirst { it.string == "inbox2_vr" }
    code.drop(name + 1).first {
        it.opcode == OP_INVOKE_VIRTUAL && it.methodRef?.let { m ->
            m.returnTypeName == "void" && m.paramTypeNames == listOf(event)
        } == true
    }.methodRef!!
}

private const val OP_INVOKE_VIRTUAL = 0x6e
private const val OP_INVOKE_DIRECT = 0x70

internal const val GOOGLE_PLAY_UPLOAD_SERVICE = "com.facebook.analytics2.logger.GooglePlayUploadService"

val boundUploadTaskFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name = "run"
            returnType = "void"
            paramCount = 0
            usingEqStrings("Job with old build ID: %d, cancelling job, expected: %d")
        }
    }.single { m -> m.instructions.any { it.classRef?.name == GOOGLE_PLAY_UPLOAD_SERVICE } }
}

val xAnalyticsUploadTimerFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name = "run"
            returnType = "void"
            paramCount = 0
            addInvoke { declaredClass("com.facebook.xanalytics.XAnalyticsHolder"); name = "flush" }
            addInvoke { declaredClass("com.facebook.xanalytics.XAnalyticsNative"); name = "kickOffUpload" }
        }
    }.single()
}

val uploadActionDispatcherFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramCount = 3
            usingStrings("com.facebook.analytics2.logger.UPLOAD_NOW", "com.facebook.analytics2.logger.USER_LOGOUT", "Unknown action=")
        }
    }.single { !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.paramTypeNames[2] == "java.lang.String" }
}

val inProcessUploadStartFingerprint = findMethodDirect {
    val dispatcher = uploadActionDispatcherFingerprint()
    findMethod {
        matcher {
            declaredClass(dispatcher.declaredClassName)
            returnType = "void"
            paramTypes(dispatcher.paramTypeNames[0], "java.lang.String")
            addInvoke(dispatcher.descriptor)
        }
    }.single { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
}
