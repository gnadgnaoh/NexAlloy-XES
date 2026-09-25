package io.github.nexalloy.revanced.zalo.backup

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.zalo.ads.remoteConfigIntGetterFingerprint

private const val BACKUP_MEDIA_MODE_KEY = "features@backup_media@mode"
private const val MODE_AVAILABLE_TO_ALL = 1

val EnableDrivePhotoBackup = patch(
    name = "Always allow Google Drive photo backup",
    description = "Keeps the \"Photos – backed up on Google Drive\" option available even after " +
        "your old backup was deleted. Zalo otherwise only offers it to accounts whose existing " +
        "backup already used Drive.",
) {
    ::remoteConfigIntGetterFingerprint.hookMethod {
        before { param ->
            if (param.args.getOrNull(0) == BACKUP_MEDIA_MODE_KEY) param.result = MODE_AVAILABLE_TO_ALL
        }
    }
}
