package com.example.voiceanswer

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone as P
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class LogEntry(
    val id: Long,
    val number: String,
    val name: String?,
    val type: Int,
    val date: Long,
    val label: String
) {
    val missed: Boolean get() = type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE
    val outgoing: Boolean get() = type == CallLog.Calls.OUTGOING_TYPE
    val title: String get() = if (!name.isNullOrBlank()) name else if (number.isNotBlank()) Calls.formatNumber(number) else "Неизвестный номер"
}

class Contact(
    val id: Long,
    val lookupKey: String?,
    val name: String,
    val number: String,
    val starred: Boolean,
    val photo: String?,
    val dataId: Long
)

/** Чтение журнала звонков и контактов (нужны разрешения; без них просто пустые списки). */
object Repo {

    private fun has(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun callLog(c: Context, limit: Int = 300): List<LogEntry> {
        if (!has(c, Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = ArrayList<LogEntry>()
        try {
            c.contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, "date DESC")?.use { cur ->
                val iId = cur.getColumnIndex(CallLog.Calls._ID)
                val iNum = cur.getColumnIndex(CallLog.Calls.NUMBER)
                val iName = cur.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val iType = cur.getColumnIndex(CallLog.Calls.TYPE)
                val iDate = cur.getColumnIndex(CallLog.Calls.DATE)
                val iNumType = cur.getColumnIndex(CallLog.Calls.CACHED_NUMBER_TYPE)
                val iNumLabel = cur.getColumnIndex(CallLog.Calls.CACHED_NUMBER_LABEL)
                val iGeo = cur.getColumnIndex("geocoded_location")
                while (cur.moveToNext() && out.size < limit) {
                    val number = if (iNum >= 0) cur.getString(iNum).orEmpty() else ""
                    var label = ""
                    if (iNumType >= 0 && !cur.isNull(iNumType) && cur.getInt(iNumType) != 0) {
                        val custom = if (iNumLabel >= 0) cur.getString(iNumLabel) else null
                        label = try {
                            ContactsContract.CommonDataKinds.Phone
                                .getTypeLabel(c.resources, cur.getInt(iNumType), custom).toString()
                        } catch (e: Exception) {
                            ""
                        }
                    }
                    if (label.isEmpty() && iGeo >= 0) label = cur.getString(iGeo).orEmpty()
                    out.add(
                        LogEntry(
                            if (iId >= 0) cur.getLong(iId) else 0L,
                            number,
                            if (iName >= 0) cur.getString(iName) else null,
                            if (iType >= 0) cur.getInt(iType) else 0,
                            if (iDate >= 0) cur.getLong(iDate) else 0L,
                            label
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun deleteLog(c: Context, id: Long) {
        if (!has(c, Manifest.permission.WRITE_CALL_LOG)) return
        try {
            c.contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID}=?", arrayOf(id.toString()))
        } catch (_: Exception) {
        }
    }

    private fun readContacts(c: Context, selection: String?, args: Array<String>?, all: Boolean): List<Contact> {
        if (!has(c, Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = ArrayList<Contact>()
        val seen = HashSet<Long>()
        try {
            val proj = arrayOf(
                P.CONTACT_ID, P.LOOKUP_KEY, P.DISPLAY_NAME, P.NUMBER, P.STARRED, P.PHOTO_URI, P._ID
            )
            c.contentResolver.query(
                P.CONTENT_URI, proj, selection, args, P.DISPLAY_NAME + " COLLATE LOCALIZED ASC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    if (!all && !seen.add(id)) continue
                    val name = cur.getString(2) ?: continue
                    out.add(
                        Contact(id, cur.getString(1), name, cur.getString(3).orEmpty(), cur.getInt(4) == 1, cur.getString(5), cur.getLong(6))
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun contacts(c: Context): List<Contact> = readContacts(c, null, null, false)

    fun contact(c: Context, id: Long): Contact? =
        readContacts(c, "${P.CONTACT_ID}=?", arrayOf(id.toString()), false).firstOrNull()

    /** Ключ для сопоставления номеров: последние 10 цифр (8 999… и +7 999… дают одно и то же). */
    fun numKey(number: String): String = number.filter { it.isDigit() }.takeLast(10)

    fun insertContact(c: Context, name: String, number: String): Boolean {
        if (!has(c, Manifest.permission.WRITE_CONTACTS)) return false
        return try {
            val ops = ArrayList<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, P.CONTENT_ITEM_TYPE)
                    .withValue(P.NUMBER, number)
                    .withValue(P.TYPE, P.TYPE_MOBILE)
                    .build()
            )
            c.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun firstRawContact(c: Context, contactId: Long): Long? {
        return try {
            c.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID}=?", arrayOf(contactId.toString()), null
            )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    fun updateContact(c: Context, contactId: Long, phoneDataId: Long, name: String, number: String): Boolean {
        if (!has(c, Manifest.permission.WRITE_CONTACTS)) return false
        val raw = firstRawContact(c, contactId) ?: return false
        return try {
            val ops = ArrayList<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                    .withSelection(
                        "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                        arrayOf(raw.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    )
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                    .withSelection("${ContactsContract.Data._ID}=?", arrayOf(phoneDataId.toString()))
                    .withValue(P.NUMBER, number)
                    .build()
            )
            c.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun deleteContact(c: Context, contactId: Long): Boolean {
        if (!has(c, Manifest.permission.WRITE_CONTACTS)) return false
        return try {
            c.contentResolver.delete(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId.toString()), null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun setStarred(c: Context, contactId: Long, starred: Boolean): Boolean {
        if (!has(c, Manifest.permission.WRITE_CONTACTS)) return false
        return try {
            val v = ContentValues()
            v.put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0)
            c.contentResolver.update(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId.toString()), v, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    /** «12:30» сегодня, «Вчера», иначе «12.11». */
    fun when_(ms: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance()
        then.timeInMillis = ms
        val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
        val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dayDiff == 0 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
            sameYear && dayDiff == 1 -> "Вчера"
            else -> SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(ms))
        }
    }
}
