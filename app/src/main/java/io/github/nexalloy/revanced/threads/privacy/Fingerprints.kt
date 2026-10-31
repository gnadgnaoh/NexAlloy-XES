package io.github.nexalloy.revanced.threads.privacy

import io.github.nexalloy.morphe.findMethodDirect
import java.lang.reflect.Modifier

val screenshotLibraryObserverFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name = "onChange"
            returnType = "void"
            paramTypes("boolean", "android.net.Uri")
            usingEqStrings("'%screenshot%'")
            declaredClass { superClass("android.database.ContentObserver") }
        }
    }.single()
}

val screenshotFolderReportFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            usingEqStrings("Reporting screenshot: %s -> %s", "ig_android_screenshot_path_parse_fail")
        }
    }.single { Modifier.isStatic(it.modifiers) && it.paramTypeNames.drop(1) == listOf("java.lang.String", "java.util.List") }
}
