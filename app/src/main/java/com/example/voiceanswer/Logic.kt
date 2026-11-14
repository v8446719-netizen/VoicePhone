package com.example.voiceanswer

import java.util.Locale

/** Чистая логика без Android — её можно проверить обычным тестом. */
object Logic {

    val DEFAULT_ACCEPT = listOf(
        "принять", "прими", "принимаю", "ответить", "ответ", "отвечай", "возьми трубку", "подними трубку"
    )
    val DEFAULT_REJECT = listOf(
        "отклонить", "отклони", "отмена", "отменить", "сбросить", "сбрось", "отбой"
    )

    enum class Command { ACCEPT, REJECT }

    private val RU = Locale.forLanguageTag("ru")
    private val junk = Regex("[^\\p{L}\\p{N} ]")
    private val spaces = Regex("\\s+")

    /** Нижний регистр, «ё» → «е», без знаков препинания, одиночные пробелы. */
    fun normalize(s: String): String =
        s.lowercase(RU).replace('ё', 'е').replace(junk, " ").replace(spaces, " ").trim()

    private fun firstIndex(phrase: String, words: List<String>): Int {
        var best = -1
        for (w in words) {
            val n = normalize(w)
            if (n.isEmpty()) continue
            val i = phrase.indexOf(n)
            if (i >= 0 && (best < 0 || i < best)) best = i
        }
        return best
    }

    /**
     * Ищет слово-команду в списке вариантов распознавания (первый — самый вероятный).
     * Берётся первый вариант, где что-то нашлось; если в нём есть и «принять», и «отклонить» —
     * побеждает то, что сказано раньше.
     */
    fun match(phrases: List<String>, accept: List<String>, reject: List<String>): Command? {
        for (raw in phrases) {
            val p = normalize(raw)
            if (p.isEmpty()) continue
            val a = firstIndex(p, accept)
            val r = firstIndex(p, reject)
            if (a < 0 && r < 0) continue
            return if (r >= 0 && (a < 0 || r <= a)) Command.REJECT else Command.ACCEPT
        }
        return null
    }

    /** Приводит слово из настроек к виду, в котором оно хранится. Пусто — если слово не годится. */
    fun cleanWord(s: String): String = normalize(s).take(40)

    fun plural(n: Int, one: String, few: String, many: String): String {
        val m100 = n % 100
        val m10 = n % 10
        return if (m100 in 11..19) many else when (m10) {
            1 -> one
            2, 3, 4 -> few
            else -> many
        }
    }

    /** «89967350717» → «8, 9 9 6, 7 3 5, 0 7, 1 7» — чтобы голос читал номер по цифрам. */
    fun spellNumber(raw: String): String {
        val d = raw.filter { it.isDigit() }
        if (d.isEmpty()) return ""
        val sizes: List<Int> = when (d.length) {
            11 -> listOf(1, 3, 3, 2, 2)
            10 -> listOf(3, 3, 2, 2)
            else -> List((d.length + 2) / 3) { 3 }
        }
        val parts = ArrayList<String>()
        var pos = 0
        for (s in sizes) {
            if (pos >= d.length) break
            val end = minOf(pos + s, d.length)
            parts.add(d.substring(pos, end).toList().joinToString(" "))
            pos = end
        }
        return parts.joinToString(", ")
    }

    fun formatDuration(totalSec: Long): String {
        val s = if (totalSec < 0) 0 else totalSec
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%02d:%02d", m, sec)
    }

    /** Буквы для аватарки: «Дядя Сережа» → «ДС», «Мама» → «М», номер → пусто. */
    fun initials(name: String?): String {
        if (name.isNullOrBlank()) return ""
        val words = name.trim().split(" ").filter { it.isNotEmpty() }
        val letters = words.mapNotNull { w -> w.firstOrNull { it.isLetter() } }
        if (letters.isEmpty()) return ""
        return letters.take(2).joinToString("").uppercase(RU)
    }
}
