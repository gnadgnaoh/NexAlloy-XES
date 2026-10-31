package io.github.nexalloy.revanced.instagram.profile

import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.InstructionData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

private const val USER = "com.instagram.user.model.User"
private const val PANDO_RELATIONSHIP = "com.instagram.api.schemas.ImmutablePandoRelationshipInfoDict"
private const val BOOLEAN = "java.lang.Boolean"

private fun DexKitBridge.pandoGetter(type: String, field: String, returns: String?): MethodData =
    findMethod {
        matcher {
            declaredClass(type)
            paramCount = 0
            if (returns != null) returnType = returns
            usingNumbers(field.hashCode())
        }
    }.single { !Modifier.isStatic(it.modifiers) && '.' in it.returnTypeName }

val profileHeaderBinderFingerprint = findMethodDirect {
    findMethod { matcher { usingEqStrings("bindFullName") } }.single()
}

val userBiographyGetterFingerprint = findMethodDirect { pandoGetter(USER, "biography", "java.lang.String") }
val userHdProfilePicGetterFingerprint = findMethodDirect {
    pandoGetter(USER, "hd_profile_pic_url_info", "com.instagram.api.schemas.ProfilePicUrlInfo")
}

val userFriendshipStatusGetterFingerprint = findMethodDirect { pandoGetter(USER, "friendship_status", null) }
val relationshipFollowedByGetterFingerprint = findMethodDirect { pandoGetter(PANDO_RELATIONSHIP, "followed_by", BOOLEAN) }
val userFollowedByGetterFingerprint = findMethodDirect { pandoGetter(USER, "followed_by", BOOLEAN) }
val relationshipFollowingGetterFingerprint = findMethodDirect { pandoGetter(PANDO_RELATIONSHIP, "following", BOOLEAN) }

private const val PROFILE_FRAGMENT = "com.instagram.profile.fragment.UserDetailFragment"
private const val VIEW_MODEL = "com.instagram.profile.fragment.UserDetailViewModel"
private const val OP_CONST_STRING = 0x1a
private const val OP_CONST_STRING_JUMBO = 0x1b
private const val OP_IGET_OBJECT = 0x54
private const val OP_MOVE_OBJECT = 0x07
private const val OP_MOVE_OBJECT_FROM16 = 0x08
private const val OP_MOVE_OBJECT_16 = 0x09
private const val OP_MOVE_RESULT_OBJECT = 0x0c
private val READS_FIRST = listOf(0x0f..0x11, 0x1d..0x1f, 0x27..0x27, 0x32..0x3d, 0x4b..0x51, 0x59..0x5f, 0x67..0x6d, 0x6e..0x78)
private const val OP_INVOKE_INTERFACE = 0x72

private fun List<InstructionData>.keyed(key: Int, tree: String, returns: String): MethodData? =
    indices.mapNotNull { at ->
        if (this[at].literal != key.toLong()) return@mapNotNull null
        this.getOrNull(at + 1)?.methodRef?.takeIf {
            this[at + 1].opcode == OP_INVOKE_INTERFACE && it.declaredClassName == tree && it.returnTypeName == returns &&
                it.paramTypeNames == listOf("int")
        }
    }.distinctBy { it.descriptor }.singleOrNull()

private fun List<InstructionData>.treeCall(from: Int, until: Int, tree: String, vararg params: String): Int? =
    (from..minOf(until, size - 1)).firstOrNull {
        this[it].opcode == OP_INVOKE_INTERFACE && this[it].methodRef?.let { m ->
            m.declaredClassName == tree && m.returnTypeName == tree && m.paramTypeNames == params.toList()
        } == true
    }

private fun List<InstructionData>.stringIn(before: Int, register: Int): String? {
    var reg = register
    for (i in before - 1 downTo 0) {
        val ins = this[i]
        if (ins.registerCount == 0 || ins.register(0) != reg) continue
        when (ins.opcode) {
            OP_CONST_STRING, OP_CONST_STRING_JUMBO -> return ins.string
            OP_MOVE_OBJECT, OP_MOVE_OBJECT_FROM16, OP_MOVE_OBJECT_16 -> reg = ins.register(1)
            OP_MOVE_RESULT_OBJECT -> return null
            else -> if (READS_FIRST.none { ins.opcode in it }) return null
        }
    }
    return null
}

val screenAnswerValue: DexKitBridge.() -> String = {
    val getters = findMethod {
        matcher { declaredClass(PROFILE_FRAGMENT); paramCount = 0; addInvoke { name = "getValue"; paramCount = 0 } }
    }.filter { !Modifier.isStatic(it.modifiers) && '.' in it.returnTypeName }
    val readers = findMethod {
        matcher {
            usingNumbers("friendship_status".hashCode(), "followed_by".hashCode())
            addInvoke { declaredClass(PROFILE_FRAGMENT); paramCount = 0 }
        }
    }
    val found = readers.flatMap { reader ->
        val code = reader.instructions
        code.indices.mapNotNull { at ->
            val getter = getters.firstOrNull { it.descriptor == code[at].methodRef?.descriptor } ?: return@mapNotNull null
            val read = (at + 1..minOf(at + 12, code.size - 1)).firstOrNull { code[it].opcode == OP_IGET_OBJECT } ?: return@mapNotNull null
            val tree = code[read].fieldRef ?: return@mapNotNull null
            val t = tree.typeName
            val reinterpretAt = code.treeCall(read + 1, read + 3, t, "int") ?: return@mapNotNull null
            val type = code.subList(read + 1, reinterpretAt).lastOrNull { it.literal != null }?.literal ?: return@mapNotNull null
            val retypeAt = code.treeCall(reinterpretAt + 1, reinterpretAt + 3, t, "java.lang.String", "int") ?: return@mapNotNull null
            val retype = code[retypeAt].methodRef!!
            val client = code.stringIn(retypeAt, code[retypeAt].register(1)) ?: return@mapNotNull null
            val subtree = code.keyed("friendship_status".hashCode(), t, t) ?: return@mapNotNull null
            val flag = code.keyed("followed_by".hashCode(), t, "java.lang.Boolean") ?: return@mapNotNull null
            val answer = getter.instructions.firstOrNull {
                it.opcode == OP_IGET_OBJECT && it.fieldRef?.declaredClassName == VIEW_MODEL
            }?.fieldRef ?: return@mapNotNull null
            listOf(
                answer.name, tree.declaredClassName, tree.name, code[reinterpretAt].methodRef!!.name, retype.name,
                subtree.name, flag.name, type.toString(), client,
            ).joinToString(";")
        }
    }
    found.distinct().single()
}
