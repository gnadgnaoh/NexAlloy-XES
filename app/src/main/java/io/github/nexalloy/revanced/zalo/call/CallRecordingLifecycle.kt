package io.github.nexalloy.revanced.zalo.call

internal object CallRecordingLifecycle {
    const val CONNECTED_AUDIO_STATE = 32
    const val TERMINAL_CALL_STATE = 6
    const val CONNECTED_CALL_STATE = 5

    fun observes(methodName: String): Boolean = when (methodName) {
        "onIncomingCall", "onMakeCall", "onCallConfirmed", "onPreConnectSuccessful",
        "onCallAudioState", "onCallVideoState", "onCallState", "onCallEnd",
        "onCallErr", "onCallAutoHangup" -> true
        else -> false
    }

    fun beginsCall(methodName: String): Boolean =
        methodName == "onIncomingCall" || methodName == "onMakeCall"

    fun confirmsCall(methodName: String): Boolean =
        methodName == "onCallConfirmed" || methodName == "onPreConnectSuccessful"

    fun connectsAudio(methodName: String, state: Int): Boolean =
        methodName == "onCallAudioState" && state == CONNECTED_AUDIO_STATE

    fun connectsCall(methodName: String, state: Int): Boolean =
        methodName == "onCallState" && state == CONNECTED_CALL_STATE

    fun shouldStartAudio(confirmed: Boolean, audioConnected: Boolean): Boolean =
        confirmed && audioConnected

    fun shouldStopAudio(methodName: String, state: Int): Boolean =
        methodName == "onCallEnd" ||
            methodName == "onCallErr" ||
            methodName == "onCallAutoHangup" ||
            (methodName == "onCallState" && state == TERMINAL_CALL_STATE)

    fun isVideoState(methodName: String): Boolean = methodName == "onCallVideoState"
}
