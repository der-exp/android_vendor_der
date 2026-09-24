/*
 * Списки на телефоне: кэш каталога, скачанные файлы и их обновление.
 *
 * Перенос splify2-update-lists и list_fetch (rpcd/m-lists.sh). Файлы лежат в каталоге
 * приложения (filesDir/lists) под теми же именами, под которыми уходят движку
 * (/data/misc/steer/lists/<имя>): движок читает только свой каталог, писать туда приложение не
 * может (SELinux), и файл туда кладёт команда сокета put-file (SpecPusher). Копия у приложения
 * нужна трижды: чтобы сравнить скачанное с прежним (просадка объёма, «ничего не изменилось»),
 * чтобы переотдать движку всё разом (его каталог пуст после сброса данных движка), и чтобы
 * откатить список, который движок отверг.
 *
 * Что перенесено из update-lists, с причинами (подробно — у каждого места ниже):
 *   - обновляются только используемые списки (выбранные в каталоге и названные в правилах);
 *   - каталог скачивается рядом и встаёт на место, только разобравшись (заглушка провайдера);
 *   - скачанное проверяется по виду строк и на кратную просадку, иначе остаётся прежнее;
 *   - набор, чей тег не изменился, повторно не качается;
 *   - набор качается один раз на обе половины (подсети и домены одной службы).
 * Что НЕ перенесено: подгонка адресных списков под память (`steer fit`) — у телефона память не
 * та, ради которой её заводили (роутер с 6 МБ overlay); и переименования издателя (см. Catalog).
 */
package com.der.splify2.logic

import org.json.JSONObject
import java.io.File

internal class ListStore(filesDir: File, private val fetcher: Fetcher) {
    val dir = File(filesDir, "lists")
    private val catalogFile = File(filesDir, "catalog/lists.json")
    private val stateFile = File(dir, "state.json")

    companion object {
        const val DEFAULT_CATALOG = "https://github.com/xyzmean/splify2-lists/releases/latest/download/lists.json"
    }

    // ---- каталог ----------------------------------------------------------------------

    fun cachedCatalog(): Catalog? = try {
        Files.readText(catalogFile)?.let { Catalog.parse(it) }
    } catch (e: BridgeError) {
        null
    }

    fun catalogUpdated(): Long = if (catalogFile.isFile) catalogFile.lastModified() / 1000 else 0

    /** Скачать каталог и поставить на место — только если он разобрался.
     *
     *  Рядом, а не поверх: заглушка провайдера или captive portal отдают 200 и HTML, и прежде на
     *  роутере рабочий манифест перезаписывался страницей — каталог в интерфейсе пустел до
     *  следующей удачной ночи. */
    fun refreshCatalog(url: String?): Pair<Catalog, String?> {
        val f = fetcher.get(url ?: DEFAULT_CATALOG)
        val text = String(f.body, Charsets.UTF_8)
        val cat = Catalog.parse(text)
        Files.writeText(catalogFile, text)
        return cat to f.note
    }

    /** Каталог с диска, а нет на диске — скачать (свежий телефон, где вкладку ещё не открывали). */
    fun catalog(url: String?): Catalog = cachedCatalog() ?: refreshCatalog(url).first

    /** Смена адреса каталога: кэш прежнего убирается сразу, иначе экран показывал бы чужой. */
    fun dropCatalog() { catalogFile.delete() }

    // ---- файлы ------------------------------------------------------------------------

    fun file(name: String) = File(dir, name)
    fun has(name: String) = file(name).isFile && file(name).length() > 0

    private fun state(): JSONObject = Files.readJson(stateFile) ?: JSONObject()
    private fun saveState(s: JSONObject) = Files.writeText(stateFile, s.toString())

    fun info(name: String): JSONObject? = state().optJSONObject(name)

    /** Сужение адресного списка по протоколу и портам (из набора sing-box) — для SpecBuilder. */
    fun narrow(name: String): Narrow? {
        val n = info(name)?.optJSONObject("narrow") ?: return null
        val r = Narrow(n.str("proto"), n.optJSONArray("ports").strings())
        return if (r.empty) null else r
    }

    /** Вторая, адресная половина обычного списка каталога, в котором домены и подсети
     *  вперемешку (так пишутся свои списки splify2-lists, см. его lists/README.md). */
    fun extraPrefixes(name: String): String? = info(name)?.str("extra")?.takeIf { has(it) }

    // ---- обновление -------------------------------------------------------------------

    class Outcome(val name: String, val changed: Boolean, val error: String?, val note: String? = null)

    private class Candidate(val name: String, val part: Catalog.Part, val text: String, val narrow: Narrow?, val extra: String?)

    /** Обновить службу каталога: скачать её половины, проверить и положить.
     *
     *  Набор качается ОДИН раз на обе половины: они — один файл у издателя, и выбросить вторую
     *  значило бы скачать те же байты снова, когда до неё дойдёт очередь (на роутере — то же
     *  «КЛАДЁМ ОБЕ ПОЛОВИНЫ» в list_fetch). */
    // Обновление службы — под замком: его зовут и spec.apply (докачать недостающее), и поток
    // lists.update, а state.json — общий файл «прочитать-изменить-записать»; два прогона
    // вперемешку теряли бы записи друг друга (на роутере — замок каталогом в update-lists).
    @Synchronized
    fun updateService(svc: Catalog.Service, baseUrl: String?, force: Boolean): List<Outcome> {
        val out = ArrayList<Outcome>()
        val st = state()
        val candidates = ArrayList<Candidate>()
        // Наборы: по ссылке.
        for ((url, parts) in svc.parts.filter { it.srsUrl != null }.groupBy { it.srsUrl!! }) {
            // Тег зафиксирован — содержимое релиза не меняется, и качать те же байты каждую
            // ночь незачем. Отметка по ФАЙЛУ, а не по службе: у youtube подсетей нет, и отметка
            // на службу заткнула бы жалобу про них навсегда.
            val fresh = parts.filter { p -> force || p.tag == null || st.optJSONObject(p.engineName)?.str("tag") != p.tag || !has(p.engineName) }
            if (fresh.isEmpty()) { parts.forEach { out.add(Outcome(it.engineName, false, null)) }; continue }
            val res = try {
                val f = fetcher.get(url)
                Srs.read(f.body).also { r -> if (f.note != null) out.add(Outcome(parts[0].engineName, false, null, f.note)); r.warnings.forEach { w -> out.add(Outcome(parts[0].engineName, false, null, w)) } }
            } catch (e: SrsError) {
                val why = if (e.unsupported) "такой список пока не подходит: ${e.message}" else "скачался испорченный набор"
                parts.forEach { out.add(Outcome(it.engineName, false, "${it.name}: $why — оставлен прежний")) }
                continue
            } catch (e: BridgeError) {
                parts.forEach { out.add(Outcome(it.engineName, false, "${it.name}: не скачался — оставлен прежний")) }
                continue
            }
            for (p in fresh) {
                val text = if (p.kind == ListText.Kind.DOMAINS) res.domains else res.prefixes
                if (ListText.records(text) == 0) {
                    // Вида в наборе может не оказаться, хотя каталог его объявляет: истину
                    // говорит сам набор. Молчать нельзя — человек выбрал и вправе узнать.
                    out.add(Outcome(p.engineName, false, "${p.name}: в наборе нет ${if (p.kind == ListText.Kind.DOMAINS) "доменов" else "подсетей"}"))
                    continue
                }
                candidates.add(Candidate(p.engineName, p, text, if (p.kind == ListText.Kind.PREFIXES) res.narrow else null, null))
            }
        }
        // Обычные списки: base_url + путь у издателя.
        for (p in svc.parts.filter { it.srsUrl == null }) {
            if (baseUrl == null) { out.add(Outcome(p.engineName, false, "${p.name}: в каталоге нет адреса списков")); continue }
            val f = try {
                fetcher.get("$baseUrl/${p.file}")
            } catch (e: BridgeError) {
                out.add(Outcome(p.engineName, false, "${p.name}: не скачался — оставлен прежний")); continue
            }
            if (f.note != null) out.add(Outcome(p.engineName, false, null, f.note))
            var text = String(f.body, Charsets.UTF_8)
            var extra: String? = null
            if (p.kind == ListText.Kind.DOMAINS) {
                // Свои списки каталога пишутся «вперемешку» (lists/README.md splify2-lists):
                // адресные строки уходят в набор, доменные — в резолвер. Разводим их на два
                // файла здесь, иначе адреса в доменном списке резолвер молча пропускает.
                val (d, pf) = ListText.split(text)
                if (pf.isNotEmpty()) {
                    val pfx = pf.joinToString("\n", postfix = "\n")
                    val half = Catalog.Part(p.id, ListText.Kind.PREFIXES, p.name, p.file, null, null, null, false)
                    extra = Names.flat("m-", p.id, ".lst")
                    candidates.add(Candidate(extra, half, pfx, null, null))
                    text = d.joinToString("\n", postfix = "\n")
                }
            }
            candidates.add(Candidate(p.engineName, p, text, null, extra))
        }

        for (c in candidates) {
            val name = c.name
            val kindWord = if (c.part.kind == ListText.Kind.DOMAINS) "домены" else "подсети"
            val bad = ListText.badLines(c.part.kind, c.text)
            if (bad > 0) {
                out.add(Outcome(name, false, "${c.part.name}: в скачанном $bad строк не похожи на $kindWord — оставлен прежний"))
                continue
            }
            val newCount = ListText.records(c.text)
            val prevText = Files.readText(file(name))
            val prevCount = ListText.records(prevText)
            if (ListText.shrank(newCount, prevCount)) {
                out.add(Outcome(name, false, "${c.part.name}: записей в скачанном $newCount против $prevCount прежних — оставлен прежний"))
                continue
            }
            val bytes = c.text.toByteArray(Charsets.UTF_8)
            val rec = JSONObject().put("part", c.part.id).put("kind", if (c.part.kind == ListText.Kind.DOMAINS) "domains" else "prefixes")
                .put("service", svc.id).put("count", newCount).put("sha", sha256hex(bytes))
            c.part.tag?.let { rec.put("tag", it) }
            c.narrow?.let { rec.put("narrow", JSONObject().put("proto", it.proto ?: JSONObject.NULL).put("ports", jsonArrayOf(it.ports))) }
            c.extra?.let { rec.put("extra", it) }
            val same = prevText != null && prevText == c.text
            val prev = st.optJSONObject(name)
            rec.put("updated", if (same) prev?.optLong("updated", nowSec()) ?: nowSec() else nowSec())
            if (!same) {
                // Прежний файл — в .prev: если движок отвергнет обновлённые списки, их вернут
                // (rollback), как на роутере после неудачного apply.
                if (prevText != null) file(name).copyTo(file("$name.prev"), overwrite = true)
                Files.write(file(name), bytes)
            }
            st.put(name, rec)
            out.add(Outcome(name, !same, null))
        }
        saveState(st)
        return out
    }

    /** Вернуть прежние копии (после того как движок отверг обновлённые списки). */
    fun rollback(names: Collection<String>) {
        for (n in names) {
            val p = file("$n.prev")
            if (p.isFile) { p.copyTo(file(n), overwrite = true); p.delete() }
        }
    }

    fun dropPrev(names: Collection<String>) { for (n in names) file("$n.prev").delete() }

    // ---- свои списки --------------------------------------------------------------------

    /** Записать файлы своего списка (домены и подсети — раздельно, как у каталога). Пустая
     *  половина файла не получает: канал со ссылкой на пустой файл — лишнее предупреждение
     *  движка при каждом применении. */
    fun writeCustom(c: CustomList): List<String> {
        val names = ArrayList<String>()
        fun put(prefix: String, lines: List<String>) {
            val name = Names.flat(prefix, c.name, ".lst")
            if (lines.isEmpty()) { file(name).delete(); return }
            val text = lines.joinToString("\n", postfix = "\n")
            if (Files.readText(file(name)) != text) Files.write(file(name), text.toByteArray(Charsets.UTF_8))
            names.add(name)
        }
        put("ud-", c.domains)
        put("up-", c.prefixes)
        return names
    }

    fun customNames(c: CustomList): Pair<String?, String?> =
        (if (c.domains.isNotEmpty()) Names.flat("ud-", c.name, ".lst") else null) to
            (if (c.prefixes.isNotEmpty()) Names.flat("up-", c.name, ".lst") else null)
}
