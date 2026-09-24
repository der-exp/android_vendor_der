/*
 * Чтение наборов правил sing-box (`.srs`) в синтаксис списков движка — перенос steer/src/srs.c.
 *
 * ПОЧЕМУ ЗДЕСЬ, А НЕ В ДВИЖКЕ. На роутере набор разбирает сам движок (`steer srs-read`), и так
 * было правильно: движок там — программа, которую объект rpcd зовёт с файлом. На телефоне
 * приложению нельзя ни исполнить движок, ни положить ему файл на разбор, а у управляющего сокета
 * команды `srs-read` нет. Каталог splify2-lists при этом почти целиком состоит из наборов
 * (`format: "srs"` — всё, кроме своих списков), то есть без разбора на телефоне каталог пуст.
 * Разбор не требует ни сети, ни криптографии, а распаковка zlib есть в самой платформе
 * (java.util.zip.Inflater) — поэтому перенос дешевле новой команды сокета.
 *
 * ЦЕНА ПЕРЕНОСА — два разборщика одного формата, и расходиться им нельзя: ошибка в разборе
 * succinct-дерева не падает, а отдаёт правдоподобные, но НЕ ТЕ имена. Поэтому стенд
 * (tests/logic) сверяет вывод этого файла с выводом `steer srs-read` на настоящих наборах
 * издателя (steer/tests/srs) байт в байт. Правило для правок: меняется srs.c — меняется и
 * этот файл, и стенд это покажет.
 *
 * Раскладка формата, соответствие видов правил синтаксису списка и обе ловушки (слова карт
 * big-endian при битах little-endian внутри слова; ключи обращены) — в шапке srs.c, здесь не
 * повторяются. Коротко: "SRS" | версия 1..5 | zlib до конца файла; тело — uvarint число правил.
 */
package com.der.splify2.logic

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/** Сужение канала по протоколу и портам — в форме спеки (`50000-65535`, через тире). */
data class Narrow(val proto: String?, val ports: List<String>) {
    val empty get() = proto == null && ports.isEmpty()
}

internal class SrsResult(val domains: String, val prefixes: String, val narrow: Narrow?, val warnings: List<String>)

/** Отказ разбора. `unsupported` — набор понят, но списком не выразим (код 2 у srs-read):
 *  повторять загрузку бесполезно. Иначе — файл не наш или испорчен (код 1). */
internal class SrsError(val unsupported: Boolean, message: String) : Exception(message)

internal object Srs {
    private const val VER_MAX = 5
    private const val LBL_PREFIX = 0x0D
    private const val LBL_ROOT = 0x0A
    private const val MAX_KEY = 512
    private const val MAX_DEPTH = 512
    private const val MAX_META_PORTS = 32
    private const val META_PORT_LEN = 16

    private const val IT_QUERY_TYPE = 0
    private const val IT_NETWORK = 1
    private const val IT_DOMAIN = 2
    private const val IT_DOMAIN_KEYWORD = 3
    private const val IT_DOMAIN_REGEX = 4
    private const val IT_SOURCE_IP_CIDR = 5
    private const val IT_IP_CIDR = 6
    private const val IT_SOURCE_PORT = 7
    private const val IT_SOURCE_PORT_RANGE = 8
    private const val IT_PORT = 9
    private const val IT_PORT_RANGE = 10
    private const val IT_ADGUARD_DOMAIN = 16
    private const val IT_NET_EXPENSIVE = 19
    private const val IT_NET_CONSTRAINED = 20
    private const val IT_IFACE_ADDR = 21
    private const val IT_DEFAULT_IFACE_ADDR = 22
    private const val IT_FINAL = 0xFF
    private val IT_STRINGS_UNSUPPORTED = setOf(11, 12, 13, 14, 15, 17, 18, 23)

    /** Флаг ошибки на весь разбор, как `struct rd` в srs.c: испорченный файл — отказ, а не
     *  разбор половины. Читатели на выставленном флаге отдают нули и ничего не двигают. */
    private class Rd(val b: ByteArray, var p: Int, val end: Int) {
        var err = false
        fun take(n: Long): Int {
            if (err || n < 0 || end - p < n) { err = true; return -1 }
            val q = p
            p += n.toInt()
            return q
        }
        fun u8(): Int { val q = take(1); return if (q < 0) 0 else b[q].toInt() and 0xFF }
        fun uvarint(): Long {
            var v = 0L
            var shift = 0
            while (true) {
                val q = take(1)
                if (q < 0) return 0
                val c = b[q].toInt() and 0xFF
                v = v or ((c and 0x7F).toLong() shl shift)
                if (c and 0x80 == 0) return v
                shift += 7
                if (shift > 63) { err = true; return 0 }
            }
        }
        fun u64be(): Long {
            val q = take(8)
            if (q < 0) return 0
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[q + i].toLong() and 0xFF)
            return v
        }
        fun u64array(): LongArray? {
            val n = uvarint()
            if (err) return null
            if (n > 1_000_000L) { err = true; return null }
            val a = LongArray(n.toInt())
            for (i in a.indices) a[i] = u64be()
            return if (err) null else a
        }
    }

    private class Ctx {
        val dom = ByteArrayOutputStream()
        val pfx = StringBuilder()
        var unsupported = false
        var why = ""
        var sawV6 = false
        var haveTcp = false
        var haveUdp = false
        val ports = ArrayList<String>()
        var portsOver = false
        fun no(reason: String) { if (!unsupported) { unsupported = true; why = reason } }
        fun portAdd(s: String) {
            if (portsOver || s in ports) return
            if (ports.size >= MAX_META_PORTS) { portsOver = true; return }
            ports.add(s)
        }
    }

    private fun bitAt(w: LongArray, i: Long): Boolean {
        val idx = (i ushr 6).toInt()
        // leaves короче карты — добиваем нулями, как sing-box.
        if (i < 0 || idx >= w.size) return false
        return (w[idx] ushr (i and 63).toInt()) and 1L == 1L
    }

    private fun emitKey(out: ByteArrayOutputStream, key: ByteArray, n: Int) {
        if (n == 0) return
        var term = key[n - 1].toInt() and 0xFF
        var bodyN = n - 1
        if (term != LBL_ROOT && term != LBL_PREFIX) { term = 0; bodyN = n }
        if (bodyN == 0 || bodyN > MAX_KEY) return
        // Разворот побайтовый: двойной разворот байтов возвращает исходную последовательность,
        // хотя при записи sing-box разворачивал по рунам (см. srs.c).
        when (term) {
            LBL_PREFIX -> out.write('*'.code)
            LBL_ROOT -> {}
            else -> out.write('='.code)
        }
        for (i in 0 until bodyN) out.write(key[bodyN - 1 - i].toInt())
        out.write('\n'.code)
    }

    private fun readDomainMatcher(r: Rd, out: ByteArrayOutputStream): Boolean {
        r.u8()  // зарезервированный байт
        val leaves = r.u64array() ?: return false
        val bitmap = r.u64array() ?: return false
        val nlab = r.uvarint()
        if (r.err || nlab > 100_000_000L) { r.err = true; return false }
        val labOff = r.take(nlab)
        if (labOff < 0) return false
        val nbits = bitmap.size.toLong() * 64
        // Позиции единиц (select1) — единственный массив, нужный обходу (см. srs.c про rank1).
        var ones = IntArray(64)
        var nones = 0
        for (i in 0 until nbits) if (bitAt(bitmap, i)) {
            if (nones == ones.size) ones = ones.copyOf(ones.size * 2)
            ones[nones++] = i.toInt()
        }
        val sNode = IntArray(MAX_DEPTH)
        val sBm = IntArray(MAX_DEPTH)
        val sPlen = IntArray(MAX_DEPTH)
        val sLabel = ByteArray(MAX_DEPTH)
        val key = ByteArray(MAX_KEY)
        var sp = 0
        sNode[0] = 0; sBm[0] = 0; sPlen[0] = 0; sLabel[0] = 0; sp = 1
        while (sp > 0) {
            sp--
            val node = sNode[sp]; val bm = sBm[sp]; val plen = sPlen[sp]; val label = sLabel[sp]
            if (plen > 0) key[plen - 1] = label
            if (bitAt(leaves, node.toLong())) emitKey(out, key, plen)
            var zeros = bm.toLong() - node.toLong()
            var i = bm.toLong()
            while (i < nbits && !bitAt(bitmap, i)) {
                if (zeros < 0 || zeros >= nlab) { r.err = true; return false }   // карта врёт про метки
                val child = zeros + 1
                if (child > nones) { r.err = true; return false }
                if (sp >= MAX_DEPTH || plen >= MAX_KEY) { r.err = true; return false }
                sNode[sp] = child.toInt()
                sBm[sp] = ones[(child - 1).toInt()] + 1
                sPlen[sp] = plen + 1
                sLabel[sp] = r.b[labOff + zeros.toInt()]
                sp++
                i++; zeros++
            }
        }
        return true
    }

    private fun emitV4Range(out: StringBuilder, lo0: Long, hi: Long) {
        var lo = lo0
        while (lo <= hi) {
            var len = 32
            while (len > 0) {
                val size = 1L shl (32 - (len - 1))
                val mask = size - 1
                if (lo and mask != 0L) break
                if (lo + size - 1 > hi) break
                len--
            }
            val size = if (len == 0) 0L else 1L shl (32 - len)
            out.append((lo ushr 24) and 0xFF).append('.').append((lo ushr 16) and 0xFF).append('.')
                .append((lo ushr 8) and 0xFF).append('.').append(lo and 0xFF).append('/').append(len).append('\n')
            if (len == 0) break
            val next = lo + size
            if (next > 0xFFFFFFFFL) break
            lo = next
        }
    }

    private fun readIpSet(r: Rd, out: StringBuilder?, c: Ctx): Boolean {
        val ver = r.u8()
        if (ver != 1) { r.err = true; return false }
        val n = r.u64be()
        if (r.err || n < 0 || n > 10_000_000L) { r.err = true; return false }
        for (k in 0 until n) {
            val la = r.uvarint(); val a = r.take(la)
            val lb = r.uvarint(); val b = r.take(lb)
            if (a < 0 || b < 0 || la != lb || (la != 4L && la != 16L)) { r.err = true; return false }
            // IPv6 пропускаем: на телефоне, как и на роутере, каналы работают по IPv4, и
            // человеку об этом говорится предупреждением, а не отказом.
            if (la == 16L) { c.sawV6 = true; continue }
            fun u32(o: Int) = ((r.b[o].toLong() and 0xFF) shl 24) or ((r.b[o + 1].toLong() and 0xFF) shl 16) or
                ((r.b[o + 2].toLong() and 0xFF) shl 8) or (r.b[o + 3].toLong() and 0xFF)
            val lo = u32(a); val hi = u32(b)
            if (lo > hi) { r.err = true; return false }
            if (out != null) emitV4Range(out, lo, hi)
        }
        return true
    }

    private fun stringList(r: Rd, each: ((Int, Int) -> Unit)?): Boolean {
        val n = r.uvarint()
        if (r.err || n > 10_000_000L) { r.err = true; return false }
        for (i in 0 until n) {
            val l = r.uvarint()
            val q = r.take(l)
            if (q < 0) return false
            each?.invoke(q, l.toInt())
        }
        return true
    }

    private fun readDefaultRule(r: Rd, c: Ctx): Boolean {
        while (true) {
            val t = r.u8()
            if (r.err) return false
            if (t == IT_FINAL) {
                val invert = r.u8()
                if (invert != 0) c.no("правило с invert: набор описывает исключение, а список исключений у нас нет")
                return !r.err
            }
            when (t) {
                IT_DOMAIN -> if (!readDomainMatcher(r, c.dom)) return false
                IT_DOMAIN_KEYWORD -> if (!stringList(r) { q, l ->
                        c.dom.write('*'.code); c.dom.write(r.b, q, l); c.dom.write('*'.code); c.dom.write('\n'.code)
                    }) return false
                IT_DOMAIN_REGEX -> if (!stringList(r) { q, l ->
                        c.dom.write("re:".toByteArray()); c.dom.write(r.b, q, l); c.dom.write('\n'.code)
                    }) return false
                IT_IP_CIDR -> if (!readIpSet(r, c.pfx, c)) return false
                IT_SOURCE_IP_CIDR -> {
                    if (!readIpSet(r, null, c)) return false
                    c.no("source_ip_cidr: набор описывает клиентов, а не назначение")
                }
                IT_NETWORK -> {
                    val n = r.uvarint()
                    if (r.err || n > 64) { r.err = true; return false }
                    for (i in 0 until n) {
                        val l = r.uvarint(); val q = r.take(l)
                        if (q < 0) return false
                        val s = String(r.b, q, l.toInt(), Charsets.ISO_8859_1)
                        when (s) {
                            "tcp" -> c.haveTcp = true
                            "udp" -> c.haveUdp = true
                            else -> c.no("network: транспорт не tcp и не udp")
                        }
                    }
                }
                IT_PORT_RANGE -> {
                    val n = r.uvarint()
                    if (r.err || n > 10_000) { r.err = true; return false }
                    for (i in 0 until n) {
                        val l = r.uvarint(); val q = r.take(l)
                        if (q < 0) return false
                        if (l >= META_PORT_LEN) { c.no("port_range: запись длиннее нашей"); continue }
                        // В спеке диапазон — через тире, у sing-box — через двоеточие (spec.c).
                        val s = String(r.b, q, l.toInt(), Charsets.ISO_8859_1).replace(':', '-')
                        if (s.isEmpty() || s.first() == '-' || s.last() == '-') {
                            c.no("port_range: диапазон открыт с одной стороны, границу додумывать нельзя"); continue
                        }
                        c.portAdd(s)
                    }
                }
                IT_SOURCE_PORT_RANGE -> {
                    if (!stringList(r, null)) return false
                    c.no("source_port_range: набор сужен по порту клиента, а не назначения")
                }
                in IT_STRINGS_UNSUPPORTED -> {
                    if (!stringList(r, null)) return false
                    c.no("правило про процесс, пакет или Wi-Fi")
                }
                IT_PORT -> {
                    val n = r.uvarint()
                    if (r.err || n > 10_000) { r.err = true; return false }
                    for (i in 0 until n) {
                        val q = r.take(2)
                        if (q < 0) return false
                        c.portAdd((((r.b[q].toInt() and 0xFF) shl 8) or (r.b[q + 1].toInt() and 0xFF)).toString())
                    }
                }
                IT_QUERY_TYPE, IT_SOURCE_PORT -> {
                    val n = r.uvarint()
                    if (r.err || n > 10_000_000L) { r.err = true; return false }
                    if (r.take(n * 2) < 0) return false
                    c.no(if (t == IT_QUERY_TYPE) "query_type: набор описывает вид DNS-запроса" else "source_port: сужение по порту клиента")
                }
                IT_ADGUARD_DOMAIN -> {
                    r.u8()
                    r.u64array(); r.u64array()
                    val nl = r.uvarint()
                    if (r.err || nl > 100_000_000L || r.take(nl) < 0) { r.err = true; return false }
                    c.no("adguard_domain")
                }
                IT_NET_EXPENSIVE, IT_NET_CONSTRAINED -> c.no("признак вида сети")
                IT_IFACE_ADDR -> {
                    val entries = r.uvarint()
                    if (r.err || entries > 1_000_000L) { r.err = true; return false }
                    for (i in 0 until entries) {
                        r.u8()
                        val cnt = r.uvarint()
                        if (r.err || cnt > 1_000_000L) { r.err = true; return false }
                        for (k in 0 until cnt) {
                            val l = r.uvarint()
                            if (r.take(l) < 0 || r.take(1) < 0) return false
                        }
                    }
                    c.no("адреса интерфейса")
                }
                IT_DEFAULT_IFACE_ADDR -> {
                    val cnt = r.uvarint()
                    if (r.err || cnt > 1_000_000L) { r.err = true; return false }
                    for (k in 0 until cnt) {
                        val l = r.uvarint()
                        if (r.take(l) < 0 || r.take(1) < 0) return false
                    }
                    c.no("адреса интерфейса по умолчанию")
                }
                else -> { r.err = true; return false }
            }
        }
    }

    private fun readRule(r: Rd, c: Ctx, depth: Int): Boolean {
        if (depth > 100) { r.err = true; return false }
        val kind = r.u8()
        if (r.err) return false
        if (kind == 0) return readDefaultRule(r, c)
        if (kind == 1) {
            r.u8()
            val n = r.uvarint()
            if (r.err || n > 100_000) { r.err = true; return false }
            for (i in 0 until n) if (!readRule(r, c, depth + 1)) return false
            r.u8()
            c.no("логическое правило: список не выражает «и»/«или» между условиями")
            return !r.err
        }
        r.err = true
        return false
    }

    /** Разобрать набор. Отказ — SrsError; половина набора — это не набор. */
    fun read(raw: ByteArray): SrsResult {
        if (raw.size < 6 || raw.size > 64 * 1024 * 1024) throw SrsError(false, "неправдоподобный размер набора")
        if (raw[0] != 'S'.code.toByte() || raw[1] != 'R'.code.toByte() || raw[2] != 'S'.code.toByte())
            throw SrsError(false, "это не набор правил sing-box")
        val ver = raw[3].toInt() and 0xFF
        if (ver < 1 || ver > VER_MAX) throw SrsError(false, "версия формата набора $ver не поддерживается")
        val zn = raw.size - 4
        if (zn < 6) throw SrsError(false, "набор короче обёртки")
        val cmf = raw[4].toInt() and 0xFF
        val flg = raw[5].toInt() and 0xFF
        if ((cmf and 0x0F) != 8 || ((cmf shl 8) or flg) % 31 != 0 || (flg and 0x20) != 0)
            throw SrsError(false, "тело набора сжато не zlib")
        // Сырой deflate без двух байт заголовка — как puff в srs.c; nowrap требует лишний байт
        // в конце входа, и им служит хвост adler32.
        val inf = Inflater(true)
        val body: ByteArray
        try {
            inf.setInput(raw, 6, raw.size - 6)
            val bo = ByteArrayOutputStream()
            val buf = ByteArray(65536)
            while (!inf.finished()) {
                val k = inf.inflate(buf)
                if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
                bo.write(buf, 0, k)
                if (bo.size() > 64 * 1024 * 1024) throw SrsError(false, "набор распаковывается в неправдоподобный размер")
            }
            if (!inf.finished() || bo.size() == 0) throw SrsError(false, "тело набора не распаковалось")
            body = bo.toByteArray()
        } catch (e: java.util.zip.DataFormatException) {
            throw SrsError(false, "тело набора не распаковалось")
        } finally {
            inf.end()
        }

        val r = Rd(body, 0, body.size)
        val c = Ctx()
        val nrules = r.uvarint()
        var bad = r.err || nrules > 100_000
        var i = 0L
        while (!bad && i < nrules) { if (!readRule(r, c, 0)) bad = true; i++ }
        if (!bad && r.p != r.end) bad = true
        if (bad) throw SrsError(false, "набор не разобран")
        val warnings = ArrayList<String>()
        if (c.sawV6) warnings.add("в наборе есть подсети IPv6 — они пропущены, правила работают по IPv4")
        if (c.portsOver) c.no("портов в наборе больше, чем правило может сузить")
        if (c.unsupported) throw SrsError(true, "набор понят, но не выразим списком — ${c.why}")
        val proto = when {
            c.haveTcp && c.haveUdp -> "both"
            c.haveTcp -> "tcp"
            c.haveUdp -> "udp"
            else -> null
        }
        val narrow = Narrow(proto, c.ports.toList())
        return SrsResult(c.dom.toString(Charsets.UTF_8.name()), c.pfx.toString(), if (narrow.empty) null else narrow, warnings)
    }

    /** То же сужение в форме, которую печатает `srs-read --meta-out` (для сверки на стенде). */
    fun metaText(n: Narrow?): String {
        if (n == null) return ""
        val sb = StringBuilder()
        if (n.proto != null) sb.append("proto=").append(n.proto).append('\n')
        if (n.ports.isNotEmpty()) sb.append("ports=").append(n.ports.joinToString(",")).append('\n')
        return sb.toString()
    }
}
