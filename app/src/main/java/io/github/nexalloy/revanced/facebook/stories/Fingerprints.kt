package io.github.nexalloy.revanced.facebook.stories

import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.query.enums.StringMatchType

internal const val VIDEO_UPLOAD_QUALITY = "video_upload_quality"

val uploadOperationForCreateFingerprint = findMethodDirect {
    val methods = findMethod {
        matcher {
            name("getUploadOperationForCreate", StringMatchType.StartsWith)
            returnType = "com.facebook.photos.upload.operation.UploadOperation"
            usingEqStrings(VIDEO_UPLOAD_QUALITY)
        }
    }.filter { it.paramTypeNames.lastOrNull() == "com.facebook.composer.publish.api.model.PublishPostParams" }
    methods.singleOrNull() ?: error("Expected 1 getUploadOperationForCreate, found ${methods.size}")
}
