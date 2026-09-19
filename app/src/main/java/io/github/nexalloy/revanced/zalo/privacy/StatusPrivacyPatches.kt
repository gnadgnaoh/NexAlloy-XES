package io.github.nexalloy.revanced.zalo.privacy

import app.morphe.extension.shared.Logger
import io.github.nexalloy.FindMethodFunc
import io.github.nexalloy.IHookCallback
import io.github.nexalloy.PatchExecutor
import io.github.nexalloy.patch
import kotlin.reflect.KProperty0

internal class Chokepoint(
    val label: String,
    val fingerprint: KProperty0<FindMethodFunc>,
    val block: IHookCallback,
)

internal fun PatchExecutor.installChokepoints(
    context: String,
    chokepoints: List<Chokepoint>,
) {
    val hooked = mutableListOf<String>()
    val missed = mutableListOf<String>()

    chokepoints.forEach { cp ->
        runCatching { cp.fingerprint.hookMethod { before(cp.block) } }
            .onSuccess { hooked += cp.label }
            .onFailure { missed += "${cp.label} (${it.javaClass.simpleName})" }
    }

    if (missed.isNotEmpty()) {
        Logger.printInfo { "[Zalo] $context — layers not hooked: ${missed.joinToString()}" }
    }
    Logger.printInfo { "[Zalo] $context — active layers: ${hooked.joinToString().ifEmpty { "none" }}" }

    check(hooked.isNotEmpty()) {
        "$context: no chokepoint could be hooked (${missed.joinToString()})"
    }
}

private fun blockWhenSeen(argIndex: Int): IHookCallback = { param ->
    if (param.args.getOrNull(argIndex) == true) {
        param.result = null
    }
}

private val blockAlways: IHookCallback = { param -> param.result = null }

val BlockSeenStatus = patch(
    name = "Block seen status",
    description = "Hides your read receipts (\"Đã xem\") while still sending delivered " +
        "status, so you keep receiving other people's receipts. Blocks the outbound " +
        "seen ack at the socket transport (covers direct chats and the group/batch " +
        "SendSeenManager) with the message repository as a fallback.",
    use = true,
) {
    installChokepoints(
        "Block seen status",
        listOf(
            Chokepoint("socket", ::seenAckSocketFingerprint, blockWhenSeen(1)),
            Chokepoint("repository", ::seenAckRepositoryFingerprint, blockWhenSeen(3)),
        ),
    )
}

val BlockTypingStatus = patch(
    name = "Block typing status",
    description = "Stops sending the \"Đang nhập\" (typing) indicator in individual and " +
        "group chats. Blocks the typing packet at the socket transport, with the " +
        "message repository wrapper as a fallback.",
    use = true,
) {
    installChokepoints(
        "Block typing status",
        listOf(
            Chokepoint("socket", ::typingSocketFingerprint, blockAlways),
            Chokepoint("repository", ::typingRepositoryFingerprint, blockAlways),
        ),
    )
}
