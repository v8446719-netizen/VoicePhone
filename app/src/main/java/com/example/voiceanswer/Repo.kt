package com.example.voiceanswer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
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

class Contact(val id: Long, val lookupKey: String?, val name: String, val number: String, val starred: Boolean)

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

    fun contacts(c: Context): List<Contact> {
        if (!has(c, Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = ArrayList<Contact>()
        val seen = HashSet<Long>()
        try {
            val proj = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.STARRED
            )
            c.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    if (!seen.add(id)) continue
                    val name = cur.getString(2) ?: continue
                    out.add(Contact(id, cur.getString(1), name, cur.getString(3).orEmpty(), cur.getInt(4) == 1))
                }
            }
        } catch (_: Exception) {
        }
        return out
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
