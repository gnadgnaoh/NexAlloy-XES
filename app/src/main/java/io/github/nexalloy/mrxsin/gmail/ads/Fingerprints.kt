package io.github.nexalloy.mrxsin.gmail.ads

import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.query.enums.StringMatchType

const val AD_PACKAGE = "com.google.android.gm.ads"

val adViewConstructors = findMethodListDirect {
    findMethod {
        matcher {
            declaredClass { className("$AD_PACKAGE.", StringMatchType.StartsWith) }
            name = "<init>"
            paramTypes(listOf("android.content.Context", "android.util.AttributeSet"))
        }
    }
}
