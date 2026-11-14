package com.example.voiceanswer

import android.content.Context
import android.content.SharedPreferences

/** Все настройки приложения. Читаются при каждом звонке, поэтому изменения действуют сразу. */
object Prefs {
    private fun p(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("voice_answer", Context.MODE_PRIVATE)

    private fun flag(c: Context, key: String, def: Boolean) = p(c).getBoolean(key, def)
    private fun setFlag(c: Context, key: String, v: Boolean) = p(c).edit().putBoolean(key, v).apply()

    // ---- Голос и звук ----
    const val K_VOICE = "voice"
    const val K_SPEAKER_VOICE = "speaker_voice"
    const val K_SPEAKER_BUTTON = "speaker_button"
    const val K_MAX_VOLUME = "max_volume"
    const val K_HEADSET_FIRST = "headset_first"
    const val K_HEADSET_MIC = "headset_mic"
    const val K_ANNOUNCE = "announce"
    const val K_QUIET_RINGER = "quiet_ringer"

    fun voiceEnabled(c: Context) = flag(c, K_VOICE, true)
    fun speakerOnVoice(c: Context) = flag(c, K_SPEAKER_VOICE, true)
    fun speakerOnButton(c: Context) = flag(c, K_SPEAKER_BUTTON, false)
    fun maxVolume(c: Context) = flag(c, K_MAX_VOLUME, true)
    fun headsetFirst(c: Context) = flag(c, K_HEADSET_FIRST, true)
    fun headsetMic(c: Context) = flag(c, K_HEADSET_MIC, true)
    fun announce(c: Context) = flag(c, K_ANNOUNCE, true)
    fun quietRinger(c: Context) = flag(c, K_QUIET_RINGER, false)

    fun get(c: Context, key: String): Boolean = when (key) {
        K_VOICE -> voiceEnabled(c)
        K_SPEAKER_VOICE -> speakerOnVoice(c)
        K_SPEAKER_BUTTON -> speakerOnButton(c)
        K_MAX_VOLUME -> maxVolume(c)
        K_HEADSET_FIRST -> headsetFirst(c)
        K_HEADSET_MIC -> headsetMic(c)
        K_ANNOUNCE -> announce(c)
        K_QUIET_RINGER -> quietRinger(c)
        else -> false
    }

    fun set(c: Context, key: String, v: Boolean) = setFlag(c, key, v)

    // ---- Слова-команды ----
    private const val K_ACCEPT_WORDS = "accept_words"
    private const val K_REJECT_WORDS = "reject_words"

    private fun words(c: Context, key: String, def: List<String>): List<String> {
        val s = p(c).getString(key, null) ?: return def
        return s.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun saveWords(c: Context, key: String, list: List<String>) {
        p(c).edit().putString(key, list.joinToString("\n")).apply()
    }

    fun acceptWords(c: Context): List<String> = words(c, K_ACCEPT_WORDS, Logic.DEFAULT_ACCEPT)
    fun rejectWords(c: Context): List<String> = words(c, K_REJECT_WORDS, Logic.DEFAULT_REJECT)

    /** Добавляет слово. false — если слово пустое или уже есть. */
    fun addWord(c: Context, accept: Boolean, raw: String): Boolean {
        val w = Logic.cleanWord(raw)
        if (w.isEmpty()) return false
        val key = if (accept) K_ACCEPT_WORDS else K_REJECT_WORDS
        val list = if (accept) acceptWords(c) else rejectWords(c)
        if (list.any { Logic.normalize(it) == w }) return false
        saveWords(c, key, list + w)
        return true
    }

    fun removeWord(c: Context, accept: Boolean, word: String) {
        val key = if (accept) K_ACCEPT_WORDS else K_REJECT_WORDS
        val list = if (accept) acceptWords(c) else rejectWords(c)
        saveWords(c, key, list.filter { it != word })
    }

    fun resetWords(c: Context) {
        p(c).edit().remove(K_ACCEPT_WORDS).remove(K_REJECT_WORDS).apply()
    }

    // ---- Оформление ----
    fun accentId(c: Context): String = p(c).getString("accent", "blue") ?: "blue"
    fun hue(c: Context): Int = p(c).getInt("hue", 280)
    fun bgMode(c: Context): String = p(c).getString("bg", "black") ?: "black"
    fun setAccent(c: Context, id: String) = p(c).edit().putString("accent", id).apply()
    fun setHue(c: Context, h: Int) = p(c).edit().putInt("hue", h).apply()
    fun setBg(c: Context, m: String) = p(c).edit().putString("bg", m).apply()

    /** Фон экрана звонка: "dark" (тёмный с оттенком темы), "black", "bright" (яркий цвет темы). */
    fun callBg(c: Context): String = p(c).getString("call_bg", "dark") ?: "dark"
    fun setCallBg(c: Context, m: String) = p(c).edit().putString("call_bg", m).apply()
}
