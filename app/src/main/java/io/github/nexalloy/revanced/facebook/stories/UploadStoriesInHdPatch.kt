package io.github.nexalloy.revanced.facebook.stories

import android.os.BaseBundle
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.shared.hook.ScopedFrameworkHook

val UploadStoriesInHd = patch(
    name = "Upload Stories in HD",
    description = "Story videos are uploaded with Facebook's high quality setting instead of standard.",
) {
    val putString = BaseBundle::class.java.getDeclaredMethod("putString", String::class.java, String::class.java)
    ScopedFrameworkHook(putString, before = { param ->
        if (param.args[0] == VIDEO_UPLOAD_QUALITY) param.args[1] = "high"
    }).installOn(::uploadOperationForCreateFingerprint.method)
}
