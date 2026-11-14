@file:Suppress("DEPRECATION")

package com.example.voiceanswer

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Запись разговора. Android не даёт обычным приложениям писать голос собеседника напрямую,
 * поэтому сначала пробуем «запись вызова», а если телефон не разрешает — пишем через микрофон
 * (тогда собеседника слышно, только если включена громкая связь).
 * Файлы лежат в папке «Музыка/Записи звонков».
 */
object Recorder {
    private var rec: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var file: File? = null
    private var usedCallSource = false

    val active: Boolean get() = rec != null

    /** true, если пишется только микрофон (собеседника слышно лишь на громкой связи). */
    val micOnly: Boolean get() = active && !usedCallSource

    private fun newRecorder(ctx: Context): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()

    private fun fileName(): String =
        "Звонок_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".m4a"

    /** Возвращает null, если запись началась, иначе — текст ошибки. */
    fun start(ctx: Context): String? {
        if (rec != null) return null
        val app = ctx.applicationContext
        if (!Perms.has(app, android.Manifest.permission.RECORD_AUDIO)) return "Нет разрешения на микрофон"
        val name = fileName()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues()
                v.put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Записи звонков")
                v.put(MediaStore.Audio.Media.IS_PENDING, 1)
                val u = app.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v)
                    ?: return "Не удалось создать файл записи"
                uri = u
                pfd = app.contentResolver.openFileDescriptor(u, "w")
            } else {
                val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Записи звонков")
                dir.mkdirs()
                file = File(dir, name)
            }
        } catch (e: Exception) {
            cleanup(app, false)
            return "Не удалось создать файл записи"
        }

        val sources = intArrayOf(MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.AudioSource.MIC)
        var lastError = "Телефон не разрешает запись"
        for (src in sources) {
            val r = newRecorder(app)
            try {
                r.setAudioSource(src)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(44100)
                r.setAudioEncodingBitRate(128000)
                val p = pfd
                if (p != null) r.setOutputFile(p.fileDescriptor) else r.setOutputFile(file!!.absolutePath)
                r.prepare()
                r.start()
                rec = r
                usedCallSource = src == MediaRecorder.AudioSource.VOICE_CALL
                return null
            } catch (e: Exception) {
                lastError = "Не удалось начать запись: " + (e.message ?: e.javaClass.simpleName)
                try {
                    r.reset()
                    r.release()
                } catch (_: Exception) {
                }
            }
        }
        cleanup(app, false)
        return lastError
    }

    /** Останавливает запись и сообщает, где лежит файл. */
    fun stop(ctx: Context) {
        val r = rec ?: return
        val app = ctx.applicationContext
        rec = null
        var ok = true
        try {
            r.stop()
        } catch (e: Exception) {
            ok = false
        }
        try {
            r.release()
        } catch (_: Exception) {
        }
        cleanup(app, ok)
        if (ok) Toast.makeText(app, "Запись сохранена: Музыка / Записи звонков", Toast.LENGTH_LONG).show()
    }

    private fun cleanup(app: Context, keep: Boolean) {
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        pfd = null
        val u = uri
        if (u != null) {
            try {
                if (keep && Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues()
                    v.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    app.contentResolver.update(u, v, null, null)
                } else if (!keep) {
                    app.contentResolver.delete(u, null, null)
                }
            } catch (_: Exception) {
            }
        }
        uri = null
        val f = file
        if (f != null && !keep) {
            try {
                f.delete()
            } catch (_: Exception) {
            }
        }
        file = null
    }
}
