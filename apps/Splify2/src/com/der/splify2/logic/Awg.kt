/*
 * Выход WireGuard/AmneziaWG (kind: awg движка): разбор файла awg-quick/wg-quick и его хранение.
 *
 * ПОЧЕМУ СВОЙ РАЗБОР, ЕСЛИ ДВИЖОК И ТАК ЧИТАЕТ ФАЙЛ. Движок разбирает файл при apply, а о
 * неудаче говорит строкой журнала, и `check`/`apply --dry-run` от ошибки в файле не падают —
 * только предупреждают (steer/src/awg.c, awg_check_all). Человек, вставивший файл с опечаткой,
 * узнал бы о ней по туннелю, который не встал, да ещё словами журнала. Здесь тот же разбор идёт
 * в момент добавления: незнакомый параметр, битый ключ, Endpoint без порта — отказ сразу, с
 * номером строки и именем параметра. Правила — ровно те, что у awg_conf_parse (steer/src/awg.c):
 * те же разделы и имена без учёта регистра, те же пределы чисел, тот же строгий base64 ключей,
 * та же обрезка пробелов (только пробел, табуляция и \r), тот же порядок итоговых проверок.
 * Стенд сверяет вердикт этого разбора с вердиктом движка на одних и тех же файлах
 * (tests/logic/LogicTest.kt, testAwg): разойдись они — стенд красный.
 *
 * СЕКРЕТЫ. Приватный ключ, PresharedKey и HeaderProtectionKey разбор проверяет и тут же
 * забывает: наружу (экрану, в модель, в сообщения об ошибках) идёт только AwgInfo — адрес
 * сервера, число пиров, обфускация, MTU, адреса интерфейса. Сам текст лежит в файлах
 * приложения (AwgStore) и уходит только движку командой put-file и в резервную копию.
 *
 * ПОЧЕМУ ФАЙЛ ЗАЛИВАЕТСЯ, А НЕ КЛАДЁТСЯ В СПЕКУ. Спеку движок печатает в status и diag, и она
 * уходит в резервные копии; ключам там не место (контракт steer, kind: awg). Поэтому в спеке
 * только путь, а файл едет той же дорогой, что списки: put-file в каталог движка.
 */
package com.der.splify2.logic

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Несекретное описание файла — то, что видит экран и что лежит в модели. */
data class AwgInfo(
    /** Сервер первого пира, как записан в файле: «хост:порт» или «[IPv6]:порт»; null — не задан. */
    val endpoint: String?,
    val peers: Int,
    /** Включена ли обфускация AmneziaWG (тот же признак, по которому движок выбирает модуль). */
    val obfs: Boolean,
    /** MTU из файла; null — не задан (движок ставит 1420, как wg-quick). */
    val mtu: Int?,
    val addresses: List<String>,
    /** Строки файла, которые движок принимает, но не исполняет: DNS, Table, FwMark, PreUp…, SaveConfig. */
    val ignored: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("endpoint", endpoint ?: JSONObject.NULL)
        .put("peers", peers)
        .put("obfs", obfs)
        .put("mtu", mtu ?: JSONObject.NULL)
        .put("addresses", jsonArrayOf(addresses))
        .put("ignored", jsonArrayOf(ignored))
}

internal object AwgConf {
    /** Как у движка (awg_conf_load): пять I по 16 КБ и тысяча AllowedIPs — с запасом. */
    const val MAX_BYTES = 256 * 1024
    private const val MAX_PEERS = 8
    private const val MAX_ADDRS = 8
    private const val MAX_AIPS = 1024
    private const val MAX_ISTR = 16384

    private val U16 = listOf("Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4")
    private val R16 = listOf("ContentPaddingAddition", "RekeyAfterTime", "RekeyTimeout", "RejectAfterTime", "KeepaliveTimeout", "MaxHandshakeAttempts")
    private val U8 = listOf("RandomTrailers", "DisableCookies")

    /** Текст, который увидит движок: без BOM и с переводами строк \n.
     *
     *  BOM приносят блокноты Windows: движок прочёл бы первую строку как «﻿[Interface]» —
     *  неизвестный раздел, и файл, который открывается везде, не встал бы здесь. \r\n движок
     *  пережил бы (trim срезает \r), но хэш в имени файла должен зависеть от содержимого, а не
     *  от того, каким редактором его сохранили. */
    fun normalize(raw: String): String {
        var s = raw.removePrefix("﻿").replace("\r\n", "\n")
        if (!s.endsWith("\n")) s += "\n"
        return s
    }

    private class Fail(msg: String) : Exception(msg)

    private fun fail(msg: String): Nothing = throw Fail(msg)

    /** Разобрать и проверить. Отказ — BridgeError("bad-args") с причиной для человека. */
    fun parse(text: String): AwgInfo {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES)
            throw BridgeError("bad-args", "Файл слишком большой для настроек WireGuard")
        val t = text.trim()
        // Частые промахи — до построчного разбора: их причина не в строке, а в том, что вставлено
        // вовсе не то, и «строка 1: нет знака «=»» человеку ничего бы не сказало.
        if (t.startsWith("vpn://"))
            throw BridgeError("bad-args", "Ключ vpn:// не подходит — выгрузите в AmneziaVPN настройки в формате AmneziaWG")
        if (t.startsWith("vless://") || t.startsWith("http://") || t.startsWith("https://"))
            throw BridgeError("bad-args", "Это ссылка, а не файл WireGuard — VLESS добавляется подпиской")
        if (t.lineSequence().none { trimC(it.substringBefore('#')).equals("[Interface]", ignoreCase = true) })
            throw BridgeError("bad-args", "Это не файл WireGuard: нет раздела [Interface]")
        return try {
            parseStrict(text)
        } catch (e: Fail) {
            throw BridgeError("bad-args", "Файл WireGuard не подходит: ${e.message}")
        }
    }

    private class Peer {
        var pub: ByteArray? = null
        var endpoint: String? = null
        var keepLo = 0L
        var keepHi = 0L
        var adv = false
    }

    private fun parseStrict(text: String): AwgInfo {
        var sec = 0            // 0 — вне раздела, 1 — [Interface], 2 — [Peer]
        var seenIf = false
        var hasPriv = false
        var hasHpk = false
        val addrs = ArrayList<String>()
        var mtu: Int? = null
        val ignored = LinkedHashSet<String>()
        val u16 = HashMap<Int, Long>()
        val h = HashMap<Int, Pair<Long, Long>>()
        val istr = HashSet<Int>()
        val r16 = HashMap<Int, Pair<Long, Long>>()
        val u8 = HashMap<Int, Boolean>()
        val peers = ArrayList<Peer>()
        var aips = 0
        var pe: Peer? = null

        // Строки — по \n, как у движка; \r на конце срезает trim.
        val lines = text.split('\n')
        // split даёт пустой хвост после последнего \n — у движка такой строки нет, и номер
        // строки в сообщении не должен уехать.
        val n = if (text.endsWith("\n")) lines.size - 1 else lines.size
        for (idx in 0 until n) {
            val no = idx + 1
            val raw = lines[idx]
            if (raw.toByteArray(Charsets.UTF_8).size >= MAX_ISTR + 256) fail("строка $no длиннее допустимого")
            val l = trimC(raw.substringBefore('#'))
            if (l.isEmpty()) continue
            if (l.startsWith("[")) {
                when {
                    l.equals("[Interface]", ignoreCase = true) -> {
                        if (seenIf) fail("строка $no: второй раздел [Interface]")
                        sec = 1; seenIf = true
                    }
                    l.equals("[Peer]", ignoreCase = true) -> {
                        if (peers.size >= MAX_PEERS) fail("строка $no: пиров больше $MAX_PEERS")
                        pe = Peer().also { peers.add(it) }
                        sec = 2
                    }
                    else -> fail("строка $no: неизвестный раздел")
                }
                continue
            }
            val eq = l.indexOf('=')
            if (eq < 0) fail("строка $no: нет знака «=»")
            val key = trimC(l.substring(0, eq))
            val v = trimC(l.substring(eq + 1))
            if (key.isEmpty()) fail("строка $no: пустое имя параметра")
            // Имя параметра идёт в сообщение — и только оно, никогда значение (там бывает ключ).
            val kn = key.take(32)
            if (sec == 0) fail("строка $no: параметр $kn вне раздела")
            fun k(s: String) = key.equals(s, ignoreCase = true)

            if (sec == 1) {
                when {
                    k("PrivateKey") -> {
                        if (key32(v) == null) fail("строка $no: PrivateKey — не ключ WireGuard (44 знака base64)")
                        hasPriv = true
                    }
                    k("Address") -> for (p in v.split(',')) {
                        val x = trimC(p)
                        if (x.isEmpty()) continue
                        if (addrs.size >= MAX_ADDRS) fail("строка $no: адресов больше $MAX_ADDRS")
                        if (!prefixOk(x)) fail("строка $no: Address — не адрес или адрес/префикс")
                        addrs.add(x)
                    }
                    // 1280 — меньше IPv6 не живёт.
                    k("MTU") -> mtu = num(v, 1280, 65535)?.toInt() ?: fail("строка $no: MTU — число от 1280 до 65535")
                    k("ListenPort") -> num(v, 0, 65535) ?: fail("строка $no: ListenPort — число от 0 до 65535")
                    k("DNS") -> ignored.add("DNS")
                    k("Table") -> ignored.add("Table")
                    k("FwMark") -> ignored.add("FwMark")
                    k("PreUp") || k("PostUp") || k("PreDown") || k("PostDown") -> ignored.add("PreUp/PostUp")
                    k("SaveConfig") -> ignored.add("SaveConfig")
                    k("H1") || k("H2") || k("H3") || k("H4") -> {
                        val r = range(v, 0xffffffffL) ?: fail("строка $no: $kn — число или диапазон N-M до 4294967295")
                        h[key[1] - '1'] = r
                    }
                    (key[0] == 'I' || key[0] == 'i') && key.length == 2 && key[1] in '1'..'5' -> {
                        if (v.isEmpty()) continue   // пустое значение — как отсутствие
                        if (v.toByteArray(Charsets.UTF_8).size >= MAX_ISTR) fail("строка $no: $kn длиннее допустимого")
                        istr.add(key[1] - '1')
                    }
                    k("HeaderProtectionKey") -> {
                        if (key32(v) == null) fail("строка $no: HeaderProtectionKey — не ключ (44 знака base64)")
                        hasHpk = true
                    }
                    else -> {
                        val a = U16.indexOfFirst { k(it) }
                        val b = R16.indexOfFirst { k(it) }
                        val c = U8.indexOfFirst { k(it) }
                        when {
                            a >= 0 -> u16[a] = num(v, 0, 65535) ?: fail("строка $no: $kn — число от 0 до 65535")
                            b >= 0 -> r16[b] = range(v, 65535) ?: fail("строка $no: $kn — число или диапазон N-M до 65535")
                            c >= 0 -> u8[c] = bool(v) ?: fail("строка $no: $kn — true или false")
                            else -> fail("строка $no: неизвестный параметр $kn в [Interface]")
                        }
                    }
                }
            } else {
                val p = pe!!
                when {
                    k("PublicKey") -> p.pub = key32(v) ?: fail("строка $no: PublicKey — не ключ WireGuard (44 знака base64)")
                    k("PresharedKey") -> key32(v) ?: fail("строка $no: PresharedKey — не ключ (44 знака base64)")
                    k("Endpoint") -> p.endpoint = if (endpointOk(v)) v else fail("строка $no: Endpoint — хост:порт или [IPv6]:порт")
                    k("AllowedIPs") -> for (x0 in v.split(',')) {
                        val x = trimC(x0)
                        if (x.isEmpty()) continue
                        if (aips >= MAX_AIPS) fail("строка $no: AllowedIPs больше $MAX_AIPS на файл")
                        if (!prefixOk(x)) fail("строка $no: AllowedIPs — адрес или адрес/префикс")
                        aips++
                    }
                    k("PersistentKeepalive") -> {
                        val r = if (v.equals("off", ignoreCase = true)) 0L to 0L
                            else range(v, 65535) ?: fail("строка $no: PersistentKeepalive — секунды, диапазон N-M или off")
                        p.keepLo = r.first; p.keepHi = r.second
                    }
                    k("AdvancedSecurity") -> p.adv = bool(v) ?: fail("строка $no: AdvancedSecurity — true или false")
                    else -> fail("строка $no: неизвестный параметр $kn в [Peer]")
                }
            }
        }

        if (!seenIf) fail("нет раздела [Interface]")
        if (!hasPriv) fail("в [Interface] нет PrivateKey")
        if (peers.isEmpty()) fail("нет ни одного [Peer]")
        for ((i, p) in peers.withIndex()) {
            // Нулевой ключ WireGuard недопустим, и у движка он же значит «не задан».
            val pub = p.pub
            if (pub == null || pub.all { it.toInt() == 0 }) fail("у [Peer] №${i + 1} нет PublicKey")
            for (j in 0 until i) if (peers[j].pub?.contentEquals(pub) == true)
                fail("PublicKey [Peer] №${i + 1} повторяет №${j + 1}")
        }
        // Проверки, на которые ядро отвечает одним EINVAL без объяснения.
        val jmin = u16[1]
        val jmax = u16[2]
        if (jmin != null && jmax != null && jmin > jmax) fail("Jmin больше Jmax")
        for (a in 0 until 4) for (b in a + 1 until 4) {
            val x = h[a] ?: continue
            val y = h[b] ?: continue
            if (x.first <= y.second && y.first <= x.second) fail("H${a + 1} и H${b + 1} пересекаются — модуль их не примет")
        }

        // Обфускация — как awg_conf_needs_awg: всё, что обычный WireGuard не выразит.
        val obfs = u16.values.any { it != 0L } ||
            h.any { (k, r) -> !(r.first == (k + 1).toLong() && r.second == (k + 1).toLong()) } ||
            istr.isNotEmpty() ||
            r16.values.any { it.first != 0L || it.second != 0L } ||
            u8.values.any { it } || hasHpk ||
            peers.any { it.adv || it.keepLo != it.keepHi }

        return AwgInfo(peers[0].endpoint, peers.size, obfs, mtu, addrs, ignored.toList())
    }

    // ---- помощники: повторяют awg.c ---------------------------------------------------

    /** trim движка: только пробел, табуляция и \r (и \n в хвосте) — не вся «пустота» Unicode. */
    internal fun trimC(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && (s[a] == ' ' || s[a] == '\t' || s[a] == '\r')) a++
        while (b > a && (s[b - 1] == ' ' || s[b - 1] == '\t' || s[b - 1] == '\r' || s[b - 1] == '\n')) b--
        return s.substring(a, b)
    }

    /** parse_num: вся строка — цифры, не длиннее 10, в [lo, hi]. */
    private fun num(s: String, lo: Long, hi: Long): Long? {
        if (s.isEmpty() || s.length > 10 || !s.all { it in '0'..'9' }) return null
        val v = s.toLong()
        return if (v in lo..hi) v else null
    }

    /** parse_range: «N» или «N-M», N ≤ M ≤ max. */
    private fun range(s: String, max: Long): Pair<Long, Long>? {
        if (s.toByteArray(Charsets.UTF_8).size >= 32) return null
        val d = s.indexOf('-')
        val lo = num(trimC(if (d < 0) s else s.substring(0, d)), 0, max) ?: return null
        if (d < 0) return lo to lo
        val hi = num(trimC(s.substring(d + 1)), 0, max) ?: return null
        return if (hi < lo) null else lo to hi
    }

    private fun bool(s: String): Boolean? = when {
        s.equals("true", true) || s.equals("on", true) || s.equals("yes", true) || s == "1" -> true
        s.equals("false", true) || s.equals("off", true) || s.equals("no", true) || s == "0" -> false
        else -> null
    }

    private fun b64v(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> -1
    }

    /** key_from_b64: ровно 44 знака, последний '=', лишние биты хвоста — отказ (как у wg). */
    internal fun key32(s: String): ByteArray? {
        if (s.length != 44 || s[43] != '=') return null
        val out = ByteArray(32)
        var o = 0
        var acc = 0
        for (i in 0 until 43) {
            val v = b64v(s[i])
            if (v < 0) return null
            acc = (acc shl 6) or v
            if (i % 4 == 3) {
                out[o++] = (acc shr 16).toByte(); out[o++] = (acc shr 8).toByte(); out[o++] = acc.toByte()
                acc = 0
            }
        }
        if (acc and 3 != 0) return null
        out[o++] = (acc shr 10).toByte()
        out[o++] = (acc shr 2).toByte()
        return if (o == 32) out else null
    }

    /** inet_pton(AF_INET): четыре десятичных октета до 255, без ведущих нулей. */
    internal fun ipv4(s: String): Boolean {
        val p = s.split('.')
        if (p.size != 4) return false
        for (x in p) {
            if (x.isEmpty() || x.length > 3 || !x.all { it in '0'..'9' }) return false
            if (x.length > 1 && x[0] == '0') return false
            if (x.toInt() > 255) return false
        }
        return true
    }

    /** inet_pton(AF_INET6): группы до четырёх шестнадцатеричных знаков, одно «::», IPv4 в хвосте. */
    internal fun ipv6(s: String): Boolean {
        if (s.isEmpty() || s.length > 45) return false
        val dbl = s.indexOf("::")
        if (dbl >= 0 && s.indexOf("::", dbl + 1) >= 0) return false
        fun groups(part: String, allowV4Tail: Boolean): Int? {
            if (part.isEmpty()) return 0
            val gs = part.split(':')
            var n = 0
            for ((i, g) in gs.withIndex()) {
                if (i == gs.size - 1 && allowV4Tail && g.contains('.')) {
                    if (!ipv4(g)) return null
                    n += 2
                    continue
                }
                if (g.isEmpty() || g.length > 4 || !g.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                n++
            }
            return n
        }
        return if (dbl < 0) {
            groups(s, true) == 8
        } else {
            val head = groups(s.substring(0, dbl), false) ?: return false
            val tail = groups(s.substring(dbl + 2), true) ?: return false
            // «::» заменяет хотя бы одну группу (glibc: tp == endp — отказ).
            head + tail <= 7
        }
    }

    /** parse_prefix: «адрес[/префикс]». */
    private fun prefixOk(s: String): Boolean {
        if (s.toByteArray(Charsets.UTF_8).size >= 64) return false
        val sl = s.indexOf('/')
        val a = if (sl < 0) s else s.substring(0, sl)
        val max = when {
            ipv4(a) -> 32L
            ipv6(a) -> 128L
            else -> return false
        }
        if (sl >= 0) num(s.substring(sl + 1), 0, max) ?: return false
        return true
    }

    /** parse_endpoint: «хост:порт» или «[IPv6]:порт»; IPv6 без скобок — отказ. */
    internal fun endpointOk(s: String): Boolean {
        val host: String
        val port: String
        if (s.startsWith("[")) {
            val rb = s.indexOf(']')
            if (rb < 0 || rb + 1 >= s.length || s[rb + 1] != ':' || rb - 1 >= 256) return false
            host = s.substring(1, rb)
            if (!ipv6(host)) return false
            port = s.substring(rb + 2)
        } else {
            val c = s.lastIndexOf(':')
            if (c <= 0 || c >= 256) return false
            host = s.substring(0, c)
            if (host.contains(':')) return false
            port = s.substring(c + 1)
        }
        return num(port, 1, 65535) != null
    }
}

/** Файлы WireGuard в данных приложения: files/awg/<id>.conf, id — начало SHA-256 текста.
 *
 *  Имя по содержимому, а не по имени выхода — по той же причине, что у подписок (sub-<id>-<хеш>):
 *  движку файл уходит под этим же именем, и новое содержимое — это новое имя. Прежний файл у
 *  движка остаётся, пока на него ссылается сохранённая спека, а спека, пришедшая после, уже
 *  называет новый — старый и новый ключи не смешиваются ни на мгновение. */
internal class AwgStore(filesDir: File) {
    private val dir = File(filesDir, "awg")

    companion object {
        val ID = Regex("^[0-9a-f]{16}$")
        fun engineName(id: String) = "awg-$id.conf"
        /** id из имени файла движка (awg-<id>.conf) или null. */
        fun idOf(engineName: String): String? =
            engineName.removePrefix("awg-").removeSuffix(".conf").takeIf { engineName.startsWith("awg-") && ID.matches(it) }
    }

    private fun file(id: String) = File(dir, "$id.conf")

    /** Проверить и сохранить. Возвращает id и описание. */
    fun put(raw: String): Pair<String, AwgInfo> {
        val text = AwgConf.normalize(raw)
        val info = AwgConf.parse(text)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val id = sha256hex(bytes).take(16)
        val f = file(id)
        if (!f.isFile || !f.readBytes().contentEquals(bytes)) Files.write(f, bytes)
        return id to info
    }

    fun has(id: String) = ID.matches(id) && file(id).isFile

    fun bytes(id: String): ByteArray? = if (has(id)) file(id).readBytes() else null

    fun text(id: String): String? = bytes(id)?.toString(Charsets.UTF_8)

    /** Описание из сохранённого файла; null — файла нет или он больше не разбирается. */
    fun info(id: String): AwgInfo? = text(id)?.let { try { AwgConf.parse(it) } catch (e: BridgeError) { null } }

    /** Убрать файлы, на которые не ссылается ни модель, ни применённый снимок.
     *
     *  Только старше суток: файл добавленного выхода живёт в черновике экрана, пока человек не
     *  нажмёт «Применить», и в модели его ещё нет. Сутки — с запасом на любой черновик. */
    fun prune(keep: Set<String>, now: Long = System.currentTimeMillis()) {
        dir.listFiles()?.forEach { f ->
            val id = f.name.removeSuffix(".conf")
            if (id !in keep && now - f.lastModified() > 24 * 3600 * 1000L) f.delete()
        }
    }
}

/** Текст из JSON-описания (модель от экрана) — терпимо: это подпись, а не настройка. Правду
 *  Dispatcher всё равно берёт из сохранённого файла (settingsPut). */
internal fun awgInfoOf(o: JSONObject?): AwgInfo? {
    if (o == null) return null
    fun strs(a: JSONArray?) = a.strings().take(16).map { it.take(64) }
    return AwgInfo(
        endpoint = o.str("endpoint")?.take(300),
        peers = o.optInt("peers", 0).coerceIn(0, 8),
        obfs = o.optBoolean("obfs", false),
        mtu = if (o.has("mtu") && !o.isNull("mtu")) o.optInt("mtu") else null,
        addresses = strs(o.optJSONArray("addresses")),
        ignored = strs(o.optJSONArray("ignored")),
    )
}
