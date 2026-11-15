@file:Suppress("DEPRECATION")

package com.example.voiceanswer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.OutputStream

/**
 * Тихое прослушивание слова-активатора (Android 13+).
 * Микрофон открыт один раз и не перезапускается: сами «слушаем» звук и только когда слышна речь,
 * отдаём её (вместе с последними 0,6 с до начала) распознавателю Google через канал.
 * Поэтому нет постоянного «включился-выключился» и писков — распознаватель работает только когда вы говорите.
 */
class WakeListener(
    context: Context,
    private val onPhrase: (List<String>) -> Boolean,
    private val bias: () -> List<String>,
    private val onBroken: () -> Unit
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile var running = false
        private set
    @Volatile private var busy = false
    private var thread: Thread? = null
    private var sr: SpeechRecognizer? = null
    private var failures = 0
    private var muted = HashSet<Int>()
    private val sessionTimeout = Runnable {
        if (busy) {
            busy = false
            destroyRecognizer()
            unmute()
            fail()
        }
    }

    fun start(): Boolean {
        if (running) return true
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        if (!SpeechRecognizer.isRecognitionAvailable(app)) return false
        running = true
        thread = Thread { loop() }.also {
            it.isDaemon = true
            it.start()
        }
        return true
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        main.removeCallbacks(sessionTimeout)
        main.post {
            destroyRecognizer()
            unmute()
        }
    }

    private fun fail() {
        failures++
        if (failures >= 3 && running) {
            running = false
            onBroken()
        }
    }

    // ---- слух: один AudioRecord, простое определение речи по громкости ----

    private fun loop() {
        val rate = 16000
        val frame = 320 // 20 мс
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec: AudioRecord
        try {
            rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate * 2)
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                running = false
                main.post { onBroken() }
                return
            }
            rec.startRecording()
        } catch (e: Exception) {
            running = false
            main.post { onBroken() }
            return
        }
        val pre = ArrayDeque<ShortArray>()
        var floor = 200.0
        var speechRun = 0
        var silence = 0
        var frames = 0
        var out: OutputStream? = null
        try {
            while (running) {
                val buf = ShortArray(frame)
                val n = rec.read(buf, 0, frame)
                if (n < 0) break
                if (n == 0) continue
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = Math.sqrt(sum / n)
                if (out == null) {
                    if (rms < floor * 2) floor = floor * 0.98 + rms * 0.02
                    pre.addLast(buf)
                    if (pre.size > 30) pre.removeFirst()
                    if (!busy) {
                        if (rms > maxOf(floor * 3.0, 600.0)) speechRun++ else speechRun = 0
                        if (speechRun >= 4) {
                            out = beginSession(pre)
                            silence = 0
                            frames = 0
                            speechRun = 0
                        }
                    }
                } else {
                    try {
                        out.write(toBytes(buf))
                    } catch (e: Exception) {
                        out = null
                        continue
                    }
                    frames++
                    if (rms > maxOf(floor * 2.0, 450.0)) silence = 0 else silence++
                    if (silence >= 70 || frames >= 600) { // 1,4 с тишины или 12 с речи
                        try {
                            out.close()
                        } catch (_: Exception) {
                        }
                        out = null
                        pre.clear()
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                out?.close()
            } catch (_: Exception) {
            }
            try {
                rec.stop()
            } catch (_: Exception) {
            }
            rec.release()
        }
    }

    private fun toBytes(b: ShortArray): ByteArray {
        val o = ByteArray(b.size * 2)
        for (i in b.indices) {
            o[2 * i] = (b[i].toInt() and 0xFF).toByte()
            o[2 * i + 1] = ((b[i].toInt() shr 8) and 0xFF).toByte()
        }
        return o
    }

    /** Открывает канал распознавателю и сразу отдаёт ему «хвост» до начала речи. */
    private fun beginSession(pre: ArrayDeque<ShortArray>): OutputStream? {
        return try {
            val pipe = ParcelFileDescriptor.createPipe()
            val read = pipe[0]
            val os = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            for (f in pre) os.write(toBytes(f))
            pre.clear()
            busy = true
            main.post { startRecognizer(read) }
            os
        } catch (e: Exception) {
            null
        }
    }

    // ---- распознавание только когда есть речь ----

    private fun startRecognizer(read: ParcelFileDescriptor) {
        if (!running) {
            busy = false
            try {
                read.close()
            } catch (_: Exception) {
            }
            return
        }
        mute()
        destroyRecognizer()
        try {
            val r = SpeechRecognizer.createSpeechRecognizer(app)
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {}

                override fun onResults(results: Bundle?) {
                    finish()
                    failures = 0
                    val l = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!l.isNullOrEmpty()) onPhrase(l)
                }

                override fun onError(error: Int) {
                    finish()
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        // просто шум или неразборчиво — ждём следующую речь
                    } else {
                        fail()
                    }
                }
            })
            sr = r
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10)
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            if (Build.VERSION.SDK_INT >= 33) {
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                i.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(bias()))
            }
            main.postDelayed(sessionTimeout, 25000)
            r.startListening(i)
        } catch (e: Exception) {
            try {
                read.close()
            } catch (_: Exception) {
            }
            busy = false
            unmute()
            fail()
        }
    }

    private fun finish() {
        main.removeCallbacks(sessionTimeout)
        busy = false
        unmute()
    }

    private fun destroyRecognizer() {
        val r = sr ?: return
        sr = null
        try {
            r.cancel()
            r.destroy()
        } catch (_: Exception) {
        }
    }

    // писк начала распознавания глушим только на время самой фразы
    private fun mute() {
        try {
            val am = app.getSystemService(AudioManager::class.java)
            for (st in intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
                if (!am.isStreamMute(st) && muted.add(st)) am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0)
            }
            if (!am.isMusicActive && !am.isStreamMute(AudioManager.STREAM_MUSIC) && muted.add(AudioManager.STREAM_MUSIC)) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            }
            Prefs.setBeepMuted(app, muted)
        } catch (_: Exception) {
        }
    }

    private fun unmute() {
        if (muted.isEmpty()) return
        try {
            val am = app.getSystemService(AudioManager::class.java)
            for (st in muted) am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: Exception) {
        }
        muted.clear()
        Prefs.setBeepMuted(app, emptySet())
    }
}
