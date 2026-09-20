package io.github.nexalloy.revanced.zalo.calls

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.LinkedHashSet

object CallRecordingContacts {

    class Result(
        @JvmField val displayName: String,
        @JvmField val phoneNumber: String,
    )

    fun resolve(
        context: Context?,
        peerUid: String?,
        displayName: String?,
        visiblePhone: String?,
    ): Result {
        var resolvedName = cleanName(displayName)
        var resolvedPhone = cleanPhone(visiblePhone)
        if (context == null || resolvedPhone.isNotEmpty()) {
            return Result(resolvedName, resolvedPhone)
        }
        val phones = LinkedHashSet<String>()
        val profileName = queryProfiles(context, peerUid, resolvedName, phones)
        if (genericName(resolvedName) && profileName.isNotEmpty()) {
            resolvedName = profileName
        }
        if (phones.isEmpty()) {
            querySyncedContacts(context, peerUid, resolvedName, phones)
        }
        if (phones.size == 1) {
            resolvedPhone = phones.iterator().next()
        }
        return Result(resolvedName, resolvedPhone)
    }

    private fun queryProfiles(
        context: Context,
        peerUid: String?,
        displayName: String,
        phones: MutableSet<String>,
    ): String {
        val database = context.getDatabasePath("zalo")
        if (!database.isFile) return ""
        var foundName = ""
        try {
            SQLiteDatabase.openDatabase(
                database.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).use { db ->
                val selection: String
                val args: Array<String>
                if (validUid(peerUid)) {
                    selection = "uid=?"
                    args = arrayOf(peerUid!!)
                } else if (!genericName(displayName)) {
                    selection = "dpn=?"
                    args = arrayOf(displayName)
                } else {
                    return ""
                }
                db.query(
                    "contact_profile_5",
                    arrayOf("dpn", "phone"), selection, args,
                    null, null, null, "8"
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0)
                        if (foundName.isEmpty() && name != null && name.trim().isNotEmpty()) {
                            foundName = name.trim()
                        }
                        addPhone(phones, cursor.getString(1))
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
        return foundName
    }

    private fun querySyncedContacts(
        context: Context,
        peerUid: String?,
        displayName: String,
        phones: MutableSet<String>,
    ) {
        val database = context.getDatabasePath("phone_contacts_v2")
        if (!database.isFile) return
        try {
            SQLiteDatabase.openDatabase(
                database.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).use { db ->
                val selection: String
                val args: Array<String>
                if (validUid(peerUid)) {
                    selection = "zalo_uid=?"
                    args = arrayOf(peerUid!!)
                } else if (!genericName(displayName)) {
                    selection = "name=?"
                    args = arrayOf(displayName)
                } else {
                    return
                }
                db.query(
                    "phone_contacts_v1",
                    arrayOf("number_iso", "number"), selection, args,
                    null, null, null, "8"
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val normalized = cleanPhone(cursor.getString(0))
                        addPhone(phones, if (normalized.isEmpty()) cursor.getString(1) else normalized)
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun addPhone(phones: MutableSet<String>, value: String?) {
        val phone = cleanPhone(value)
        if (phone.isNotEmpty()) phones.add(phone)
    }

    private fun cleanName(value: String?): String =
        if (value == null || value.trim().isEmpty()) "Zalo contact" else value.trim()

    private fun cleanPhone(value: String?): String {
        if (value == null) return ""
        val plus = value.trim().startsWith("+")
        val digits = value.replace(Regex("\\D"), "")
        if (digits.length < 8 || digits.length > 15) return ""
        return if (plus) "+$digits" else digits
    }

    private fun validUid(value: String?): Boolean =
        value != null && value.matches(Regex("\\d{5,20}"))

    private fun genericName(value: String?): Boolean =
        value == null || value.isEmpty() || value == "Zalo contact"
}
