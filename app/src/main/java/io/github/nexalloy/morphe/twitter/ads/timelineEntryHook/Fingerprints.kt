package io.github.nexalloy.morphe.twitter.ads.timelineEntryHook

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

internal object PromotedMetadataToStringFingerprint : Fingerprint(
    name = "toString",
    strings = listOf("TimelinePromotedMetadata(impressionId="),
)

internal object ClientEventInfoToStringFingerprint : Fingerprint(
    name = "toString",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    strings = listOf("ClientEventInfo(component="),
)

private const val DATABASE_PACKAGE = "com.x.database."

private fun MethodData.isRecursive() = invokes.any { it.descriptor == descriptor }

internal val dbTimelineEntryToItemFingerprint = findMethodDirect {
    val clientEventInfo = ClientEventInfoToStringFingerprint.run().declaredClass!!.name

    val itemInterfaces = findMethod {
        matcher {
            modifiers(Modifier.ABSTRACT)
            paramCount = 0
            returnType = clientEventInfo
        }
    }
        .mapNotNull { it.declaredClass }
        .filter { Modifier.isInterface(it.modifiers) }
        .distinctBy { it.name }

    val itemInterface = itemInterfaces.singleOrNull()?.name ?: throw Exception(
        "expected one interface with a no-arg $clientEventInfo getter, found " +
            "${itemInterfaces.size}: ${itemInterfaces.joinToString { it.name }}",
    )

    val mappers = findMethod {
        matcher {
            modifiers(Modifier.STATIC)
            returnType = itemInterface
            paramCount(2, Int.MAX_VALUE)
        }
    }

    val fromDatabaseRow = mappers.filter {
        it.paramTypeNames.firstOrNull()?.startsWith(DATABASE_PACKAGE) == true
    }

    fromDatabaseRow.singleOrNull()
        ?: fromDatabaseRow.singleOrNull(MethodData::isRecursive)
        ?: mappers.singleOrNull(MethodData::isRecursive)
        ?: throw Exception(
            "expected one static $itemInterface mapper taking a $DATABASE_PACKAGE row, found " +
                "${fromDatabaseRow.size} of ${mappers.size} candidates: " +
                mappers.joinToString { it.descriptor },
        )
}
