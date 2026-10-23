package io.github.nexalloy.revanced.facebook.download

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData

internal class DexStream private constructor(val items: List<Item>) {

    sealed class Item(val opcode: Int) {
        class Str(op: Int, val value: String) : Item(op)
        class Num(op: Int, val int: Int) : Item(op)
        class Field(op: Int, val field: FieldData) : Item(op) {
            val isInstanceRead get() = opcode in 0x52..0x58
            val isStaticRead get() = opcode in 0x60..0x66
        }
        class Call(op: Int, val callee: MethodData) : Item(op) {
            val isInterface get() = opcode == 0x72 || opcode == 0x78
            val isVirtual get() = opcode == 0x6e || opcode == 0x74
        }
        class Op(op: Int) : Item(op)
    }

    fun indexOfString(value: String) = items.indexOfFirst { (it as? Item.Str)?.value == value }

    fun calls() = items.filterIsInstance<Item.Call>()

    companion object {
        private fun isString(op: Int) = op == 0x1a || op == 0x1b
        private fun isField(op: Int) = op in 0x52..0x6d
        private fun isCall(op: Int) = op in 0x6e..0x72 || op in 0x74..0x78
        private fun isNumber(op: Int) = op in 0x12..0x19 || op in 0xd0..0xe2

        fun of(method: MethodData): DexStream? = runCatching {
            val strings = method.usingStrings
            val numbers = method.usingNumbers
            val fields = method.usingFields
            val calls = method.invokes
            var s = 0; var n = 0; var f = 0; var c = 0
            val items = method.opCodes.map { op ->
                when {
                    isString(op) -> Item.Str(op, strings[s++])
                    isNumber(op) -> Item.Num(op, numbers[n++].longValue().toInt())
                    isField(op) -> Item.Field(op, fields[f++].field)
                    isCall(op) -> Item.Call(op, calls[c++])
                    else -> Item.Op(op)
                }
            }
            if (s != strings.size || n != numbers.size || f != fields.size || c != calls.size) null
            else DexStream(items)
        }.getOrNull()
    }
}

internal fun DexKitBridge.reportedField(owner: String, marker: String, name: String, typeName: String): FieldData {
    val dumps = getClassData(owner)?.findMethod { matcher { usingStrings(marker) } }?.filter { it.isMethod }.orEmpty()
    val dump = dumps.singleOrNull() ?: error("Expected 1 dump of $owner with \"$marker\", found ${dumps.size}")
    val items = DexStream.of(dump)?.items ?: error("Could not read ${dump.descriptor}")

    fun scan(from: Int, step: Int): String? {
        var found: String? = null
        var at = from
        while (at in items.indices) {
            when (val item = items[at]) {
                is DexStream.Item.Call -> return found
                is DexStream.Item.Str -> found = item.value
                else -> {}
            }
            at += step
        }
        return found
    }

    val field = items.withIndex().firstNotNullOfOrNull { (at, item) ->
        val read = item as? DexStream.Item.Field ?: return@firstNotNullOfOrNull null
        if (!read.isInstanceRead || read.field.declaredClassName != owner) return@firstNotNullOfOrNull null
        read.field.takeIf { (scan(at + 1, 1) ?: scan(at - 1, -1)) == name }
    } ?: error("$owner's dump reports no field as \"$name\"")
    check(field.typeName == typeName) { "$owner's \"$name\" is ${field.typeName}, not $typeName" }
    return field
}

internal fun ClassData.typesCreated(): Set<String> =
    methods.flatMap { runCatching { it.invokes }.getOrDefault(emptyList()) }
        .filter { it.isConstructor }.map { it.className }.toSet()
