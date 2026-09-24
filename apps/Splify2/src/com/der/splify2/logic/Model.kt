/*
 * Модель настроек splify2 на телефоне — источник правды.
 *
 * НА РОУТЕРЕ ИСТОЧНИК ПРАВДЫ — САМА СПЕКА. Там объект rpcd нарочно не держит своей модели
 * каналов («вторая модель означала бы второе место, которое надо держать в согласии с
 * контрактом»), а интерфейс правит spec.json напрямую и только проверяет его компилятором. На
 * телефоне так нельзя по двум причинам, и обе — от платформы, а не от вкуса:
 *
 *   1. Спеку приложение прочитать не может вовсе: /data/misc/steer закрыт для него политикой, а
 *      у управляющего сокета нет команды «отдай спеку». Хранить её копию у себя — это и есть
 *      вторая модель, только в неудобной форме.
 *   2. В спеке нет понятий, которыми думает человек на телефоне: «приложение» там — число UID,
 *      «список из каталога» — два пути к файлам в каталоге движка плюс канал-спутник с портами,
 *      «подписка» — путь к файлу, имя которого меняется при каждом обновлении (см. SpecBuilder).
 *      Обратно из спеки их не восстановить.
 *
 * Поэтому здесь модель, а спека из неё СОБИРАЕТСЯ (SpecBuilder) и отдаётся движку; обратного
 * разбора нет. Понятия модели повторяют спеку там, где это возможно (выходы с тем же `kind` и
 * `on_fail`, каналы в том же порядке), чтобы экран и движок говорили об одном.
 *
 * ФОРМА JSON (settings.get / settings.put, резервная копия):
 *
 *   {
 *     "version": 1,
 *     "outputs": [
 *       {"name":"vpn", "kind":"interface", "devices":["wg0"], "on_fail":"drop"},
 *       {"name":"nl",  "kind":"vless", "sub":"s1", "nodes":[2,5], "on_fail":"drop"},
 *       {"name":"tg",  "kind":"tgws", "domain":"example.com"}
 *     ],
 *     "channels": [                                  // сверху вниз, первое совпадение побеждает
 *       {"name":"YouTube", "enabled":true,
 *        "who":  {"kind":"phone"}                    // весь телефон — from:"self"
 *              | {"kind":"apps", "uids":[10123]}     // приложения — from:"uid:N"
 *              | {"kind":"tether", "from":[]},       // раздача — как на роутере; from пуст — вся
 *        "what": {"lists":["itdoginfo:youtube"],     // службы каталога (Catalog.Service.id)
 *                 "custom":["work"],                 // свои списки по имени
 *                 "all":false},                      // весь трафик вместо списков
 *        "out":"vpn"}
 *     ],
 *     "lists":  ["itdoginfo:block"],                 // выбранные службы каталога
 *     "custom": [{"name":"work", "domains":["corp.example"], "prefixes":["203.0.113.0/24"]}],
 *     "subs":   [{"id":"s1", "name":"Моя подписка", "url":"https://…", "kind":"url"}],
 *     "tether": {"devices":["rndis0","ncm0","softap0","ap0","swlan0","bt-pan"]},
 *     "catalog_url": null,                           // свой каталог; null — каталог по умолчанию
 *     "update": {"unmetered_only": true}             // ежесуточное обновление — только без лимитной сети
 *   }
 *
 * settings.put принимает и часть модели: поля верхнего уровня, которых нет в запросе, остаются
 * как были (Dispatcher.settingsPut). Подписки — только своими методами subs.*.
 *
 * Выход `direct` есть всегда и в модели его заводить не нужно (как withDirect в интерфейсе
 * роутера): «напрямую» — законная цель правила, а не настройка, которую можно забыть.
 */
package com.der.splify2.logic

import org.json.JSONArray
import org.json.JSONObject

enum class OutKind(val word: String) {
    INTERFACE("interface"), DIRECT("direct"), VLESS("vless"), TGWS("tgws");

    companion object {
        fun of(s: String?): OutKind? = entries.firstOrNull { it.word == s }
    }
}

data class Output(
    val name: String,
    val kind: OutKind,
    /** kind=interface: устройства в порядке предпочтения — первое здоровое забирает трафик. */
    val devices: List<String> = emptyList(),
    /** drop по умолчанию: канал заводят, чтобы трафик НЕ шёл напрямую, и молча вернуть его на
     *  открытый путь в момент поломки — нарушить обещание тогда, когда это опаснее всего. */
    val onFail: String? = null,
    /** kind=vless: id подписки из Model.subs. */
    val sub: String? = null,
    /** kind=vless: номера узлов по предпочтению; пусто — «первый рабочий», и это умолчание
     *  рекомендуется: номер узла меняется при обновлении подписки. */
    val nodes: List<Int> = emptyList(),
    /** kind=tgws: имя за Cloudflare для точек kwsN.<domain>. */
    val domain: String? = null,
)

sealed class Who {
    object Phone : Who()
    data class Apps(val uids: List<Int>) : Who()
    data class Tether(val from: List<String>) : Who()
}

data class What(
    val lists: List<String> = emptyList(),
    val custom: List<String> = emptyList(),
    val all: Boolean = false,
) {
    val empty get() = lists.isEmpty() && custom.isEmpty() && !all
}

data class Channel(
    val name: String,
    val enabled: Boolean,
    val who: Who,
    val what: What,
    val out: String,
)

data class CustomList(val name: String, val domains: List<String>, val prefixes: List<String>)

data class Sub(val id: String, val name: String, val url: String?, val kind: String)

data class Model(
    val version: Int = 1,
    val outputs: List<Output> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val lists: List<String> = emptyList(),
    val custom: List<CustomList> = emptyList(),
    val subs: List<Sub> = emptyList(),
    val tetherDevices: List<String> = DEFAULT_TETHER,
    val catalogUrl: String? = null,
    /** Ежесуточное обновление списков и подписок — только без лимитной сети. По умолчанию да:
     *  списки каталога весят мегабайты, и тратить на них мобильный трафик без спроса нельзя;
     *  кому свежесть важнее трафика, выключит сам. Применяет оболочка (UpdateJobService). */
    val updateUnmeteredOnly: Boolean = true,
) {
    fun output(name: String): Output? =
        outputs.firstOrNull { it.name == name } ?: if (name == "direct") Output("direct", OutKind.DIRECT) else null

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("version", version)
        o.put("outputs", JSONArray().also { a -> outputs.forEach { a.put(outputJson(it)) } })
        o.put("channels", JSONArray().also { a -> channels.forEach { a.put(channelJson(it)) } })
        o.put("lists", jsonArrayOf(lists))
        o.put("custom", JSONArray().also { a ->
            custom.forEach {
                a.put(JSONObject().put("name", it.name).put("domains", jsonArrayOf(it.domains))
                    .put("prefixes", jsonArrayOf(it.prefixes)))
            }
        })
        o.put("subs", JSONArray().also { a ->
            subs.forEach {
                val s = JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind)
                if (it.url != null) s.put("url", it.url)
                a.put(s)
            }
        })
        o.put("tether", JSONObject().put("devices", jsonArrayOf(tetherDevices)))
        o.put("catalog_url", catalogUrl ?: JSONObject.NULL)
        o.put("update", JSONObject().put("unmetered_only", updateUnmeteredOnly))
        return o
    }

    companion object {
        const val VERSION = 1

        /** Устройства раздачи по умолчанию: USB (rndis0, ncm0), точка доступа (softap0, ap0,
         *  swlan0 — у производителей по-разному) и Bluetooth (bt-pan).
         *
         *  wlan0 и wlan1 здесь НЕТ нарочно: это клиентский Wi-Fi телефона (а у двухдиапазонных
         *  чипов wlan1 бывает вторым клиентским), то есть его ВХОД из интернета. Устройство в
         *  этом списке движок считает источником клиентов, и завернуть сюда верхнюю сеть значило
         *  бы маршрутизировать каналами ответы из интернета. Отсутствующее устройство вреда не
         *  делает: правило пишется через iifname и начинает работать само, когда оно поднимется
         *  (контракт steer, lan_devices). */
        val DEFAULT_TETHER = listOf("rndis0", "ncm0", "softap0", "ap0", "swlan0", "bt-pan")

        fun empty() = Model()

        private val OUT_NAME = Regex("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,30}$")
        private val DEV_NAME = Regex("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,14}$")
        private val CUSTOM_NAME = Regex("^[A-Za-z0-9_-]{1,24}$")
        private val SUB_ID = Regex("^[a-z0-9]{1,12}$")
        private val HOST = Regex("^(?=.{1,200}$)[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$")
        private val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

        private fun bad(msg: String): Nothing = throw BridgeError("bad-args", msg)

        /** Разбор модели от экрана или из резервной копии — со всеми проверками.
         *
         *  Проверки ЗДЕСЬ, а не в экране, по той же причине, по которой на роутере список
         *  проверяется в rpcd, а не в интерфейсе: экран можно обойти (резервная копия — это
         *  файл, пришедший откуда угодно), а последствие непроверенного значения молчаливое —
         *  движок отвергнет спеку целиком, и человек узнает об этом только при «Применить», да
         *  ещё словами движка. Здесь человек получает ответ на то, что написал, в момент записи.
         *
         *  Чего здесь НЕ проверяется: что каналы образуют рабочую спеку. Это решает только
         *  движок (spec.preview), и второй компилятор спеки в приложении разошёлся бы с ним. */
        fun parse(o: JSONObject): Model {
            val version = if (o.has("version")) o.optInt("version", -1) else 1
            if (version < 1) bad("Файл настроек повреждён: неизвестная версия")
            if (version > VERSION) bad("Настройки сохранены более новой версией приложения — обновите приложение")

            val subs = parseSubs(o.optJSONArray("subs"))
            val custom = parseCustom(o.optJSONArray("custom"))
            val outputs = parseOutputs(o.optJSONArray("outputs"), subs)
            val channels = parseChannels(o.optJSONArray("channels"), outputs, custom)

            val lists = LinkedHashSet<String>()
            for (id in o.optJSONArray("lists").strings()) {
                if (id.isBlank() || id.length > 128) bad("Список каталога назван неверно")
                lists.add(id)
            }

            var tether = DEFAULT_TETHER
            o.optJSONObject("tether")?.let { t ->
                if (t.has("devices")) {
                    val d = t.optJSONArray("devices").strings()
                    if (d.size > 32) bad("Устройств раздачи больше 32")
                    for (x in d) if (!DEV_NAME.matches(x)) bad("Устройство раздачи «$x»: латиница, цифры, «_», «-» и «.», до 15 знаков")
                    if (d.toSet().size != d.size) bad("Устройство раздачи названо дважды")
                    tether = d
                }
            }

            val cu = o.str("catalog_url")?.trim()?.ifEmpty { null }
            if (cu != null && !cu.startsWith("https://") && !cu.startsWith("http://"))
                bad("Адрес каталога списков должен начинаться с https://")

            val uv: Any? = o.optJSONObject("update")?.opt("unmetered_only")
            val unmetered = if (uv == null || uv == JSONObject.NULL) true
                else uv as? Boolean ?: bad("Настройка обновления записана неверно")

            return Model(version, outputs, channels, lists.toList(), custom, subs, tether, cu, unmetered)
        }

        private fun parseSubs(a: JSONArray?): List<Sub> {
            val out = ArrayList<Sub>()
            if (a == null) return out
            for (i in 0 until a.length()) {
                val s = a.optJSONObject(i) ?: bad("Подписка №${i + 1} записана неверно")
                val id = s.str("id") ?: bad("У подписки №${i + 1} нет идентификатора")
                if (!SUB_ID.matches(id)) bad("Подписка «$id»: идентификатор из строчных латинских букв и цифр")
                if (out.any { it.id == id }) bad("Подписка «$id» записана дважды")
                val kind = s.str("kind") ?: if (s.str("url") != null) "url" else "links"
                if (kind != "url" && kind != "links") bad("Подписка «$id»: неизвестный вид")
                val url = s.str("url")?.trim()?.ifEmpty { null }
                if (kind == "url" && (url == null || !(url.startsWith("https://") || url.startsWith("http://"))))
                    bad("Подписка «$id»: нужна ссылка http:// или https://")
                val name = (s.str("name") ?: "").trim().take(64)
                out.add(Sub(id, name, if (kind == "url") url else null, kind))
            }
            return out
        }

        private fun parseCustom(a: JSONArray?): List<CustomList> {
            val out = ArrayList<CustomList>()
            if (a == null) return out
            for (i in 0 until a.length()) {
                val c = a.optJSONObject(i) ?: bad("Свой список №${i + 1} записан неверно")
                val name = c.str("name") ?: bad("У своего списка №${i + 1} нет имени")
                if (!CUSTOM_NAME.matches(name)) bad("Свой список «$name»: латиница, цифры, «_» и «-», до 24 знаков")
                if (out.any { it.name == name }) bad("Свой список «$name» записан дважды")
                // Содержимое проверяется тем же санитайзером, что и lists.custom put: иначе
                // резервная копия была бы обходным путём мимо проверки (на роутере по той же
                // причине импорт считается МЕНЕЕ доверенным источником, чем интерфейс).
                val d = ListText.sanitize(ListText.Kind.DOMAINS, c.optJSONArray("domains").strings())
                val p = ListText.sanitize(ListText.Kind.PREFIXES, c.optJSONArray("prefixes").strings())
                out.add(CustomList(name, d.lines, p.lines))
            }
            return out
        }

        private fun parseOutputs(a: JSONArray?, subs: List<Sub>): List<Output> {
            val out = ArrayList<Output>()
            if (a == null) return out
            if (a.length() > 15) bad("Выходов больше 15 — столько движок не держит")
            for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: bad("Выход №${i + 1} записан неверно")
                val name = x.str("name") ?: bad("У выхода №${i + 1} нет имени")
                if (!OUT_NAME.matches(name)) bad("Имя выхода «$name»: латиница, цифры, «_», «-» и «.», до 31 знака")
                if (out.any { it.name == name }) bad("Выход «$name» назван дважды — переименуйте один")
                val kind = OutKind.of(x.str("kind")) ?: bad("Выход «$name»: такой вид выхода на телефоне не поддерживается")
                if (name == "direct" && kind != OutKind.DIRECT) bad("Имя «direct» занято выходом «напрямую» — выберите другое")
                val onFail = x.str("on_fail")
                // zapret на телефоне нет (решение владельца): движок под Android такое значение
                // отвергает, и лучше сказать это здесь, словами человека.
                if (onFail != null && onFail != "drop" && onFail != "direct")
                    bad("Выход «$name»: при отказе можно только блокировать или пускать напрямую")
                when (kind) {
                    OutKind.INTERFACE -> {
                        val devs = x.optJSONArray("devices").strings().ifEmpty { listOfNotNull(x.str("device")) }
                        if (devs.isEmpty()) bad("Выход «$name»: укажите устройство туннеля")
                        if (devs.size > 16) bad("Выход «$name»: устройств больше 16")
                        for (d in devs) if (!DEV_NAME.matches(d)) bad("Выход «$name»: устройство «$d» — латиница, цифры, «_», «-» и «.», до 15 знаков")
                        if (devs.toSet().size != devs.size) bad("Выход «$name»: устройство указано дважды")
                        out.add(Output(name, kind, devices = devs, onFail = onFail))
                    }
                    OutKind.DIRECT -> out.add(Output(name, kind))
                    OutKind.VLESS -> {
                        val sub = x.str("sub") ?: bad("Выход «$name»: выберите подписку")
                        if (subs.none { it.id == sub }) bad("Выход «$name»: подписки «$sub» нет — выберите другую")
                        val nodes = ArrayList<Int>()
                        val na = x.optJSONArray("nodes")
                        if (na != null) for (k in 0 until na.length()) {
                            val v = na.opt(k)
                            val n = (v as? Number)?.toInt() ?: bad("Выход «$name»: номер узла — число")
                            if (n < 0 || n > 9999) bad("Выход «$name»: номер узла вне 0…9999")
                            if (n in nodes) bad("Выход «$name»: узел $n выбран дважды")
                            nodes.add(n)
                        }
                        if (nodes.size > 16) bad("Выход «$name»: узлов больше 16")
                        // Устройство туннеля движок называет по первым 15 знакам имени выхода
                        // (IFNAMSIZ, spec.c): два таких выхода с общим началом делили бы одно
                        // устройство, и второй туннель не поднялся бы.
                        out.firstOrNull { it.kind == OutKind.VLESS && it.name.take(15) == name.take(15) }?.let {
                            bad("Выходы «${it.name}» и «$name» начинаются одинаково — сделайте различными первые 15 знаков")
                        }
                        out.add(Output(name, kind, sub = sub, nodes = nodes, onFail = onFail))
                    }
                    OutKind.TGWS -> {
                        val d = x.str("domain")?.trim()?.lowercase() ?: bad("Выход «$name»: укажите домен моста")
                        if (!HOST.matches(d)) bad("Выход «$name»: «$d» — не имя домена")
                        // У моста Telegram «при отказе» только блокировка: перехват стоит в ядре
                        // всегда (контракт steer, kind: tgws), обещать «напрямую» нечем.
                        if (onFail == "direct") bad("Выход «$name»: у моста Telegram при отказе возможна только блокировка")
                        out.add(Output(name, kind, domain = d))
                    }
                }
            }
            return out
        }

        private fun parseChannels(a: JSONArray?, outputs: List<Output>, custom: List<CustomList>): List<Channel> {
            val out = ArrayList<Channel>()
            if (a == null) return out
            for (i in 0 until a.length()) {
                val c = a.optJSONObject(i) ?: bad("Правило №${i + 1} записано неверно")
                val name = (c.str("name") ?: "").trim()
                if (name.isEmpty()) bad("У правила №${i + 1} нет имени")
                if (name.length > 64) bad("Имя правила «${name.take(20)}…» длиннее 64 знаков")
                if (name.any { it == '"' || it == '\\' || it < ' ' || it == '\u007f' })
                    bad("Правило «$name»: в имени нельзя кавычки и обратную косую черту")
                if (out.any { it.name == name }) bad("Правило «$name» записано дважды — переименуйте одно")
                // Условиями, а не `when (val e = …)`: над Any? из android.jar kotlinc 2.x считает
                // такой when неисчерпывающим даже с else, а сборка прошивки идёт без предупреждений.
                val e: Any? = c.opt("enabled")
                val enabled = when {
                    e == null || e == JSONObject.NULL -> true
                    e is Boolean -> e
                    e is Number -> e.toInt() != 0
                    else -> bad("Правило «$name»: «включено» — да или нет")
                }
                val who = parseWho(c.optJSONObject("who"), name)
                val w = c.optJSONObject("what") ?: JSONObject()
                val lists = w.optJSONArray("lists").strings().distinct()
                val cust = w.optJSONArray("custom").strings().distinct()
                val all = w.optBoolean("all", false)
                if (all && (lists.isNotEmpty() || cust.isNotEmpty()))
                    bad("Правило «$name»: «весь трафик» не сочетается со списками — оставьте что-то одно")
                for (x in cust) if (custom.none { it.name == x }) bad("Правило «$name»: своего списка «$x» нет")
                val outName = c.str("out") ?: bad("Правило «$name»: выберите, куда направить трафик")
                val o = if (outName == "direct") Output("direct", OutKind.DIRECT) else outputs.firstOrNull { it.name == outName }
                o ?: bad("Правило «$name»: выхода «$outName» нет")
                // Мост Telegram перехватывает соединения только у клиентов раздачи: у трафика
                // самого телефона такого заворота нет, и движок отвергает эту пару (spec.c).
                if (o.kind == OutKind.TGWS && who !is Who.Tether)
                    bad("Правило «$name»: мост Telegram работает только для раздачи")
                out.add(Channel(name, enabled, who, What(lists, cust, all), outName))
            }
            return out
        }

        private fun parseWho(w: JSONObject?, name: String): Who {
            if (w == null) return Who.Phone
            return when (w.str("kind") ?: "phone") {
                "phone" -> Who.Phone
                "apps" -> {
                    val a = w.optJSONArray("uids") ?: JSONArray()
                    val uids = ArrayList<Int>()
                    for (k in 0 until a.length()) {
                        val v = a.opt(k) as? Number ?: bad("Правило «$name»: приложение задано неверно")
                        val u = v.toLong()
                        // uid 0 — root, то есть сам движок и системные демоны: их трафик каналом не
                        // маршрутизируется нарочно (иначе «в туннель» ушёл бы сам туннель), и
                        // движок отвергает такую спеку (spec.c).
                        if (u <= 0 || u > Int.MAX_VALUE) bad("Правило «$name»: такого приложения нет")
                        if (u.toInt() !in uids) uids.add(u.toInt())
                    }
                    if (uids.isEmpty()) bad("Правило «$name»: выберите хотя бы одно приложение")
                    if (uids.size > 32) bad("Правило «$name»: приложений больше 32 — разделите на два правила")
                    Who.Apps(uids)
                }
                "tether" -> {
                    val from = w.optJSONArray("from").strings().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    if (from.size > 32) bad("Правило «$name»: адресов больше 32")
                    for (f in from) if (!addrOk(f) && !MAC.matches(f))
                        bad("Правило «$name»: «$f» — не адрес, не подсеть и не MAC")
                    val macs = from.count { MAC.matches(it) }
                    // nft не умеет «или» внутри правила — движок отвергает смесь (spec.c).
                    if (macs in 1 until from.size) bad("Правило «$name»: адреса и MAC смешивать нельзя — разделите на два правила")
                    Who.Tether(from.map { if (MAC.matches(it)) it.lowercase() else it })
                }
                else -> bad("Правило «$name»: неизвестно, к кому оно относится")
            }
        }

        internal fun addrOk(s: String): Boolean {
            val parts = s.split('/')
            if (parts.size > 2) return false
            val ip = parts[0].split('.')
            if (ip.size != 4) return false
            for (p in ip) {
                if (p.isEmpty() || p.length > 3 || !p.all { it.isDigit() } || p.toInt() > 255) return false
            }
            if (parts.size == 2) {
                val l = parts[1]
                if (l.isEmpty() || l.length > 2 || !l.all { it.isDigit() } || l.toInt() > 32) return false
            }
            return true
        }

        private fun outputJson(x: Output): JSONObject {
            val o = JSONObject().put("name", x.name).put("kind", x.kind.word)
            when (x.kind) {
                OutKind.INTERFACE -> o.put("devices", jsonArrayOf(x.devices))
                OutKind.VLESS -> o.put("sub", x.sub).put("nodes", jsonArrayOf(x.nodes))
                OutKind.TGWS -> o.put("domain", x.domain)
                OutKind.DIRECT -> {}
            }
            if (x.onFail != null) o.put("on_fail", x.onFail)
            return o
        }

        private fun channelJson(c: Channel): JSONObject {
            val who = when (val w = c.who) {
                Who.Phone -> JSONObject().put("kind", "phone")
                is Who.Apps -> JSONObject().put("kind", "apps").put("uids", jsonArrayOf(w.uids))
                is Who.Tether -> JSONObject().put("kind", "tether").put("from", jsonArrayOf(w.from))
            }
            val what = JSONObject().put("lists", jsonArrayOf(c.what.lists))
                .put("custom", jsonArrayOf(c.what.custom)).put("all", c.what.all)
            return JSONObject().put("name", c.name).put("enabled", c.enabled)
                .put("who", who).put("what", what).put("out", c.out)
        }
    }
}
