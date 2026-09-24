/*
 * Текст списков: что принимаем от человека, что — от издателя, и когда скачанное не ставим.
 *
 * Перенос трёх решений роутера, каждое со своей причиной:
 *
 *   sanitize  — common.sh, sanitize_list: свои списки человека (lists.custom put, импорт);
 *   looksLike — splify2-update-lists, проверка скачанного по виду строк;
 *   shrank    — splify2-update-lists, отказ на кратной просадке объёма.
 */
package com.der.splify2.logic

internal object ListText {
    enum class Kind { DOMAINS, PREFIXES }

    class Sanitized(val lines: List<String>, val ok: Int, val bad: Int)

    private val IP4 = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+$")
    private val CIDR4 = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+/[0-9]+$")
    private val DOMAIN = Regex("^[a-z0-9]([a-z0-9._-]*[a-z0-9])?$")

    /** Чужой текст на входе движка — свои списки человека.
     *
     *  Проверяется ЗДЕСЬ, а не в экране: экран можно обойти, а последствие непроверенного
     *  списка молчаливое — спека применится, ядро отвергнет набор, и канал останется пустым без
     *  слова о причине. Почти-верное принимаем, а не отвергаем из принципа: одиночный адрес
     *  дополняем до /32 (человек пишет 1.2.3.4, имея в виду именно его), домены приводим к
     *  нижнему регистру и снимаем ведущее «*.» и хвостовую точку. Что отбросили — считаем:
     *  молчаливая потеря строк была бы худшим из возможных поведений.
     *
     *  Проверяются и ДИАПАЗОНЫ значений, а не только форма: «10.0.0.256» и «192.168.0.0/40»
     *  форму проходят, движок их тоже пропускает (он смотрит на цифры, точки и слэши), а
     *  отвергает их уже ядро — целиком весь набор правил одной транзакцией. */
    fun sanitize(kind: Kind, input: List<String>): Sanitized {
        val out = LinkedHashSet<String>()
        var ok = 0
        var bad = 0
        for (raw in input) for (piece in raw.split('\n')) {
            var line = piece.removeSuffix("\r")
            val hash = line.indexOf('#')
            if (hash >= 0) line = line.substring(0, hash)
            line = line.trim()
            if (line.isEmpty()) continue
            if (kind == Kind.PREFIXES) {
                when {
                    IP4.matches(line) -> if (Model.addrOk(line)) { out.add("$line/32"); ok++ } else bad++
                    CIDR4.matches(line) -> if (Model.addrOk(line)) { out.add(line); ok++ } else bad++
                    else -> bad++
                }
                continue
            }
            line = line.lowercase()
            if (line.startsWith("*.")) line = line.substring(2)
            if (line.endsWith(".")) line = line.dropLast(1)
            if (DOMAIN.matches(line) && line.contains('.') && line.length <= 253) { out.add(line); ok++ } else bad++
        }
        return Sanitized(out.toList(), ok, bad)
    }

    /** Смешанный текст (как в файлах splify2-lists: «домены и подсети вперемешку») — на два вида.
     *  Строка, похожая на адрес, уходит в подсети, остальное — в домены; дальше каждый вид
     *  проходит свой санитайзер. */
    fun split(text: String): Pair<List<String>, List<String>> {
        val d = ArrayList<String>()
        val p = ArrayList<String>()
        for (piece in text.split('\n')) {
            val t = piece.substringBefore('#').trim()
            if (t.isEmpty()) continue
            if (t.first().isDigit() && (IP4.matches(t) || CIDR4.matches(t))) p.add(t) else d.add(t)
        }
        return d to p
    }

    private val IP_LINE = Regex("^\\s*([0-9]{1,3}\\.){3}[0-9]{1,3}(/[0-9]{1,2})?(\\s*-\\s*([0-9]{1,3}\\.){3}[0-9]{1,3})?\\s*$")
    private val DOM_LINE = Regex("^\\s*[=*]?[A-Za-z0-9_.*?-]+\\.[A-Za-z0-9_*?-]{2,}\\s*$")
    private val RE_LINE = Regex("^\\s*(re:.*)?$")
    private val SKIP_LINE = Regex("^\\s*([#;].*)?$")

    /** Сколько строк скачанного НЕ похожи на записи своего вида.
     *
     *  Заглушка провайдера или captive portal отдают 200 и HTML; без этой проверки рабочий
     *  список подменялся страницей, и канал переставал совпадать с чем-либо. Выражения те же,
     *  что у роутера (splify2-update-lists), включая доменный синтаксис движка: `=точное`,
     *  `*шаблон`, `re:выражение`. */
    fun badLines(kind: Kind, text: String): Int {
        var bad = 0
        for (line in text.split('\n')) {
            val l = line.removeSuffix("\r")
            if (SKIP_LINE.matches(l)) continue
            val good = if (kind == Kind.PREFIXES) IP_LINE.matches(l) else DOM_LINE.matches(l) || RE_LINE.matches(l)
            if (!good) bad++
        }
        return bad
    }

    /** Записей в тексте: пустые строки и комментарии не считаются. */
    fun records(text: String?): Int {
        if (text == null) return 0
        var n = 0
        for (line in text.split('\n')) if (!SKIP_LINE.matches(line.removeSuffix("\r"))) n++
        return n
    }

    const val SHRINK_FACTOR = 4
    const val SHRINK_MIN = 100

    /** Кратная просадка: «записей стало кратно меньше», а не «меньше на N процентов».
     *
     *  Проценты врут в обе стороны. Состав категории у издателя законно меняется на десятки
     *  процентов (на роутере замерено: tiktok между двумя здоровыми сборками разошёлся на 42%),
     *  а беда, ради которой рубеж заведён, выглядит иначе — пусто или впятеро меньше (просевшие
     *  сборки издателя: 77 префиксов против 420). Мелкие списки исключены порогом: у списка из
     *  восьми строк кратное падение — обычная жизнь. Пусто вместо записей — это не «стал
     *  меньше», а «списка не стало», и порогом не снимается. */
    fun shrank(newCount: Int, prevCount: Int): Boolean {
        if (prevCount <= 0) return false
        if (newCount == 0) return true
        if (prevCount < SHRINK_MIN) return false
        return newCount * SHRINK_FACTOR < prevCount
    }
}
