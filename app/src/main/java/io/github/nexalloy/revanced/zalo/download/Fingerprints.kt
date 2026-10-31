package io.github.nexalloy.revanced.zalo.download

import io.github.nexalloy.morphe.findMethodDirect
import java.lang.reflect.Modifier

private const val STORY_MENU_DATA = "dataStoryViewFull"
private const val MENU_VIEW = "com.zing.zalo.social.features.menu_feed.BottomSheetMenuView"

val storyMenuBuilderFingerprint = findMethodDirect {
    findMethod { matcher { usingStrings(STORY_MENU_DATA); returnType = "java.util.List" } }.single()
}

val menuItemFactoryFingerprint = findMethodDirect {
    storyMenuBuilderFingerprint().invokes.filter { m ->
        m.isMethod && Modifier.isStatic(m.modifiers) && m.paramCount == 10 && m.returnTypeName != "void" &&
            "android.graphics.drawable.Drawable" in m.paramTypeNames
    }.distinctBy { it.descriptor }.single()
}

val menuItemClickFingerprint = findMethodDirect {
    val item = menuItemFactoryFingerprint().returnTypeName
    findMethod { matcher { declaredClass(MENU_VIEW); paramTypes(item); returnType = "void" } }.single()
}

val storyJsonFingerprint = findMethodDirect {
    findMethod {
        matcher { usingStrings("urlHls", "serverFileId", "delayTime"); returnType = "org.json.JSONObject"; paramCount = 0 }
    }.single { !Modifier.isStatic(it.modifiers) }
}

const val PHOTO_DOWNLOAD_ID = 0x17
const val VIDEO_DOWNLOAD_ID = 0x1c

val viewfullMenuFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass(MENU_VIEW)
            paramTypes("android.os.Bundle")
            returnType = "void"
            usingStrings("EXTRA_BUNDLE_DATA", "hiddenAreaHeight")
            usingNumbers(PHOTO_DOWNLOAD_ID, VIDEO_DOWNLOAD_ID)
        }
    }.single()
}

val feedJsonFingerprint = findMethodDirect {
    findMethod {
        matcher { usingStrings("fid", "ftype", "feed_title", "content"); returnType = "org.json.JSONObject"; paramCount = 0 }
    }.single { !Modifier.isStatic(it.modifiers) }
}

