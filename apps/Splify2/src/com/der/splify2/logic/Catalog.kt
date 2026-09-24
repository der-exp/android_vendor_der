/*
 * Каталог списков splify2-lists (lists.json) — разбор и сведение в «службы».
 *
 * Формат тот же, что читает роутер (splify2-lists/README.md): `categories` — списки ПОДСЕТЕЙ,
 * `domain_lists` — списки ДОМЕНОВ; у записи `file` (путь относительно `base_url`) либо
 * `format: "srs"` и своя `url` (набор sing-box, который надо скачать и разложить, см. Srs.kt).
 *
 * СЛУЖБА — ТО, ЧТО ВИДИТ ЧЕЛОВЕК. Одна строка каталога на экране — это «Telegram», а не два
 * файла: доменная запись ссылается на свою пару подсетей полем `same_as_ip`, и интерфейс роутера
 * склеивает такие записи в одну (toCatalog в ui/src/lib/model.ts — система непересекающихся
 * множеств). Здесь то же правило, и по той же причине: выбрать домены Telegram без его подсетей
 * — значит получить правило, которое работает наполовину, и понять это человеку нечем.
 *
 * Идентификатор службы — id её записи подсетей, если она есть, иначе id доменной записи. У
 * каталога пространства id двух видов не пересекаются (подсети — «itdoginfo:telegram», домены
 * той же службы — «svc_itdoginfo_telegram»); если однажды пересекутся, доменная служба получит
 * приставку «d:», чтобы выбор одной не выбирал другую.
 *
 * ПЕРЕИМЕНОВАНИЙ (`aliases`) здесь нет нарочно — решение владельца: смена состава каталога не
 * сопровождается миграцией. Служба, пропавшая из каталога, остаётся у того, кто её выбрал,
 * последней скачанной копией (ListStore её не трогает), а новые выбираются из нового каталога.
 */
package com.der.splify2.logic

import org.json.JSONObject

internal class Catalog(
    val version: String,
    val baseUrl: String?,
    val services: List<Service>,
) {
    class Part(
        val id: String,
        val kind: ListText.Kind,
        val name: String,
        /** Путь у издателя (для скачивания обычного списка) — в имена файлов НЕ идёт. */
        val file: String,
        val count: Int?,
        val srsUrl: String?,
        val tag: String?,
        val defaultOn: Boolean,
    ) {
        /** Имя файла в каталоге движка (/data/misc/steer/lists/<имя>).
         *
         *  Выводится из id записи, а не из её `file`, и это защита, а не вкус. `file` приходит
         *  из интернета (адрес каталога вдобавок настраивается), и на роутере путь вида
         *  `../../../etc/crontabs/root` давал запись файла от root куда угодно — там это
         *  закрыто проверкой local_path. Здесь имя собирается из безопасных символов по
         *  построению, и проверять нечего. Приставка вида (`d-`/`p-`) разводит половины одной
         *  службы; длина — не больше 31 знака, как у слов управляющего сокета. */
        val engineName: String get() = Names.flat(if (kind == ListText.Kind.DOMAINS) "d-" else "p-", id, ".lst")
    }

    class Service(
        val id: String,
        val name: String,
        val description: String?,
        val source: String?,
        val parts: List<Part>,
    ) {
        val defaultOn get() = parts.any { it.defaultOn }
        val count: Int? get() = parts.mapNotNull { it.count }.takeIf { it.isNotEmpty() }?.sum()
        val tag: String? get() = parts.firstNotNullOfOrNull { it.tag }
    }

    fun service(id: String) = services.firstOrNull { it.id == id }

    companion object {
        private val SAFE_FILE = Regex("^[A-Za-z0-9_./-]+$")

        /** Разбор каталога. Не JSON или не наш формат — BridgeError: заглушка провайдера отдаёт
         *  200 и HTML, и принять её за каталог значило бы опустошить экран списков. */
        fun parse(text: String): Catalog {
            val o = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw BridgeError("network", "Каталог списков пришёл повреждённым — повторите позже")
            }
            if (!o.has("categories") && !o.has("domain_lists"))
                throw BridgeError("network", "По адресу каталога списков лежит не каталог — проверьте адрес")
            val base = o.str("base_url")?.trimEnd('/')
            val cats = ArrayList<JSONObject>()
            val doms = ArrayList<JSONObject>()
            o.optJSONArray("categories")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { cats.add(it) } }
            o.optJSONArray("domain_lists")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { doms.add(it) } }

            fun part(x: JSONObject, kind: ListText.Kind): Part? {
                val id = x.str("id")?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
                val file = x.str("file") ?: ""
                val srs = if (x.str("format") == "srs") x.str("url") else null
                // Обычный список без годного пути скачать нечем — такую запись пропускаем, а не
                // роняем весь каталог: одна испорченная строка издателя не повод опустошить экран.
                if (srs == null && (!SAFE_FILE.matches(file) || file.split('/').any { it == ".." || it == "." || it.isEmpty() }))
                    return null
                if (srs != null && !srs.startsWith("https://") && !srs.startsWith("http://")) return null
                return Part(id, kind, x.str("name_ru") ?: id, file, if (x.has("count")) x.optInt("count") else null,
                    srs, x.str("tag"), x.optBoolean("default_on", false))
            }

            // Система непересекающихся множеств по ключам «c:<id>» и «d:<id>» — как toCatalog.
            val parent = HashMap<String, String>()
            fun find(k: String): String {
                val p = parent[k] ?: k.also { parent[k] = it }
                if (p == k) return k
                val r = find(p)
                parent[k] = r
                return r
            }
            val catIds = cats.mapNotNull { it.str("id") }.toSet()
            for (c in cats) c.str("id")?.let { find("c:$it") }
            for (d in doms) {
                val id = d.str("id") ?: continue
                find("d:$id")
                for (cid in d.optJSONArray("same_as_ip").strings())
                    if (cid in catIds) parent[find("d:$id")] = find("c:$cid")
            }
            val groups = LinkedHashMap<String, MutableList<Pair<Part, JSONObject>>>()
            for (c in cats) part(c, ListText.Kind.PREFIXES)?.let { groups.getOrPut(find("c:${it.id}")) { ArrayList() }.add(it to c) }
            for (d in doms) part(d, ListText.Kind.DOMAINS)?.let { groups.getOrPut(find("d:${it.id}")) { ArrayList() }.add(it to d) }

            val services = ArrayList<Service>()
            val usedIds = HashSet<String>()
            for ((root, parts) in groups) {
                var id = root.substring(2)
                if (root.startsWith("d:") && id in catIds) id = "d:$id"
                if (!usedIds.add(id)) continue
                // Имя и описание — от записи подсетей, если есть (она и даёт id), иначе от доменной.
                val lead = parts.firstOrNull { it.first.kind == ListText.Kind.PREFIXES } ?: parts.first()
                val desc = parts.firstNotNullOfOrNull { it.second.str("description_ru") }
                val src = parts.firstNotNullOfOrNull { it.second.str("source_name") ?: it.second.str("source") }
                services.add(Service(id, lead.first.name, desc, src, parts.map { it.first }))
            }
            return Catalog(o.str("version") ?: "", base, services)
        }
    }
}

/** Имена файлов в каталоге движка. */
internal object Names {
    private val SAFE = Regex("^[A-Za-z0-9_:-]+$")
    private val UNSAFE = Regex("[^A-Za-z0-9_-]")

    /** Плоское имя: приставка + id + окончание, не длиннее 31 знака, из [A-Za-z0-9_.-].
     *
     *  Двоеточие приставки издателя («itdoginfo:telegram») становится точкой — точки в id нет,
     *  поэтому замена взаимно однозначна и имя остаётся читаемым в каталоге движка. Всё прочее
     *  (другие символы, слишком длинный id) даёт обрезанное имя с хвостом из хеша полного id:
     *  иначе два разных id с общим началом сошлись бы в одно имя и затирали бы файлы друг друга. */
    fun flat(prefix: String, id: String, suffix: String): String {
        val room = 31 - prefix.length - suffix.length
        if (SAFE.matches(id) && id.length <= room) return prefix + id.replace(':', '.') + suffix
        val h = sha256hex(id.toByteArray()).take(6)
        return prefix + UNSAFE.replace(id, "_").take(room - 7) + "-" + h + suffix
    }
}
