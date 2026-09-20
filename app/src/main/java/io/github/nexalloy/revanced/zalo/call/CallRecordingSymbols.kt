package io.github.nexalloy.revanced.zalo.calls

/**
 * Obfuscated Zalo symbols used only by the *fallback* recording paths.
 *
 * The primary capture path (PeerJNI native methods + the non-obfuscated
 * `com.vng.zing.vn.zrtc.CallCallback` base class + peer-termination hooks) needs
 * NONE of these — it works from stable, non-obfuscated names. These symbols only
 * improve robustness:
 *
 *  - [PEER_MANAGER_CLASS] / [PEER_MANAGER_INSTANCE_METHOD] / [PEER_CONTAINER_FIELD]
 *    / [PEER_HANDLE_FIELD] let a live callback re-bind to the current native peer
 *    handle when the Session map misses (see `resolveCurrentSession`).
 *  - [CURRENT_CALLBACK_CLASS] is the concrete `CallCallback` subclass, hooked in
 *    addition to the base class.
 *  - [ACTIVITY_READY_METHOD] / [ACTIVITY_CALL_STATE_FIELD] / [ACTIVITY_CONNECTED_METHOD]
 *    drive the `ZmInCallActivity` "controls ready" secondary start trigger.
 *
 * Values below are the verified names for Zalo 26.08.02 (versionCode 260802903),
 * taken from the Zalo Patch symbol schema. They are obfuscated and WILL change
 * between Zalo releases. When they no longer resolve, the fallback hooks are
 * skipped silently and the primary path still records; update them (or replace
 * this table with DexKit fingerprints) for a new build. See PORTING.md.
 */
internal object CallRecordingSymbols {
    // Non-obfuscated, stable across builds.
    const val PEER_JNI = "com.vng.zing.vn.zrtc.PeerJNI"
    const val CALL_CALLBACK = "com.vng.zing.vn.zrtc.CallCallback"
    val CALL_ACTIVITIES = arrayOf("zm.voip.ui.incall.ZmInCallActivity")

    // Obfuscated — pinned to Zalo 26.08.02 (260802903).
    const val PEER_MANAGER_CLASS = "zh.a"
    const val PEER_MANAGER_INSTANCE_METHOD = "a"
    const val PEER_CONTAINER_FIELD = "a"
    const val PEER_HANDLE_FIELD = "e"

    const val CURRENT_CALLBACK_CLASS = "l92.z1"

    const val ACTIVITY_READY_METHOD = "U1"
    const val ACTIVITY_CALL_STATE_FIELD = "K2"
    const val ACTIVITY_CONNECTED_METHOD = "n"
}
