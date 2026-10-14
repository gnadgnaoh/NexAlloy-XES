package io.github.nexalloy.revanced.zalo.media

import io.github.nexalloy.patch

val KeepExpiredMediaAccessible = patch(
    name = "Keep expired media accessible",
    description = "Keeps large chat files that are still stored on the device usable after " +
        "Zalo marks them expired, instead of showing the Z Cloud / expired screen. It does " +
        "not restore missing files or bypass server download limits.",
) {
    val stateEnum = ::chatMediaStateEnumFingerprint.clazz
    val constants = stateEnum.enumConstants.orEmpty().associateBy { (it as Enum<*>).name }
    val expired = constants[BIG_FILE_EXPIRED] ?: error("$BIG_FILE_EXPIRED missing in ${stateEnum.name}")
    val notExpired = constants[BIG_FILE_NOT_EXPIRED]
        ?: error("$BIG_FILE_NOT_EXPIRED missing in ${stateEnum.name}")

    ::chatMediaStateClassifierFingerprint.hookMethod {
        after { param ->
            if (param.result === expired) param.result = notExpired
        }
    }
}
