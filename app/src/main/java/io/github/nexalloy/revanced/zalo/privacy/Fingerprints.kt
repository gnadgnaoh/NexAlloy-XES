package io.github.nexalloy.revanced.zalo.privacy

import io.github.nexalloy.morphe.fingerprint

val seenAckSocketFingerprint = fingerprint {
    strings("ackMsgList", "seen", "data")
    returns("V")
}

val seenAckRepositoryFingerprint = fingerprint {
    strings("ACK receive msg | ", " | seen=")
    returns("V")
}

val typingSocketFingerprint = fingerprint {
    classMatcher { usingStrings("ackMsgList", "seen", "data") }
    parameters("Ljava/lang/String;", "I", "Z", "Z")
    returns("V")
}

val typingRepositoryFingerprint = fingerprint {
    classMatcher { usingStrings("ACK receive msg | ") }
    parameters("Ljava/lang/String;", "I", "Z", "Z")
    returns("V")
}
