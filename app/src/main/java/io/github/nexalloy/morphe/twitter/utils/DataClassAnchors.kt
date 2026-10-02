package io.github.nexalloy.morphe.twitter.utils

import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher

/** Root of X's own (non-obfuscated) package tree. R8 renames classes but keeps these packages. */
internal const val X_PACKAGE = "com.x."
internal const val X_MODELS_PACKAGE = "com.x.models."
internal const val X_URT_PACKAGE = "com.x.urt."
internal const val X_NAVIGATION_PACKAGE = "com.x.navigation."

/**
 * Matches the generated `toString()` of a Kotlin data class by its simple name.
 *
 * Kotlin always emits `"<SimpleName>(<firstProperty>="` as the first literal. Matching only the
 * `"<SimpleName>("` prefix keeps the anchor alive when X renames, reorders or inserts the first
 * property, which is the most common way the old exact-string anchors broke. The package scope
 * keeps unrelated classes that share a simple name (e.g. Thrift `ClientEventInfo`, GraphQL
 * fragments) out of the result.
 */
internal fun MethodMatcher.dataClassToString(simpleName: String, packagePrefix: String = X_PACKAGE) {
    paramCount(0)
    returnType("java.lang.String")
    declaredClass(packagePrefix, StringMatchType.StartsWith)
    usingStrings(listOf("$simpleName("), StringMatchType.StartsWith)
}
