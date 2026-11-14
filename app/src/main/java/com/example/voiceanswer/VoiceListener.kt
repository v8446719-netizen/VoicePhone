@file:Suppress("DEPRECATION")

package com.example.voiceanswer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Слушает микрофон и ищет слова-команды из настроек.
 * [oneShot] = true: после первой найденной команды перестаёт слушать (так при звонке).
 * Всё работает в главном потоке; создавать и запускать — только пока экран приложения виден.
 */
class VoiceListener(
    context: Context,
    private val oneShot: Boolean,
    private val onCommand: (Logic.Command) -> Unit,
    private val onHeard: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val ctx: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var accept: List<String> = emptyList()
    private var reject: List<String> = emptyList()
    private var errors = 0
    private var preferOffline = true
    private var scoStarted = false
    private var fired = false
    private var lastHeard = ""

    var running = false
        private set

    fun start() {
        if (running) return
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStatus("Нет разрешения на микрофон")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onStatus("Нет службы распознавания речи — установите приложение «Google»")
            return
        }
        accept = Prefs.acceptWords(ctx)
        reject = Prefs.rejectWords(ctx)
        running = true
        fired = false
        errors = 0
        lastHeard = ""
        startHeadsetMic()
        begin()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        stopHeadsetMic()
        onLevel(0f)
    }

    private fun begin() {
        if (!running) return
        destroyRecognizer()
        val sr = try {
            SpeechRecognizer.createSpeechRecognizer(ctx)
        } catch (e: Exception) {
            onStatus("Не удалось запустить распознавание")
            return
        }
        recognizer = sr
        sr.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        if (preferOffline) intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            schedule(800)
        }
    }

    private fun destroyRecognizer() {
        val sr = recognizer ?: return
        recognizer = null
        try {
            sr.cancel()
            sr.destroy()
        } catch (_: Exception) {
        }
    }

    private fun schedule(delayMs: Long) {
        if (!running) return
        handler.postDelayed({ begin() }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            onStatus("")
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (!running) return
            val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                onStatus("Нет разрешения на микрофон")
                return
            }
            if (error == 12 || error == 13) preferOffline = false // нет офлайн-языка: идём через интернет
            if (!quiet) {
                errors++
                if (errors >= 3) onStatus("Распознавание речи не отвечает (код $error). Проверьте интернет")
            } else {
                errors = 0
            }
            val delay = if (quiet) 120L else minOf(400L * errors, 3000L)
            schedule(delay)
        }

        override fun onResults(results: Bundle?) {
            if (!running) return
            if (!check(results)) schedule(100)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!running) return
            check(partialResults)
        }
    }

    private fun check(bundle: Bundle?): Boolean {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        val top = list.firstOrNull()
        if (!top.isNullOrBlank() && top != lastHeard) {
            lastHeard = top
            onHeard(top)
        }
        if (fired) return true
        val cmd = Logic.match(list, accept, reject) ?: return false
        fired = true
        if (oneShot) {
            // не уничтожаем распознаватель внутри его же колбэка
            handler.post { stop() }
        } else {
            handler.postDelayed({ fired = false }, 1500)
        }
        onCommand(cmd)
        return true
    }

    // ---- микрофон Bluetooth-наушников (по возможности) ----

    private fun startHeadsetMic() {
        if (!Prefs.headsetMic(ctx)) return
        if (Calls.headsetKind(ctx) != Calls.Headset.BLUETOOTH) return
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            if (am.isBluetoothScoAvailableOffCall) {
                am.startBluetoothSco()
                scoStarted = true
            }
        } catch (_: Exception) {
        }
    }

    private fun stopHeadsetMic() {
        if (!scoStarted) return
        scoStarted = false
        try {
            ctx.getSystemService(AudioManager::class.java).stopBluetoothSco()
        } catch (_: Exception) {
        }
    }
}
