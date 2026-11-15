package com.example.voiceanswer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Постоянно слушает слово-активатор: «Абобус, позвони маме» — и звонит, ничего нажимать не надо.
 * Android требует, чтобы приложение, слушающее микрофон в фоне, показывало значок-уведомление;
 * мы делаем его минимальным и бесшумным. Во время разговора прослушивание на паузе.
 */
class WakeService : Service(), Calls.Listener {

    companion object {
        private const val CH = "wake_listen"
        private const val ID = 103
        private const val ACTION_STOP = "com.example.voicephone.WAKE_STOP"

        /** Включает или выключает службу согласно настройке. Вызывать, пока приложение на экране. */
        fun sync(ctx: Context) {
            val i = Intent(ctx, WakeService::class.java)
            if (Prefs.wakeOn(ctx) && Perms.has(ctx, android.Manifest.permission.RECORD_AUDIO)) {
                try {
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                } catch (_: Exception) {
                }
            } else {
                ctx.stopService(i)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var listener: VoiceListener? = null
    private var quiet: WakeListener? = null
    @Volatile private var names: List<String> = emptyList()

    private fun loadNames() {
        io.execute {
            try {
                names = Repo.contacts(this).map { it.name }
            } catch (_: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VoiceListener.restoreSound(this)
        if (intent?.action == ACTION_STOP) {
            Prefs.set(this, Prefs.K_WAKE, false)
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        loadNames()
        Calls.addListener(this)
        refresh()
        return START_STICKY
    }

    override fun onDestroy() {
        Calls.removeListener(this)
        stopListening()
        io.shutdown()
        super.onDestroy()
    }

    override fun onCallsChanged() {
        main.post { refresh() }
    }

    private fun refresh() {
        loadNames()
        if (Calls.calls.isEmpty()) startListening() else stopListening()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH, "Голосовой набор", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            ch.lockscreenVisibility = Notification.VISIBILITY_SECRET
            nm.createNotificationChannel(ch)
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Скажите: «" + Prefs.wakeWord(this) + ", позвони …»")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Выключить", stop).build())
        val n = b.build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(ID, n)
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun startListening() {
        if (listener?.running == true || quiet?.running == true) return
        val biasList = { listOf(Prefs.wakeWord(this) + " позвони", "позвони", "набери") + names.take(150) }
        if (Build.VERSION.SDK_INT >= 33 && !Prefs.pipeBroken(this)) {
            val q = WakeListener(
                this,
                onPhrase = { phrases -> handle(phrases) },
                bias = biasList,
                onBroken = {
                    // этот телефон не принимает звук напрямую — переходим на обычное прослушивание
                    Prefs.setPipeBroken(this, true)
                    stopListening()
                    refresh()
                }
            )
            quiet = q
            if (q.start()) return
            quiet = null
        }
        val l = VoiceListener(
            this, false,
            onCommand = {}, onHeard = {}, onLevel = {}, onStatus = {},
            onPhrase = { phrases -> handle(phrases) },
            smartMute = true,
            bias = biasList
        )
        listener = l
        l.start()
    }

    private fun stopListening() {
        listener?.stop()
        listener = null
        quiet?.stop()
        quiet = null
    }

    /** true, если фраза была командой звонка. */
    private fun handle(phrases: List<String>): Boolean {
        val wake = Prefs.wakeWord(this)
        var query: String? = null
        for (p in phrases) {
            query = Logic.parseWake(p, wake)
            if (query != null) break
        }
        val q = query ?: return false
        io.execute {
            val contacts = Repo.contacts(this)
            val found = Logic.findNames(q, contacts.map { it.name })
            main.post {
                if (found.isEmpty()) {
                    Toast.makeText(this, "Не нашёл в контактах: «$q»", Toast.LENGTH_LONG).show()
                } else {
                    val c = contacts[found[0]]
                    val num = c.numbers.firstOrNull()?.number ?: c.number
                    call(c.name, num)
                }
            }
        }
        return true
    }

    private fun call(name: String, number: String) {
        if (number.isBlank() || !Perms.has(this, android.Manifest.permission.CALL_PHONE)) return
        try {
            Toast.makeText(this, "Звоню: $name", Toast.LENGTH_SHORT).show()
            getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось позвонить", Toast.LENGTH_SHORT).show()
        }
    }
}
