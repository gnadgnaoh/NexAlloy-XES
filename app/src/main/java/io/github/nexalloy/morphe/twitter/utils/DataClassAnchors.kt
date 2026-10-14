package io.github.nexalloy.morphe.twitter.utils

import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher

internal const val X_PACKAGE = "com.x."
internal const val X_MODELS_PACKAGE = "com.x.models."
internal const val X_URT_PACKAGE = "com.x.urt."
internal const val X_NAVIGATION_PACKAGE = "com.x.navigation."

internal fun MethodMatcher.dataClassToString(simpleName: String, packagePrefix: String = X_PACKAGE) {
    paramCount(0)
    returnType("java.lang.String")
    declaredClass(packagePrefix, StringMatchType.StartsWith)
    usingStrings(listOf("$simpleName("), StringMatchType.StartsWith)
}
