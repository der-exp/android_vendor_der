/*
 * Подписки VLESS: скачать, разобрать, что сказала панель, и посчитать пригодные узлы.
 *
 * НА РОУТЕРЕ ЭТО ДЕЛАЕТ ДВИЖОК (`steer sub-fetch`, src/ext/subfetch.c): заголовки запроса с
 * идентификатором устройства, разбор ответных заголовков, название подписки, остаток трафика,
 * повтор за форматом /json. Объект rpcd только зовёт его и кладёт ответ в uci. На телефоне
 * сходить в сеть за приложение движок не может — у управляющего сокета нет такой команды, и
 * заводить её незачем: скачивание — ровно то, что приложение умеет само, а доступ движка в сеть
 * от имени приложения размыл бы границу «движок невидим». Поэтому шаги subfetch.c перенесены
 * сюда, по одному, с их причинами.
 *
 * ЧИСЛО ПРИГОДНЫХ УЗЛОВ — перенос разбора src/ext/sub.c (vless_parse_sub и node_usable). Это
 * второе место, где решается «какой узел годен», и расходиться с движком ему нельзя: номер узла
 * в выходе (`nodes`) считается среди ПРИГОДНЫХ, и если приложение насчитает их иначе, человек
 * выберет пятый, а поднимется другой. Поэтому здесь пригодность решается по тем же правилам,
 * а стенд сверяет счёт с образцами tests/submatch.c движка. Правильнее было бы спросить движок:
 * для этого нужна команда сокета `sub-check <длина>` с телом-подпиской, отвечающая выводом
 * `vless_parse_sub` (число, пропущенные с причинами) — описана в отчёте; пока её нет, разбор здесь.
 *
 * ФАЙЛ ПОДПИСКИ ДЛЯ ДВИЖКА — ТОТ ЖЕ, ЧТО ОТДАЛА ПАНЕЛЬ: движок сам раскрывает base64 и читает
 * конфиг Xray (sub.c), и пересобирать текст здесь значило бы держать вторую реализацию того,
 * что у движка уже есть и что он же применит.
 */
package com.der.splify2.logic

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

internal class SubStats(val usable: Int, val skipped: Int, val foreign: Int, val reasons: Map<String, Int>)

internal object SubParse {
    /** base64 в духе sub.c: переводы строк внутри, без выравнивания, URL-safe алфавит; '='
     *  закрывает блок (склеенные блоки «QQ==QQ==» иначе сдвигали бы всё дальнейшее). */
    fun b64(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (ch in s) {
            if (ch == '=') { acc = 0; bits = 0; continue }
            val v = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                else -> -1
            }
            if (v < 0) continue
            acc = ((acc shl 6) or v) and 0x3FFF
            bits += 6
            if (bits >= 8) { bits -= 8; out.write((acc shr bits) and 0xFF) }
        }
        return out.toByteArray()
    }

    /** Текст подписки: JSON или ссылки — как есть, иначе base64 (vless_sub_text). */
    fun text(raw: String): String {
        val t = raw.trimStart(' ', '\t', '\n', '\r')
        if (t.startsWith("[") || t.startsWith("{")) return raw
        if (raw.contains("://")) return raw
        return String(b64(raw), Charsets.UTF_8)
    }

    private class Node {
        var uuid = ""; var host = ""; var port = 0; var name = ""
        var type = "tcp"; var security = ""; var sni = ""; var pbk = ""; var mode = ""
    }

    private fun hostIsName(h: String): Boolean {
        if (h.isEmpty() || h.contains(':') || h.startsWith("[")) return false
        return h.any { !(it.isDigit() || it == '.') }
    }

    private fun leadsNowhere(h: String): Boolean {
        if (h.isEmpty()) return true
        if (h in setOf("0.0.0.0", "::", "[::]", "::1", "[::1]", "255.255.255.255")) return true
        if (h.startsWith("127.") && h.substring(4).all { it.isDigit() || it == '.' }) return true
        return false
    }

    private val HEX = Regex("^[0-9A-Fa-f]+$")

    /** vless_uuid_form: 32…36 знаков — UUID с дефисами на границах групп; 1…30 — строка, из
     *  которой Xray выводит UUID (годна); 0, 31 и больше 36 — нет. */
    private fun uuidProblem(s: String): String? {
        val n = s.length
        if (n in 32..36) {
            if (!uuidHex(s)) return "UUID с недопустимым знаком"
            return null
        }
        if (n == 0) return "идентификатор пуст"
        if (n == 31) return "идентификатор: 31 знак, нужен UUID"
        if (n > 36) return "идентификатор длиннее UUID"
        return null
    }

    // hex_groups (vless_proto.c) дословно: группы 8-4-4-4-12, перед каждой — не больше одного
    // необязательного дефиса; хвост после пятой группы не проверяется, как и там.
    private fun uuidHex(s: String): Boolean {
        var i = 0
        for (g in intArrayOf(8, 4, 4, 4, 12)) {
            if (i < s.length && s[i] == '-') i++
            if (s.length - i < g) return false
            if (!HEX.matches(s.substring(i, i + g))) return false
            i += g
        }
        return true
    }

    private fun usable(n: Node): String? {
        if (n.security.isEmpty()) n.security = "none"
        uuidProblem(n.uuid)?.let { return it }
        if (n.security != "reality" && n.security != "none" && n.security != "tls") return "security=${n.security} не поддержан"
        if (n.security == "tls" && n.sni.isEmpty() && !hostIsName(n.host)) return "tls по адресу без sni: нечем сверить"
        if (n.security == "reality" && n.pbk.isEmpty()) return "reality без pbk"
        if (n.type != "tcp" && n.type != "grpc" && n.type != "xhttp") return "транспорт ${n.type} не поддержан"
        if (leadsNowhere(n.host)) return "${n.host.take(20)}: отвечать некому"
        if (n.type == "xhttp" && n.mode.isNotEmpty() && n.mode !in setOf("auto", "stream-one", "stream-up", "packet-up"))
            return "xhttp mode=${n.mode} не поддержан"
        return null
    }

    /** vless_parse_url: null — ссылка не разобрана; иначе узел и причина непригодности (или null). */
    private fun parseUrl(url: String): Pair<Node, String?>? {
        if (!url.startsWith("vless://")) return null
        val n = Node()
        val p = url.substring(8)
        val at = p.indexOf('@')
        if (at < 0) return null
        n.uuid = p.substring(0, at)
        val rest = p.substring(at + 1)
        val hash = rest.indexOf('#')
        val hpEnd = if (hash >= 0) hash else rest.length
        val q = rest.substring(0, hpEnd).indexOf('?').let { if (it < 0) -1 else it }
        val hostport = rest.substring(0, if (q >= 0) q else hpEnd)
        val colon = hostport.indexOf(':')
        if (colon < 0) return null
        n.host = hostport.substring(0, colon)
        val pnum = hostport.substring(colon + 1)
        if (pnum.isEmpty() || pnum.length > 7 || !pnum.all { it.isDigit() }) return null
        n.port = pnum.toInt()
        if (n.port == 0 || n.port > 65535) return null
        if (hash >= 0) n.name = pct(rest.substring(hash + 1))
        if (q >= 0) {
            val end = if (hash > q) hash else rest.length
            for (kv in rest.substring(q + 1, end).split('&')) {
                val eq = kv.indexOf('=')
                if (eq < 0) continue
                val k = kv.substring(0, eq)
                val v = kv.substring(eq + 1)
                when (k) {
                    "type" -> n.type = v
                    "security" -> n.security = v
                    "sni" -> n.sni = v
                    "pbk" -> n.pbk = v
                    "mode" -> n.mode = v
                }
            }
        }
        return n to usable(n)
    }

    private fun pct(s: String): String {
        val b = java.io.ByteArrayOutputStream()
        var i = 0
        val bytes = s.toByteArray(Charsets.UTF_8)
        while (i < bytes.size) {
            val c = bytes[i].toInt()
            if (c == '%'.code && i + 2 < bytes.size) {
                val h = Character.digit(bytes[i + 1].toInt(), 16)
                val l = Character.digit(bytes[i + 2].toInt(), 16)
                if (h >= 0 && l >= 0) { b.write(h * 16 + l); i += 3; continue }
            }
            b.write(c); i++
        }
        return String(b.toByteArray(), Charsets.UTF_8)
    }

    private val SCHEMES = listOf("hysteria2", "wireguard", "hysteria", "trojan", "vmess", "vless", "tuic", "hy2", "ssr", "ss")
    private const val LINE_MAX = 8191
    const val MAX_NODES = 128

    /** Посчитать пригодные узлы — vless_parse_sub. */
    fun stats(raw: String): SubStats {
        val text = text(raw)
        val reasons = LinkedHashMap<String, Int>()
        var usable = 0
        var foreign = 0
        fun skip(r: String) { reasons[r] = (reasons[r] ?: 0) + 1 }
        val t = text.trimStart(' ', '\t', '\n', '\r')
        if (t.startsWith("[") || t.startsWith("{")) {
            for ((node, why) in xray(t)) {
                if (why == null && usable < MAX_NODES) usable++
                else skip(why ?: "узлов больше, чем помещается")
                @Suppress("UNUSED_VARIABLE") val unused = node
            }
        } else {
            for (line in splitLinks(text)) {
                if (line.length > LINE_MAX) {
                    if (line.startsWith("vless://")) skip("ссылка длиннее $LINE_MAX байт")
                    else if (line.contains("://")) foreign++
                    continue
                }
                if (line.startsWith("vless://")) {
                    if (gluedTail(line)) { skip("ссылки склеены без разделителя"); continue }
                    val r = parseUrl(line)
                    when {
                        r == null -> skip("ссылка не разобрана")
                        r.second != null -> skip(r.second!!)
                        usable >= MAX_NODES -> skip("узлов больше, чем помещается")
                        else -> usable++
                    }
                } else if (line.contains("://")) foreign++
            }
        }
        return SubStats(usable, reasons.values.sum(), foreign, reasons)
    }

    /** Строки подписки; ссылка, приклеенная к предыдущей без перевода строки, отрезается по
     *  известной схеме (так делают некоторые панели, см. vless_parse_sub). */
    private fun splitLinks(text: String): List<String> {
        val out = ArrayList<String>()
        var p = 0
        val n = text.length
        while (p < n) {
            while (p < n && (text[p] == '\n' || text[p] == '\r' || text[p] == ' ' || text[p] == '\t')) p++
            if (p >= n) break
            var e = p
            while (e < n && text[e] != '\n' && text[e] != '\r') {
                if (e > p && text.startsWith("://", e)) {
                    var cut = -1
                    for (s in SCHEMES) {
                        if (e - p < s.length) continue
                        if (!text.regionMatches(e - s.length, s, 0, s.length)) continue
                        if (e - p > s.length) cut = e - s.length
                        break
                    }
                    if (cut >= 0) { e = cut; break }
                }
                e++
            }
            out.add(text.substring(p, e))
            p = e
        }
        return out
    }

    private fun gluedTail(line: String): Boolean {
        var q = line.indexOf("://", 1)
        while (q >= 1) {
            var sc = q
            while (sc > 0 && line[sc - 1].let { it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '.' || it == '-' }) sc--
            val sl = q - sc
            if (sc != 0 && sl in 2..15 && line[sc] in 'a'..'z') {
                val tail = line.substring(q + 3).substringBefore('#')
                if (tail.contains('@')) return true
            }
            q = line.indexOf("://", q + 1)
        }
        return false
    }

    /** Конфиг Xray: массив конфигов или один; из outbounds — те, у кого protocol = vless. */
    private fun xray(t: String): List<Pair<Node, String?>> {
        val out = ArrayList<Pair<Node, String?>>()
        val cfgs = try {
            if (t.startsWith("[")) JSONArray(t) else JSONArray().put(JSONObject(t))
        } catch (e: Exception) {
            return out
        }
        for (i in 0 until cfgs.length()) {
            val cfg = cfgs.optJSONObject(i) ?: continue
            val obs = cfg.optJSONArray("outbounds") ?: continue
            for (k in 0 until obs.length()) {
                val ob = obs.optJSONObject(k) ?: continue
                if (ob.str("protocol") != "vless") continue
                val n = Node()
                val vnext = ob.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0)
                if (vnext != null) {
                    n.host = vnext.str("address") ?: ""
                    n.port = when (val pv = vnext.opt("port")) {
                        is Number -> pv.toInt()
                        is String -> pv.toIntOrNull() ?: 0
                        else -> 0
                    }.let { if (it in 1..65535) it else 0 }
                    n.uuid = vnext.optJSONArray("users")?.optJSONObject(0)?.str("id") ?: ""
                }
                val ss = ob.optJSONObject("streamSettings")
                if (ss != null) {
                    ss.str("network")?.let { n.type = if (it == "raw") "tcp" else it }
                    ss.str("security")?.let { n.security = it }
                    val ts = ss.optJSONObject("realitySettings") ?: ss.optJSONObject("tlsSettings")
                    if (ts != null) {
                        ts.str("serverName")?.let { n.sni = it }
                        ts.str("publicKey")?.let { n.pbk = it }
                    }
                    (ss.optJSONObject("xhttpSettings") ?: ss.optJSONObject("splithttpSettings"))?.str("mode")?.let { n.mode = it }
                }
                out.add(n to usable(n))
            }
        }
        return out
    }
}

/** Что панель сказала заголовками — перенос разбора subfetch.c. */
internal object SubHeaders {
    fun get(h: Map<String, String>, name: String): String? =
        h.entries.lastOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()

    /** Одно поле `subscription-userinfo`; только цифры (байты и unix-время). Переполнение —
     *  «числу верить нельзя», а не «число большое». */
    fun uiField(v: String, field: String): Long? {
        for (item in v.split(';', ',')) {
            val eq = item.indexOf('=')
            if (eq < 0) continue
            if (!item.substring(0, eq).trim().equals(field, ignoreCase = true)) continue
            val d = item.substring(eq + 1).trim().takeWhile { it.isDigit() }
            if (d.isEmpty()) return null
            return d.toLongOrNull()
        }
        return null
    }

    /** Название подписки, как его назвала панель: `profile-title` (часто `base64:…`), иначе
     *  имя файла из content-disposition. Управляющие символы и кавычки — вон; не длиннее 48
     *  байт и без половины буквы на конце. */
    fun title(h: Map<String, String>): String {
        var raw = get(h, "profile-title") ?: ""
        if (raw.isEmpty()) {
            val cd = get(h, "content-disposition") ?: ""
            val f = cd.indexOf("filename=", ignoreCase = true)
            if (f >= 0) raw = cd.substring(f + 9).trimStart('"').takeWhile { it != '"' && it != ';' }
        }
        if (raw.isEmpty()) return ""
        if (raw.startsWith("base64:", ignoreCase = true)) {
            val dec = SubParse.b64(raw.substring(7))
            if (dec.isNotEmpty()) raw = String(dec, Charsets.UTF_8)
        }
        val clean = raw.filter { it >= ' ' && it != '"' && it != '\\' }
        return utf8Cut(clean, 48)
    }

    fun deviceWarn(h: Map<String, String>, hwidSent: Boolean): String? = when {
        get(h, "x-hwid-not-supported") != null -> "панель не увидела идентификатора устройства и отдала заглушку вместо узлов"
        get(h, "x-hwid-limit") != null -> "панель считает, что устройств уже больше, чем позволено подпиской: освободите место у поставщика"
        !hwidSent -> "идентификатор устройства не ушёл: если панель требует его, вместо узлов приедет заглушка"
        else -> null
    }

    /** Название и ссылка известного продавца — перенос sub_brand (rpcd/m-sub.sh): источник
     *  опознаётся по ссылке однозначно, и название ему даём мы, а не панель «как придётся». */
    fun brand(url: String, title: String): Pair<String, String?> =
        if (url.contains("StressKVN_bot")) "☢️ StressKVN❤️" to "https://t.me/StressKVN_bot" else title to null
}

/** Хранилище подписок: filesDir/subs/<id>.txt (как отдала панель) и <id>.json (что она сказала). */
internal class SubStore(filesDir: File, private val http: Http, private val device: DeviceInfo) {
    val dir = File(filesDir, "subs")
    private val hwidFile = File(filesDir, "hwid")

    fun content(id: String): ByteArray? = File(dir, "$id.txt").takeIf { it.isFile }?.readBytes()
    fun info(id: String): JSONObject = Files.readJson(File(dir, "$id.json")) ?: JSONObject()

    /** Имя файла подписки в каталоге движка: с хешем содержимого.
     *
     *  Хеш в имени — не украшение. Помощник vless читает подписку ОДИН раз при старте, а
     *  супервизор движка перезапускает его по подписи параметров, в которую входит ПУТЬ файла, но
     *  не содержимое (steer.c, sup_sig: «содержимое файлов подписью не ловится»). На роутере
     *  обновлённую подписку дочитывает перезапуск экземпляра по признаку VLESS_DIRTY; на телефоне
     *  приложению перезапускать помощников нечем. Новое содержимое — новое имя — новая подпись,
     *  и `apply` перезапускает помощника сам. */
    fun engineName(id: String): String? {
        val c = content(id) ?: return null
        return "sub-$id-${sha256hex(c).take(8)}.txt"
    }

    fun delete(id: String) {
        File(dir, "$id.txt").delete(); File(dir, "$id.json").delete()
    }

    /** Идентификатор устройства для панели (заголовок x-hwid).
     *
     *  На роутере его выводит движок из MAC-адресов портов; на телефоне MAC приложению не
     *  виден, а ANDROID_ID требует Android. Поэтому — случайное число, созданное один раз и
     *  хранимое у приложения: человеку оно нужно ровно затем, чтобы найти своё устройство в
     *  панели, и главное свойство — не меняться между обновлениями подписки. */
    fun hwid(): String {
        Files.readText(hwidFile)?.trim()?.takeIf { it.matches(Regex("^[0-9a-f]{20}$")) }?.let { return it }
        val b = ByteArray(10).also { SecureRandom().nextBytes(it) }
        val id = b.joinToString("") { "%02x".format(it) }
        Files.writeText(hwidFile, id)
        return id
    }

    private fun request(url: String, withId: Boolean): HttpResult? {
        val h = LinkedHashMap<String, String>()
        // Тот же User-Agent, что у движка на роутере (subfetch.c, SUB_UA): панели выбирают
        // формат ответа по нему, и одинаковое имя даёт одинаковый ответ на обоих устройствах.
        h["User-Agent"] = "steer/android"
        if (withId) {
            h["x-hwid"] = hwid()
            h["x-device-os"] = device.os
            h["x-ver-os"] = device.osVersion.ifEmpty { device.os }
            h["x-device-model"] = device.model.ifEmpty { "Android" }
        }
        return try {
            val r = http.get(url, h)
            if (r.code in 200..299) r else null
        } catch (e: Exception) {
            null
        }
    }

    class Fetch(val body: ByteArray, val headers: Map<String, String>, val url: String, val stats: SubStats, val hwidSent: Boolean)

    /** Скачать подписку — cmd_sub_fetch по шагам:
     *   1) с идентификатором устройства, при отказе — без него (как http_get: панель, которой
     *      заголовки не нравятся, не должна оставить человека без подписки);
     *   2) ни одного пригодного узла — перезапросить с суффиксом /json: панели с привязкой к
     *      устройствам отдают незнакомому клиенту заглушку, а конфиг Xray — по /json. */
    fun fetch(url: String): Fetch {
        var sent = true
        val r = request(url, true) ?: request(url, false).also { sent = false }
            ?: throw BridgeError("network", "Подписка не скачалась — проверьте ссылку и подключение")
        if (r.body.isEmpty()) throw BridgeError("network", "Панель подписки отдала пустой ответ")
        var st = SubParse.stats(String(r.body, Charsets.UTF_8))
        if (st.usable == 0 && !url.trimEnd('/').endsWith("/json")) {
            val jurl = url.trimEnd('/') + "/json"
            val r2 = request(jurl, true)
            if (r2 != null && r2.body.isNotEmpty()) {
                val st2 = SubParse.stats(String(r2.body, Charsets.UTF_8))
                if (st2.usable > 0) return Fetch(r2.body, r2.headers, jurl, st2, true)
            }
        }
        return Fetch(r.body, r.headers, url, st, sent)
    }

    /** Положить скачанное и запомнить, что сказала панель. Остаток трафика — с началом периода
     *  (quota_save): панель называет только конец и накопленный расход, а средний расход в
     *  сутки без начала не посчитать; гадать длину периода нельзя. Период новый, когда сменился
     *  срок или объём, либо расход уменьшился (панель обнулила счётчик). */
    fun store(id: String, body: ByteArray, headers: Map<String, String>?, stats: SubStats, extra: JSONObject.() -> Unit = {}) {
        Files.write(File(dir, "$id.txt"), body)
        val prev = info(id)
        val o = JSONObject()
        o.put("updated", nowSec()).put("usable", stats.usable).put("skipped", stats.skipped).put("foreign", stats.foreign)
        if (stats.reasons.isNotEmpty()) o.put("reasons", JSONObject(stats.reasons as Map<*, *>))
        val ui = headers?.let { SubHeaders.get(it, "subscription-userinfo") }
        if (ui != null) {
            val up = SubHeaders.uiField(ui, "upload")
            val down = SubHeaders.uiField(ui, "download")
            val total = SubHeaders.uiField(ui, "total")
            val exp = SubHeaders.uiField(ui, "expire")
            if (total != null || exp != null) {
                val now = nowSec()
                val used = (up ?: 0) + (down ?: 0)
                val pq = prev.optJSONObject("quota")
                var at0 = now
                var used0 = used
                if (pq != null && pq.has("since") && pq.optLong("expire", -1) == (exp ?: -1) &&
                    pq.optString("total", "") == (total?.toString() ?: "") &&
                    used >= (pq.optString("up", "0").toLongOrNull() ?: 0) + (pq.optString("down", "0").toLongOrNull() ?: 0)) {
                    at0 = pq.optLong("since", now)
                    used0 = pq.optString("since_used", "0").toLongOrNull() ?: used
                }
                // Байты — строками, как на роутере: JSON-число в JavaScript точно до 2^53, а
                // счётчики панелей бывают любыми; экран делит их сам.
                o.put("quota", JSONObject().put("up", (up ?: 0).toString()).put("down", (down ?: 0).toString())
                    .put("total", total?.toString() ?: "").put("expire", exp ?: 0).put("at", now)
                    .put("since", at0).put("since_used", used0.toString()))
            }
        } else if (headers == null) {
            prev.optJSONObject("quota")?.let { o.put("quota", it) }
        }
        o.extra()
        Files.writeText(File(dir, "$id.json"), o.toString())
    }
}
