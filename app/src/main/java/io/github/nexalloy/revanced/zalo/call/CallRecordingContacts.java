package io.github.nexalloy.revanced.zalo.calls;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

public final class CallRecordingContacts {
    public static final class Result {
        public final String displayName;
        public final String phoneNumber;

        Result(String displayName, String phoneNumber) {
            this.displayName = displayName;
            this.phoneNumber = phoneNumber;
        }
    }

    private CallRecordingContacts() {
    }

    public static Result resolve(
            Context context,
            String peerUid,
            String displayName,
            String visiblePhone) {
        String resolvedName = cleanName(displayName);
        String resolvedPhone = cleanPhone(visiblePhone);
        if (context == null || !resolvedPhone.isEmpty()) {
            return new Result(resolvedName, resolvedPhone);
        }
        LinkedHashSet<String> phones = new LinkedHashSet<>();
        String profileName = queryProfiles(context, peerUid, resolvedName, phones);
        if (genericName(resolvedName) && !profileName.isEmpty()) {
            resolvedName = profileName;
        }
        if (phones.isEmpty()) {
            querySyncedContacts(context, peerUid, resolvedName, phones);
        }
        if (phones.size() == 1) {
            resolvedPhone = phones.iterator().next();
        }
        return new Result(resolvedName, resolvedPhone);
    }

    private static String queryProfiles(
            Context context, String peerUid, String displayName, Set<String> phones) {
        File database = context.getDatabasePath("zalo");
        if (!database.isFile()) {
            return "";
        }
        String foundName = "";
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database.getAbsolutePath(), null,
                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
            String selection;
            String[] args;
            if (validUid(peerUid)) {
                selection = "uid=?";
                args = new String[]{peerUid};
            } else if (!genericName(displayName)) {
                selection = "dpn=?";
                args = new String[]{displayName};
            } else {
                return "";
            }
            try (Cursor cursor = db.query("contact_profile_5",
                    new String[]{"dpn", "phone"}, selection, args,
                    null, null, null, "8")) {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(0);
                    if (foundName.isEmpty() && name != null && !name.trim().isEmpty()) {
                        foundName = name.trim();
                    }
                    addPhone(phones, cursor.getString(1));
                }
            }
        } catch (Throwable ignored) {
        }
        return foundName;
    }

    private static void querySyncedContacts(
            Context context, String peerUid, String displayName, Set<String> phones) {
        File database = context.getDatabasePath("phone_contacts_v2");
        if (!database.isFile()) {
            return;
        }
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database.getAbsolutePath(), null,
                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
            String selection;
            String[] args;
            if (validUid(peerUid)) {
                selection = "zalo_uid=?";
                args = new String[]{peerUid};
            } else if (!genericName(displayName)) {
                selection = "name=?";
                args = new String[]{displayName};
            } else {
                return;
            }
            try (Cursor cursor = db.query("phone_contacts_v1",
                    new String[]{"number_iso", "number"}, selection, args,
                    null, null, null, "8")) {
                while (cursor.moveToNext()) {
                    String normalized = cleanPhone(cursor.getString(0));
                    addPhone(phones, normalized.isEmpty() ? cursor.getString(1) : normalized);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void addPhone(Set<String> phones, String value) {
        String phone = cleanPhone(value);
        if (!phone.isEmpty()) {
            phones.add(phone);
        }
    }

    private static String cleanName(String value) {
        return value == null || value.trim().isEmpty() ? "Zalo contact" : value.trim();
    }

    private static String cleanPhone(String value) {
        if (value == null) {
            return "";
        }
        boolean plus = value.trim().startsWith("+");
        String digits = value.replaceAll("\\D", "");
        if (digits.length() < 8 || digits.length() > 15) {
            return "";
        }
        return plus ? "+" + digits : digits;
    }

    private static boolean validUid(String value) {
        return value != null && value.matches("\\d{5,20}");
    }

    private static boolean genericName(String value) {
        return value == null || value.isEmpty() || "Zalo contact".equals(value);
    }
}
