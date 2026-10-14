package io.github.nexalloy.revanced.zalo.telemetry

import io.github.nexalloy.patch
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

val DisableAnalyticsDatabase = patch(
    name = "Disable analytics database",
    description = "Stops Zalo from recording events, screens, sessions and views in its " +
        "analytics database, so there is nothing for it to upload.",
) {
    val analyticsDatabase = classLoader.loadClass(ANALYTICS_DATABASE_CLASS)
    val databaseField = ::roomStatementDatabaseField.field.apply { isAccessible = true }
    val verdicts: MutableMap<Any, Boolean> = Collections.synchronizedMap(WeakHashMap())
    fun isAnalyticsAdapter(adapter: Any): Boolean = verdicts.getOrPut(adapter) {
        analyticsDatabase.isInstance(runCatching { databaseField.get(adapter) }.getOrNull())
    }

    ::roomEntityInsertMethods.dexMethodList.forEach { insert ->
        insert.hookMethod {
            before { param ->
                val adapter = param.thisObject ?: return@before
                if (!isAnalyticsAdapter(adapter)) return@before
                val returnsLong = (param.method as? Method)?.returnType == Long::class.javaPrimitiveType
                param.result = if (returnsLong) 0L else null
            }
        }
    }
}
