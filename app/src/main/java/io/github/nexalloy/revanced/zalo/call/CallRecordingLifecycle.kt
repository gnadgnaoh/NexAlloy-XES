package io.github.nexalloy.revanced.zalo.calls

/**
 * Stable audio-recording decisions derived from ZRTC call callbacks.
 *
 * Direct Kotlin port of the Zalo Patch `CallRecordingLifecycle`. The method
 * names below are ZRTC `CallCallback` callback names and are NOT obfuscated, so
 * they carry across Zalo builds unchanged.
 */
internal object CallRecordingLifecycle {
    const val CONNECTED_AUDIO_STATE = 32
    const val TERMINAL_CALL_STATE = 6

    fun observes(methodName: String): Boolean = when (methodName) {
        "onIncomingCall", "onMakeCall", "onCallConfirmed", "onPreConnectSuccessful",
        "onCallAudioState", "onCallVideoState", "onCallState", "onCallEnd",
        "onCallErr", "onCallAutoHangup" -> true
        else -> false
    }

    fun beginsCall(methodName: String): Boolean =
        methodName == "onIncomingCall" || methodName == "onMakeCall"

    // Current ZRTC callback has no onCallConfirmed method. Its confirmed-call edge
    // is onPreConnectSuccessful; the older name is retained for versions that still
    // expose it.
    fun confirmsCall(methodName: String): Boolean =
        methodName == "onCallConfirmed" || methodName == "onPreConnectSuccessful"

    fun connectsAudio(methodName: String, state: Int): Boolean =
        methodName == "onCallAudioState" && state == CONNECTED_AUDIO_STATE

    fun shouldStartAudio(confirmed: Boolean, audioConnected: Boolean): Boolean =
        confirmed && audioConnected

    fun shouldStopAudio(methodName: String, state: Int): Boolean =
        methodName == "onCallEnd" ||
            methodName == "onCallErr" ||
            methodName == "onCallAutoHangup" ||
            (methodName == "onCallState" && state == TERMINAL_CALL_STATE)

    fun isVideoState(methodName: String): Boolean = methodName == "onCallVideoState"
}
