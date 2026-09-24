/*
 * Сборка спеки движка (spec.json, steer/docs/contract-v1.md) из модели splify2.
 *
 * Спека — ВЫВОД, а не источник: движку отдаётся собранное, приложение её не хранит как правду и
 * обратно не разбирает (см. шапку Model.kt). Отсюда главное правило этого файла: всё, что в
 * спеке появляется, выводится из модели и состояния файлов одним и тем же способом при каждой
 * сборке — иначе две сборки одной модели дали бы разные правила.
 *
 * ЧТО РЕШАЕТСЯ ЗДЕСЬ, с причинами:
 *
 *   - ПОРЯДОК КАНАЛОВ = ПОРЯДОК ПРАВИЛ МОДЕЛИ. Первое совпадение сверху побеждает (контракт),
 *     поэтому перестановка правил — это изменение поведения, и сборщик её не делает никогда.
 *     Спутники (ниже) встают СРАЗУ за своим правилом, чтобы не обогнать и не отстать от него.
 *
 *   - «КОМУ»: весь телефон — `from: ["self"]` (приложения, UID от 10000; демоны и сам движок —
 *     мимо, иначе «всё в туннель» завернуло бы туннель в себя), приложения — `uid:N`, раздача —
 *     как на роутере: без `from` (тогда клиентов задаёт `lan_devices`) или адресами/MAC.
 *
 *   - «ВЕСЬ ТРАФИК» — `any: true` вместе с `allow_all: true`. Движок без `allow_all` такой канал
 *     отвергает («забирает ВЕСЬ трафик в туннель»), и это защита от описки на роутере. На
 *     телефоне правило «весь телефон → туннель» — основной сценарий, и человек выбрал его явно
 *     отдельным пунктом, а не получил пустым списком; поэтому согласие ставится здесь.
 *
 *   - СУЖЕНИЕ ПОДСЕТЕЙ ПО ПРОТОКОЛУ И ПОРТАМ — каналом-спутником, как expandNarrow в интерфейсе
 *     роутера. Сужение — свойство КАНАЛА целиком: правило «Discord» с доменами и подсетями и
 *     `udp 50000-65535` отрезало бы TCP к discord.com. Поэтому домены остаются в правиле, а
 *     подсети с портами едут отдельным каналом сразу за ним, с тем же «кому» и выходом. Схема
 *     поднимается до 2 только тогда, когда сужение и правда записано.
 *
 *   - ИМЕНА КАНАЛОВ в спеке — не длиннее 31 байта (поле движка 32 байта, длиннее — отказ всей
 *     спеки), резаные по границе буквы и уникальные. В модели имя может быть длиннее: движку
 *     оно нужно для status и explain, человеку — полностью.
 *
 *   - ПРАВИЛО, КОТОРОМУ НЕЧЕГО СОВПАДАТЬ (ни списков, ни «весь трафик»), в спеку НЕ идёт: движок
 *     отвергает «channel … matches nothing» целиком, включая выключенные правила, то есть одно
 *     недописанное правило сняло бы все. Об этом — предупреждение в ответе.
 *
 *   - ПУТИ ФАЙЛОВ — /data/misc/steer/lists/<имя> (listsDir). Сами файлы заливает SpecPusher до
 *     apply; здесь только перечень, какие нужны.
 */
package com.der.splify2.logic

import org.json.JSONArray
import org.json.JSONObject

internal class BuiltSpec(
    val spec: JSONObject,
    /** Имена файлов каталога движка, на которые ссылается спека. */
    val files: Set<String>,
    /** Есть ли доменные каналы на сам телефон — для Private DNS (ApplyResult.needsLocalDns). */
    val needsLocalDns: Boolean,
    val warnings: List<String>,
) {
    val text: String get() = spec.toString()
}

/** Где взять файлы для сборки: у каталога — службы, у своих списков — имена, у подписок — имя
 *  файла с хешем. Отдельным интерфейсом, чтобы стенд собирал спеку и без скачивания. */
internal interface FileSource {
    fun service(id: String): Catalog.Service?
    /** Файл лежит у приложения (скачан) — ссылаться на него есть смысл. */
    fun has(name: String): Boolean
    fun narrow(name: String): Narrow?
    fun extraPrefixes(name: String): String?
    fun custom(c: CustomList): Pair<String?, String?>
    fun subFile(id: String): String?
}

internal class SpecBuilder(private val listsDir: String = DEFAULT_LISTS_DIR) {
    companion object {
        const val DEFAULT_LISTS_DIR = "/data/misc/steer/lists"
        const val MAX_CHANNELS = 64
        private const val NAME_BYTES = 31
    }

    private fun path(name: String) = "$listsDir/$name"

    fun build(m: Model, src: FileSource): BuiltSpec {
        val warnings = ArrayList<String>()
        val files = LinkedHashSet<String>()
        var schema2 = false
        var localDns = false

        // ---- выходы ----
        val outputs = JSONObject()
        outputs.put("direct", JSONObject().put("kind", "direct"))
        for (o in m.outputs) {
            val x = JSONObject().put("kind", o.kind.word)
            when (o.kind) {
                OutKind.DIRECT -> {}
                OutKind.INTERFACE -> x.put("devices", jsonArrayOf(o.devices))
                OutKind.VLESS -> {
                    val f = o.sub?.let { src.subFile(it) }
                    if (f == null) {
                        // Выход без файла подписки движок отвергает («kind vless нужен sub_file»),
                        // а указать путь к несуществующему — значит поднять туннель без узлов.
                        // Честнее не собирать спеку вовсе и сказать, что сделать.
                        throw BridgeError("bad-args", "Выход «${o.name}»: подписка ещё не скачана — обновите её")
                    }
                    files.add(f)
                    x.put("sub_file", path(f))
                    if (o.nodes.isNotEmpty()) x.put("nodes", jsonArrayOf(o.nodes))
                }
                OutKind.TGWS -> x.put("domain", o.domain)
            }
            if (o.onFail != null && o.kind != OutKind.DIRECT) x.put("on_fail", o.onFail)
            outputs.put(o.name, x)
        }

        // ---- каналы ----
        val channels = JSONArray()
        val usedNames = HashSet<String>()
        fun specName(base: String): String {
            var n = utf8Cut(base, NAME_BYTES)
            var k = 2
            while (!usedNames.add(n)) {
                val suffix = " $k"
                n = utf8Cut(base, NAME_BYTES - suffix.length) + suffix
                k++
            }
            return n
        }

        for (c in m.channels) {
            val doms = LinkedHashSet<String>()
            val pfx = LinkedHashSet<String>()
            val narrowed = LinkedHashMap<String, Narrow>()   // файл подсетей → сужение
            for (id in c.what.lists) {
                val svc = src.service(id)
                if (svc == null) {
                    warnings.add("Правило «${c.name}»: списка «$id» нет в каталоге — он пропущен")
                    continue
                }
                var any = false
                for (p in svc.parts) {
                    val name = p.engineName
                    if (!src.has(name)) continue
                    any = true
                    files.add(name)
                    if (p.kind == ListText.Kind.DOMAINS) {
                        doms.add(path(name))
                        src.extraPrefixes(name)?.let { files.add(it); pfx.add(path(it)) }
                    } else {
                        pfx.add(path(name))
                        src.narrow(name)?.let { narrowed[path(name)] = it }
                    }
                }
                if (!any) warnings.add("Правило «${c.name}»: список «${svc.name}» ещё не скачан — обновите списки")
            }
            for (cn in c.what.custom) {
                val cl = m.custom.firstOrNull { it.name == cn } ?: continue
                val (d, p) = src.custom(cl)
                d?.let { files.add(it); doms.add(path(it)) }
                p?.let { files.add(it); pfx.add(path(it)) }
            }

            val from: List<String>? = when (val w = c.who) {
                Who.Phone -> listOf("self")
                is Who.Apps -> w.uids.map { "uid:$it" }
                is Who.Tether -> w.from.ifEmpty { null }
            }
            val local = c.who !is Who.Tether

            fun channel(name: String, match: JSONObject): JSONObject {
                val x = JSONObject().put("name", name)
                if (!c.enabled) x.put("enabled", false)
                if (from != null) x.put("from", jsonArrayOf(from))
                return x.put("match", match).put("out", c.out)
            }

            if (c.what.all) {
                channels.put(channel(specName(c.name), JSONObject().put("any", true).put("allow_all", true)))
                continue
            }
            if (doms.isEmpty() && pfx.isEmpty()) {
                if (c.what.empty) warnings.add("Правило «${c.name}»: не выбрано, какой трафик направлять — оно пропущено")
                else warnings.add("Правило «${c.name}»: его списки ещё не скачаны — оно пропущено до обновления списков")
                continue
            }
            if (local && doms.isNotEmpty() && c.enabled) localDns = true

            // Спутники: подсети с одинаковым сужением — в одну группу (как narrowKey).
            val groups = LinkedHashMap<String, Pair<Narrow, MutableList<String>>>()
            for ((f, n) in narrowed) groups.getOrPut("${n.proto}|${n.ports.joinToString(",")}") { n to ArrayList() }.second.add(f)
            val rest = pfx.filter { it !in narrowed }
            val base = specName(c.name)
            val gs = groups.values.toList()
            if (gs.isEmpty()) {
                channels.put(channel(base, matchOf(doms, rest)))
                continue
            }
            schema2 = true
            var sats = gs
            if (doms.isEmpty() && rest.isEmpty()) {
                // Правило из одних суженных подсетей несёт первую группу само: родитель с пустым
                // match — канал, который ничем не совпадает, и движок отверг бы спеку целиком.
                val (n, fs) = gs[0]
                channels.put(channel(base, narrowMatch(matchOf(emptyList(), fs), n)))
                sats = gs.drop(1)
            } else {
                channels.put(channel(base, matchOf(doms, rest)))
            }
            sats.forEachIndexed { i, (n, fs) ->
                val tail = if (i == 0) " (порты)" else " (порты ${i + 1})"
                val nm = specName(utf8Cut(c.name, NAME_BYTES - tail.toByteArray().size) + tail)
                // part_of движок не знает и пропускает (незнакомые ключи он терпит нарочно);
                // по нему видно в status, чьё это правило.
                channels.put(channel(nm, narrowMatch(matchOf(emptyList(), fs), n)).put("part_of", base))
            }
        }
        if (channels.length() > MAX_CHANNELS)
            throw BridgeError("bad-args", "Правил получилось ${channels.length()}, а движок держит не больше $MAX_CHANNELS — объедините часть правил")

        val spec = JSONObject()
        spec.put("schema", if (schema2) 2 else 1)
        spec.put("lan_devices", jsonArrayOf(m.tetherDevices.ifEmpty { Model.DEFAULT_TETHER }))
        spec.put("outputs", outputs)
        spec.put("channels", channels)
        return BuiltSpec(spec, files, localDns, warnings)
    }

    private fun matchOf(doms: Collection<String>, pfx: Collection<String>): JSONObject {
        val m = JSONObject()
        if (pfx.isNotEmpty()) m.put("prefixes_files", jsonArrayOf(pfx))
        if (doms.isNotEmpty()) m.put("domains_files", jsonArrayOf(doms))
        return m
    }

    private fun narrowMatch(m: JSONObject, n: Narrow): JSONObject {
        n.proto?.let { m.put("proto", it) }
        if (n.ports.isNotEmpty()) m.put("ports", jsonArrayOf(n.ports))
        return m
    }
}
