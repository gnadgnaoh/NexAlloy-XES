package io.github.nexalloy.morphe.tiktok.interaction

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

internal const val COMMENT_CLASS = "com.ss.android.ugc.aweme.comment.model.Comment"
private const val CLIP_DATA = "android.content.ClipData"
private const val STRING = "java.lang.String"
private const val CERT = "com.bytedance.bpea.basics.Cert"

private val MethodData.isStatic get() = Modifier.isStatic(modifiers)

val commentClipBuilderFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = CLIP_DATA
            paramTypes(STRING, STRING, "java.util.List")
            usingEqStrings("copy_label")
        }
    }.single { it.isStatic }
}

val clipboardHelperFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramTypes(STRING, STRING, "android.content.Context", CERT)
            addInvoke { declaredClass(CLIP_DATA); name = "newPlainText" }
        }
    }.single { it.isStatic }
}

val commentCopyBuildersFingerprint = findMethodListDirect {
    val helper = clipboardHelperFingerprint()
    findMethod {
        matcher {
            addInvoke(helper.descriptor)
            addInvoke { declaredClass(COMMENT_CLASS); name = "getText"; paramCount = 0 }
            addInvoke { declaredClass(COMMENT_CLASS); name = "getUser"; paramCount = 0 }
        }
    }
}

val countFormattersFingerprint = findMethodListDirect {
    findMethod {
        matcher {
            returnType = STRING
            paramTypes("long")
            usingEqStrings("1.0M", "1.0B")
        }
    }.filter { it.isStatic }
}

val compactCountFormattersFingerprint = findMethodListDirect {
    findMethod {
        matcher {
            returnType = STRING
            paramTypes("long")
            usingNumbers(10_000)
            addInvoke {
                returnType = "java.lang.CharSequence"
                paramTypes("java.lang.Number", null, "int", "java.lang.Boolean", "int")
            }
        }
    }.filter { it.isStatic }
}

val localizedNumberFormatterFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "java.lang.CharSequence"
            paramTypes("java.lang.Number", null, "int", "java.lang.Boolean", "int")
        }
    }.single { it.isStatic }
}

internal const val HD_CHOICE_KEY = "USER_HD_VIDEO_SWITCH_SETTING"

val hdUploadDecisionFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "boolean"
            paramTypes("long")
            usingEqStrings("TOOLS_PUBLISH_REPO_NAME", HD_CHOICE_KEY)
        }
    }.single { it.isStatic }
}

val hdChoiceReadersFingerprint = findMethodListDirect {
    val owner = hdUploadDecisionFingerprint().declaredClassName
    findMethod {
        matcher { declaredClass(owner); usingEqStrings(HD_CHOICE_KEY) }
    }
}

internal const val SIGNATURE_COMPONENT =
    "com.ss.android.ugc.profile.platform.business.header.business.bio.business.signature.base.ProfileHeaderBaseSignatureComponent"
internal const val BIO_BASE_COMPONENT =
    "com.ss.android.ugc.profile.platform.business.header.business.bio.base.BioBaseUIComponent"
internal const val BASE_SHARE_PACKAGE = "com.ss.android.ugc.aweme.share.base.model.BaseSharePackage"

val bioBindFingerprints = findMethodListDirect {
    findMethod {
        matcher {
            declaredClass { superClass(BIO_BASE_COMPONENT) }
            returnType = "void"
            paramTypes(STRING, "java.util.List")
        }
    }.filter { Modifier.isPublic(it.modifiers) && !it.isStatic }
}

val shareSnapshotFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name = "<init>"
            paramCount = 1
            usingEqStrings("click_to_respond_duration", "config_duration")
        }
    }.single { it.declaredClassName != "com.ss.android.ugc.aweme.sharer.model.SharePackage" }
}

val shareActionPanelAttachFingerprint = findMethodDirect {
    findMethod {
        matcher { name = "onAttachedToWindow"; paramCount = 0; usingEqStrings("share_panel_action") }
    }.single()
}

val shareAppsPanelAttachFingerprint = findMethodDirect {
    findMethod {
        matcher { name = "onAttachedToWindow"; paramCount = 0; usingEqStrings("share_panel_channel") }
    }.single()
}

internal const val BASE_UI_COMPONENT = "com.ss.android.ugc.profile.platform.base.component.BaseUIComponent"
internal const val PROFILE_COMMON_INFO = "com.ss.android.ugc.profile.platform.base.data.ProfileCommonInfo"
internal const val HEADER_TEXT_ITEM =
    "com.ss.android.ugc.profile.platform.business.header.business.info.business.userinfo.base.CommonInfoBaseUIComponent"
internal const val RELATION_USER_CELL =
    "com.ss.android.ugc.profile.business.ur.following.ui.viewholder.assem.RelationUserCellAssem"

val profileHeaderTextBindFingerprint = findMethodDirect {
    findMethod {
        matcher {
            declaredClass(HEADER_TEXT_ITEM)
            returnType = "void"
            paramCount = 0
            addInvoke { declaredClass("android.widget.TextView"); name = "setText"; paramTypes("java.lang.CharSequence") }
        }
    }.single { Modifier.isPublic(it.modifiers) && !it.isStatic && it.name != "onCreate" }
}

val profileCommonInfoGetterFingerprint = findMethodDirect {
    findMethod {
        matcher { declaredClass(BASE_UI_COMPONENT); paramCount = 0; returnType = PROFILE_COMMON_INFO }
    }.single { !it.isStatic }
}

val relationCellBindFingerprint = findMethodDirect {
    findMethod {
        matcher { declaredClass(RELATION_USER_CELL); returnType = "void"; paramTypes("java.lang.Object") }
    }.single { m ->
        Modifier.isPublic(m.modifiers) && Modifier.isFinal(m.modifiers) && !m.isStatic &&
            (m.modifiers and 0x1000) == 0 && m.opCodes.firstOrNull() == OP_CHECK_CAST
    }
}

private const val OP_CHECK_CAST = 0x1f
