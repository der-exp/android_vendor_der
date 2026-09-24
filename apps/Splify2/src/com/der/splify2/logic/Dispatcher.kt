/*
 * Методы логики для моста (apps/Splify2/BRIDGE.md): группы settings, spec, lists, subs, backup.
 *
 * Роль — та же, что у объекта rpcd `splify2` на роутере: экран зовёт метод по имени с JSON и
 * получает JSON. Отличия — от платформы:
 *   - движок — не программа, а управляющий сокет (Engine): спека уходит телом команды, файлы
 *     списков — командой put-file (SpecPusher ниже);
 *   - источник правды — модель (Model.kt), спека из неё собирается (SpecBuilder.kt);
 *   - сеть — у приложения (Http), и скачивание подписок и списков делается здесь, а не движком.
 *
 * ДОГОВОР С ОБОЛОЧКОЙ: `call` возвращает значение `result` (строку JSON), а отказ — исключением
 * BridgeError с кодом из BRIDGE.md; конверт {"ok":…} собирает оболочка. Сверх договора, с
 * умолчаниями, чтобы трёхаргументный конструктор оболочки собирался как есть:
 *   - `device` — что сказать панели подписки об устройстве (заголовки);
 *   - `engineListsDir` — где файлы списков у движка (стенд подставляет свой каталог);
 *   - `onEvent` — события `lists.updated` и `subs.updated` (BRIDGE.md, «События»): их порождает
 *     фоновая работа логики, и без обратного вызова оболочке их не увидеть.
 *
 * Методы могут приходить одновременно (пул потоков оболочки). Правка модели — под modelLock
 * (прочитать-изменить-записать), разговор с движком (сборка, заливка, apply) — под engineLock:
 * два apply вперемешку залили бы файлы одного и спеку другого.
 */
package com.der.splify2.logic

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class Dispatcher(
    filesDir: File,
    engine: Engine,
    http: Http,
    device: DeviceInfo = DeviceInfo(),
    engineListsDir: String = SpecBuilder.DEFAULT_LISTS_DIR,
) {
    private val dir = filesDir
    private val engine: Engine = Guarded(engine)
    private val modelFile = File(dir, "model.json")
    private val appliedFile = File(dir, "applied.json")
    private val pushedFile = File(dir, "lists/pushed.json")
    private val stampFile = File(dir, "lists/last-update.json")
    private val fetcher = Fetcher(http)
    private val lists = ListStore(dir, fetcher)
    private val subs = SubStore(dir, http, device)
    private val builder = SpecBuilder(engineListsDir)
    private val modelLock = Any()
    private val engineLock = Any()
    private val updating = AtomicBoolean(false)

    /** Итог последнего spec.apply (и фоновых переприменений) — по нему оболочка решает про
     *  Private DNS. После перезапуска приложения восстанавливается из снимка применённого. */
    @Volatile
    var lastApply: ApplyResult? = Files.readJson(appliedFile)?.let {
        ApplyResult(applied = it.optBoolean("applied", false), saved = true, needsLocalDns = it.optBoolean("needs_local_dns", false))
    }
        private set

    /** Движок, у которого любой отказ, кроме BridgeError, — «движок не отвечает».
     *
     *  Реализация сокета у оболочки вправе бросить IOException (сокета нет — движок не запущен),
     *  и без этой обёртки человек увидел бы «внутреннюю ошибку приложения» там, где на самом деле
     *  выключен или упал движок, — то есть совет искать не там. */
    private class Guarded(private val e: Engine) : Engine {
        private fun <T> g(f: () -> T): T = try { f() } catch (x: BridgeError) { throw x } catch (x: Exception) {
            throw BridgeError("engine-down", "Движок не отвечает — включите его или перезагрузите телефон")
        }
        override fun check(spec: String) = g { e.check(spec) }
        override fun apply(spec: String) = g { e.apply(spec) }
        override fun putFile(name: String, data: ByteArray) = g { e.putFile(name, data) }
        override fun status() = g { e.status() }
    }

    @Volatile
    var onEvent: ((name: String, payloadJson: String) -> Unit)? = null

    fun call(method: String, argsJson: String): String {
        val args = try {
            if (argsJson.isBlank() || argsJson == "null") JSONObject() else JSONObject(argsJson)
        } catch (e: Exception) {
            throw BridgeError("bad-args", "Запрос повреждён")
        }
        try {
            val r: Any = when (method) {
                "settings.get" -> loadModel().toJson()
                "settings.put" -> settingsPut(args)
                "spec.preview" -> specPreview()
                "spec.apply" -> specApply(args.optBoolean("force", true))
                "lists.catalog" -> listsCatalog()
                "lists.select" -> listsSelect(args)
                "lists.update" -> listsUpdateAsync(args.optBoolean("force", false))
                "lists.custom" -> listsCustom(args)
                "subs.list" -> subsList()
                "subs.add" -> subsAdd(args)
                "subs.remove" -> subsRemove(args)
                "subs.refresh" -> subsRefresh(args.str("id"))
                "backup.export" -> backupExport()
                "backup.import" -> backupImport(args)
                else -> throw BridgeError("unknown-method", "Эта версия приложения не знает такого действия")
            }
            return r.toString()
        } catch (e: BridgeError) {
            throw e
        } catch (e: Exception) {
            throw BridgeError("internal", "Внутренняя ошибка приложения — повторите действие")
        }
    }

    // ---- модель -----------------------------------------------------------------------

    private fun loadModel(): Model {
        val o = Files.readJson(modelFile) ?: return Model.empty()
        return try {
            Model.parse(o)
        } catch (e: BridgeError) {
            // Файл, который мы сами записали, не разбирается — значит его испортило что-то
            // снаружи (сбой записи, откат данных). Отказывать во всём приложении нельзя: человек
            // не смог бы даже импортировать резервную копию. Отдаём пустую модель, а
            // испорченный файл оставляем рядом — его можно достать.
            modelFile.copyTo(File(dir, "model.broken.json"), overwrite = true)
            Model.empty()
        }
    }

    private fun saveModel(m: Model) = Files.writeText(modelFile, m.toJson().toString())

    private fun settingsPut(a: JSONObject): JSONObject = synchronized(modelLock) {
        val cur = loadModel()
        // Подписки меняются только своими методами (subs.add/remove): за подпиской стоит файл
        // узлов, и модель без него — выход, который не соберётся. Из присланного берём только
        // новые названия уже известных подписок.
        val names = HashMap<String, String>()
        a.optJSONArray("subs")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { s -> s.str("id")?.let { id -> s.str("name")?.let { names[id] = it } } }
        }
        val subsJson = JSONArray()
        for (s in cur.subs) subsJson.put(JSONObject().put("id", s.id).put("name", names[s.id] ?: s.name).put("kind", s.kind).also { o -> s.url?.let { o.put("url", it) } })
        a.put("subs", subsJson)
        val m = Model.parse(a)
        if (m.catalogUrl != cur.catalogUrl) lists.dropCatalog()
        saveModel(m)
        JSONObject().put("saved", true)
    }

    // ---- файлы для сборки ---------------------------------------------------------------

    private inner class Src(private val cat: Catalog?) : FileSource {
        override fun service(id: String) = cat?.service(id)
        override fun has(name: String) = lists.has(name)
        override fun narrow(name: String) = lists.narrow(name)
        override fun extraPrefixes(name: String) = lists.extraPrefixes(name)
        override fun custom(c: CustomList) = lists.customNames(c)
        override fun subFile(id: String) = subs.engineName(id)
    }

    private fun fileBytes(name: String): ByteArray? {
        if (name.startsWith("sub-")) {
            // sub-<id>-<хеш>.txt: отдаём текущее содержимое, только если хеш совпал — иначе
            // под старым именем уехало бы новое содержимое, и хеш в имени перестал бы значить.
            val id = name.removePrefix("sub-").substringBefore('-')
            return subs.content(id)?.takeIf { subs.engineName(id) == name }
        }
        val f = lists.file(name)
        return if (f.isFile) f.readBytes() else null
    }

    /** Залить файлы в каталог движка. `all` — все, а не только изменившиеся с прошлой заливки.
     *
     *  При spec.apply — все: это действие человека, редкое, а каталог движка мог опустеть
     *  (сброс его данных), и запись «уже залито» у приложения тогда врёт. Байты по локальному
     *  сокету — дело миллисекунд. В фоновом переприменении — только изменившиеся. */
    private fun push(files: Collection<String>, all: Boolean) {
        val pushed = Files.readJson(pushedFile) ?: JSONObject()
        for (name in files) {
            val b = fileBytes(name) ?: continue
            val sha = sha256hex(b)
            if (!all && pushed.str(name) == sha) continue
            val r = engine.putFile(name, b)
            when {
                r.error == "unknown-command" -> throw BridgeError("engine",
                    "Эта версия системы не принимает списки и подписки от приложения — правила с ними пока не применить. Обновите систему")
                r.error != null -> throw engineError(r, "Движок не принял файл списка")
                r.code != null && r.code != 0 -> throw BridgeError("engine", "Движок не принял файл списка: ${humanize(r.stderr)}")
            }
            pushed.put(name, sha)
        }
        Files.writeText(pushedFile, pushed.toString())
    }

    private fun engineError(r: CtlReply, what: String): BridgeError {
        val msg = JSONObject(r.raw.ifBlank { "{}" }).let { it.str("message") } ?: ""
        return when (r.error) {
            "busy" -> BridgeError("engine", "Движок занят другой операцией — повторите через несколько секунд")
            "timeout" -> BridgeError("engine", "$what: движок не успел ответить — повторите позже")
            "denied" -> BridgeError("engine-down", "Приложению закрыт доступ к движку — переустановите систему с приложением splify2")
            else -> BridgeError("engine", if (msg.isNotEmpty()) "$what: $msg" else what)
        }
    }

    /** Отказ движка словами человека: строки журнала (`steer[warn]`) — долой, приставку
     *  программы — долой, остаётся причина. Длинный вывод — последние строки: причина отказа
     *  у движка печатается в конце. */
    private fun humanize(stderr: String): String {
        val lines = stderr.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("steer[") }
            .map { it.removePrefix("steer: ").removePrefix("steer:").trim() }
        return lines.takeLast(2).joinToString("; ").ifEmpty { "причина не названа" }.take(400)
    }

    /** Докачать то, на что ссылаются правила, но чего ещё нет у приложения.
     *
     *  Перед каждой сборкой для применения, а не только ночью, — урок роутера (fetch_missing_lists):
     *  пока доскачивание стояло в одном месте, выбранный сервис не заводился до ночи. Отказ
     *  сети не отменяет применения — про каждый несказачанный список скажет предупреждение. */
    private fun ensureFiles(m: Model, cat: Catalog?, warnings: MutableList<String>) {
        for (c in m.custom) if (m.channels.any { c.name in it.what.custom }) lists.writeCustom(c)
        if (cat == null) return
        val ids = m.channels.flatMap { it.what.lists }.toSet()
        for (id in ids) {
            val svc = cat.service(id) ?: continue
            if (svc.parts.all { lists.has(it.engineName) }) continue
            for (o in lists.updateService(svc, cat.baseUrl, false)) o.error?.let { warnings.add(it) }
        }
    }

    private fun catalogOrNull(m: Model): Catalog? = lists.cachedCatalog() ?: try {
        lists.refreshCatalog(m.catalogUrl).first
    } catch (e: BridgeError) {
        null
    }

    // ---- spec ---------------------------------------------------------------------------

    private fun specPreview(): JSONObject {
        val m = loadModel()
        val built = builder.build(m, Src(lists.cachedCatalog()))
        val r = engine.check(built.text)
        val check = JSONObject()
        if (r.error != null) {
            check.put("code", -1).put("stderr", engineError(r, "Проверка не выполнена").message).put("error", r.error)
        } else {
            check.put("code", r.code ?: -1).put("stderr", r.stderr)
        }
        return JSONObject().put("spec", built.spec).put("check", check).put("warnings", jsonArrayOf(built.warnings))
            .put("needs_local_dns", built.needsLocalDns)
    }

    private fun specApply(pushAll: Boolean): JSONObject = synchronized(engineLock) {
        val m = loadModel()
        val warnings = ArrayList<String>()
        val cat = catalogOrNull(m)
        ensureFiles(m, cat, warnings)
        val built = builder.build(m, Src(cat))
        warnings.addAll(built.warnings)
        // Проверка ДО заливки и до apply: отвергнутую спеку незачем сопровождать файлами, и
        // человеку причина нужна сразу, словами проверки, а не словами отката.
        val chk = engine.check(built.text)
        if (chk.error != null) throw engineError(chk, "Проверка настройки не выполнена")
        if (chk.code != 0) throw BridgeError("engine", "Движок не принял настройку: ${humanize(chk.stderr)}")
        push(built.files, pushAll)
        val out = applyBuilt(built, m)
        out.put("warnings", jsonArrayOf(warnings))
    }

    /** apply собранной спеки и учёт итога. Общий для spec.apply и фоновых переприменений. */
    private fun applyBuilt(built: BuiltSpec, m: Model): JSONObject {
        val r = engine.apply(built.text)
        val raw = try { JSONObject(r.raw) } catch (e: Exception) { JSONObject() }
        if (r.error != null && !raw.has("saved")) throw engineError(r, "Настройка не применена")
        val saved = raw.optBoolean("saved", false)
        val applied = raw.optBoolean("applied", false)
        val enabled = if (raw.has("enabled")) raw.optBoolean("enabled") else null
        val rolled = raw.optBoolean("rolled_back", false)
        val res = JSONObject().put("applied", applied).put("saved", saved)
        val message: String? = when {
            applied -> null
            saved && enabled == false -> "Настройка сохранена; правила заработают, когда движок включат"
            rolled -> "Система не приняла новые правила — оставлены прежние: ${humanize(r.stderr)}"
            r.error == "timeout" -> "Применение не уложилось в срок и остановлено — прежние правила на месте"
            else -> "Движок не принял настройку: ${humanize(r.stderr)}"
        }
        if (rolled) res.put("rolled_back", true)
        if (message != null) res.put("message", message)
        if (saved) {
            Files.writeText(appliedFile, JSONObject().put("model", m.toJson()).put("needs_local_dns", built.needsLocalDns)
                .put("applied", applied).put("at", nowSec()).toString())
        }
        val prevDns = lastApply?.needsLocalDns ?: false
        lastApply = ApplyResult(applied, saved, if (saved) built.needsLocalDns else prevDns, message)
        return res
    }

    private fun appliedModel(): Model? = Files.readJson(appliedFile)?.optJSONObject("model")?.let {
        try { Model.parse(it) } catch (e: BridgeError) { null }
    }

    /** Переприменить то, что уже у движка, с обновлёнными файлами (списки, подписки).
     *
     *  Собирается из СНИМКА применённой модели, а не из текущей: у человека могут быть правки,
     *  которые он ещё не применял, и ночное обновление не вправе применить их за него (на роутере
     *  так же: update-lists применяет сохранённую спеку, а не черновик интерфейса). Возвращает
     *  null, если применять нечего, иначе текст-отчёт. */
    internal fun reapply(changed: Collection<String>): String? = synchronized(engineLock) {
        val m = appliedModel() ?: return null
        val built = builder.build(m, Src(lists.cachedCatalog()))
        if (changed.none { it in built.files }) return null
        push(built.files, false)
        val res = applyBuilt(built, m)
        if (res.optBoolean("saved") || res.optBoolean("applied")) {
            lists.dropPrev(changed)
            return null
        }
        // Движок отверг обновлённые списки — вернуть прежние и применить снова, как роутер.
        lists.rollback(changed)
        val again = builder.build(m, Src(lists.cachedCatalog()))
        push(again.files, false)
        val res2 = applyBuilt(again, m)
        return if (res2.optBoolean("saved")) "движок отверг обновлённые списки — работают прежние"
        else "движок отверг и прежние списки — откройте правила и примените их заново"
    }

    // ---- lists ----------------------------------------------------------------------------

    private fun listsCatalog(): JSONObject {
        val m0 = loadModel()
        val cat = lists.catalog(m0.catalogUrl)
        // «default_on — список включён у того, кто ничего не выбирал»: свежий телефон получает
        // выбор каталога один раз, при первом открытии; дальше выбор — человека.
        val m = if (!modelFile.exists()) synchronized(modelLock) {
            m0.copy(lists = cat.services.filter { it.defaultOn }.map { it.id }).also { saveModel(it) }
        } else m0
        val used = m.channels.flatMap { it.what.lists }.toSet()
        val items = JSONArray()
        for (s in cat.services) {
            val x = JSONObject().put("id", s.id).put("name", s.name)
                .put("kinds", jsonArrayOf(s.parts.map { if (it.kind == ListText.Kind.DOMAINS) "domains" else "prefixes" }.distinct()))
                .put("default_on", s.defaultOn).put("selected", s.id in m.lists).put("used", s.id in used)
            s.description?.let { x.put("description", it) }
            s.source?.let { x.put("source", it) }
            s.count?.let { x.put("count", it) }
            s.tag?.let { x.put("tag", it) }
            val have = s.parts.mapNotNull { p -> lists.info(p.engineName)?.takeIf { lists.has(p.engineName) } }
            if (have.isNotEmpty()) x.put("downloaded", JSONObject().put("count", have.sumOf { it.optInt("count") })
                .put("updated", have.maxOf { it.optLong("updated") }))
            s.parts.firstNotNullOfOrNull { lists.narrow(it.engineName) }?.let { n ->
                x.put("narrow", JSONObject().put("proto", n.proto ?: JSONObject.NULL).put("ports", jsonArrayOf(n.ports)))
            }
            items.put(x)
        }
        val last = Files.readJson(stampFile)
        return JSONObject().put("version", cat.version).put("updated", lists.catalogUpdated())
            .put("last_update", last ?: JSONObject.NULL).put("items", items)
    }

    private fun listsSelect(a: JSONObject): JSONObject = synchronized(modelLock) {
        val id = a.str("id") ?: throw BridgeError("bad-args", "Не указан список")
        val on = a.optBoolean("on", true)
        val m = loadModel()
        val next = if (on) (m.lists + id).distinct() else m.lists - id
        saveModel(m.copy(lists = next))
        JSONObject().put("saved", true)
    }

    private fun listsUpdateAsync(force: Boolean): JSONObject {
        if (!updating.compareAndSet(false, true)) return JSONObject().put("started", false).put("running", true)
        val t = Thread({
            val rep = try {
                updateListsNow(force)
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("changed", 0).put("message", (e as? BridgeError)?.message ?: "Обновление списков прервалось")
            } finally {
                updating.set(false)
            }
            onEvent?.invoke("lists.updated", rep.toString())
        }, "splify2-lists")
        t.isDaemon = true
        t.start()
        return JSONObject().put("started", true)
    }

    /** Обновить используемые списки и, если что-то изменилось, переприменить (синхронно — для
     *  фоновой работы и для потока lists.update). Отчёт — в форме события `lists.updated`. */
    internal fun updateListsNow(force: Boolean): JSONObject {
        val m = loadModel()
        val notes = ArrayList<String>()
        val cat = try {
            val (c, note) = lists.refreshCatalog(m.catalogUrl)
            note?.let { notes.add("каталог: $it") }
            c
        } catch (e: BridgeError) {
            notes.add("каталог не обновился, работаем с прежним")
            lists.cachedCatalog()
        } ?: return JSONObject().put("ok", false).put("changed", 0).put("message", "Каталог списков недоступен — проверьте подключение")

        // Обновляются ТОЛЬКО используемые списки: скачивать всё, что есть в каталоге, значит
        // тратить трафик на то, что никуда не подключено. Используемые — выбранные человеком и
        // названные в правилах (текущих и уже применённых: применённое правило работает, даже
        // если в черновике его убрали).
        val ids = LinkedHashSet<String>()
        ids.addAll(m.lists)
        m.channels.forEach { ids.addAll(it.what.lists) }
        appliedModel()?.channels?.forEach { ids.addAll(it.what.lists) }
        val changed = ArrayList<String>()
        var failed = 0
        for (id in ids) {
            val svc = cat.service(id) ?: continue
            for (o in lists.updateService(svc, cat.baseUrl, force)) {
                if (o.changed) changed.add(o.name)
                if (o.error != null) { failed++; notes.add(o.error) }
                o.note?.let { notes.add(it) }
            }
        }
        var ok = failed == 0
        if (changed.isNotEmpty()) {
            try {
                reapply(changed)?.let { notes.add(it); ok = false }
            } catch (e: BridgeError) {
                notes.add(e.message ?: "")
                ok = false
            }
        }
        val rep = JSONObject().put("ok", ok).put("changed", changed.size)
        if (notes.isNotEmpty()) rep.put("message", notes.joinToString("\n"))
        Files.writeText(stampFile, JSONObject(rep.toString()).put("at", nowSec()).toString())
        return rep
    }

    private fun listsCustom(a: JSONObject): Any = synchronized(modelLock) {
        val m = loadModel()
        val put = a.optJSONObject("put")
        val remove = a.str("remove")
        if (put != null) {
            val name = put.str("name") ?: throw BridgeError("bad-args", "У списка нет имени")
            var domIn = put.optJSONArray("domains").strings()
            var pfxIn = put.optJSONArray("prefixes").strings()
            put.str("text")?.let { t -> val (d, p) = ListText.split(t); domIn = domIn + d; pfxIn = pfxIn + p }
            val d = ListText.sanitize(ListText.Kind.DOMAINS, domIn)
            val p = ListText.sanitize(ListText.Kind.PREFIXES, pfxIn)
            if (d.lines.isEmpty() && p.lines.isEmpty())
                throw BridgeError("bad-args", "В списке «$name» нет ни одного домена или подсети")
            val cl = CustomList(name, d.lines, p.lines)
            val next = m.custom.filter { it.name != name } + cl
            // Через разбор модели — та же проверка имени, что у settings.put.
            val nm = Model.parse(m.copy(custom = next).toJson())
            saveModel(nm)
            return@synchronized JSONObject().put("saved", true).put("domains", d.lines.size).put("prefixes", p.lines.size).put("dropped", d.bad + p.bad)
        }
        if (remove != null) {
            m.channels.firstOrNull { remove in it.what.custom }?.let {
                throw BridgeError("bad-args", "Список «$remove» используется в правиле «${it.name}» — сначала уберите его оттуда")
            }
            saveModel(m.copy(custom = m.custom.filter { it.name != remove }))
            return@synchronized JSONObject().put("saved", true)
        }
        val used = m.channels.flatMap { it.what.custom }.toSet()
        JSONArray().also { arr ->
            m.custom.forEach {
                arr.put(JSONObject().put("name", it.name).put("domains", jsonArrayOf(it.domains))
                    .put("prefixes", jsonArrayOf(it.prefixes)).put("used", it.name in used))
            }
        }
    }

    // ---- subs -----------------------------------------------------------------------------

    private fun subsList(): JSONArray {
        val m = loadModel()
        val out = JSONArray()
        val hw = subs.hwid()
        for (s in m.subs) {
            val info = subs.info(s.id)
            val x = JSONObject().put("id", s.id).put("name", s.name.ifEmpty { info.str("title") ?: "" }).put("kind", s.kind)
                .put("nodes", info.optInt("usable", 0)).put("updated", info.optLong("updated", 0))
                .put("skipped", info.optInt("skipped", 0)).put("foreign", info.optInt("foreign", 0))
                .put("used_by", jsonArrayOf(m.outputs.filter { it.sub == s.id }.map { it.name })).put("hwid", hw)
            s.url?.let { x.put("url", it) }
            info.optJSONObject("quota")?.let { x.put("quota", it) }
            info.str("warn")?.let { x.put("warn", it) }
            info.str("link")?.let { x.put("link", it) }
            info.optJSONObject("reasons")?.let { x.put("reasons", it) }
            out.put(x)
        }
        return out
    }

    private fun newSubId(m: Model): String {
        var k = 1
        while (m.subs.any { it.id == "s$k" }) k++
        return "s$k"
    }

    private fun subsAdd(a: JSONObject): JSONArray {
        val input = (a.str("url") ?: throw BridgeError("bad-args", "Не указана ссылка")).trim()
        val given = a.str("name")?.trim()?.take(64)
        // Две формы одного и того же, как sub_set на роутере: ссылка на подписку и сами ссылки
        // vless://. Движок различия не знает — он читает файл, где по строке на узел; подписка —
        // это такой же файл, который держит на сервере кто-то другой. Значит «вставить одну
        // vless://» — не новая возможность, а человек с одним сервером от знакомого.
        if (input.startsWith("http://") || input.startsWith("https://")) {
            val f = subs.fetch(input)   // сеть — до блокировки модели
            synchronized(modelLock) {
                val m = loadModel()
                val id = newSubId(m)
                val (title, link) = SubHeaders.brand(f.url, SubHeaders.title(f.headers))
                val warn = SubHeaders.deviceWarn(f.headers, f.hwidSent)
                subs.store(id, f.body, f.headers, f.stats) {
                    put("title", title); link?.let { put("link", it) }; warn?.let { put("warn", it) }
                }
                // Ссылка — из ответа, а не та, что просили: могли перезапросить с /json, и
                // обновлять надо по той.
                saveModel(m.copy(subs = m.subs + Sub(id, given?.ifEmpty { null } ?: title, f.url, "url")))
            }
        } else if (input.contains("vless://")) {
            // Пробелы между ссылками — в переводы строк: из однострочного поля многострочная
            // вставка приезжает склеенной пробелами.
            val links = input.split(Regex("\\s+")).filter { it.startsWith("vless://") }
            if (links.isEmpty()) throw BridgeError("bad-args", "В тексте нет ссылок vless://")
            val body = links.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
            synchronized(modelLock) {
                val m = loadModel()
                val id = newSubId(m)
                subs.store(id, body, null, SubParse.stats(String(body, Charsets.UTF_8)))
                saveModel(m.copy(subs = m.subs + Sub(id, given?.ifEmpty { null } ?: "Свои ссылки", null, "links")))
            }
        } else throw BridgeError("bad-args", "Нужна ссылка на подписку (https://) или ссылка vless://")
        return subsList()
    }

    private fun subsRemove(a: JSONObject): JSONArray {
        val id = a.str("id") ?: throw BridgeError("bad-args", "Не указана подписка")
        synchronized(modelLock) {
            val m = loadModel()
            // Удалять подписку, на которую ссылается выход, нельзя молча: движок читает её при
            // подъёме, и правило осталось бы вести в туннель без единого узла.
            m.outputs.firstOrNull { it.sub == id }?.let {
                throw BridgeError("bad-args", "Подписку использует выход «${it.name}» — сначала уберите или перенастройте его")
            }
            saveModel(m.copy(subs = m.subs.filter { it.id != id }))
            subs.delete(id)
        }
        return subsList()
    }

    private fun subsRefresh(id: String?): JSONArray {
        refreshSubsNow(id, emit = false)
        return subsList()
    }

    /** Обновить подписки со ссылкой (одну или все) и переприменить, если изменился файл
     *  подписки, которую использует применённый выход. Отказ одной не останавливает остальные. */
    internal fun refreshSubsNow(only: String?, emit: Boolean): List<String> {
        val m = loadModel()
        val targets = m.subs.filter { it.kind == "url" && it.url != null && (only == null || it.id == only) }
        if (only != null && targets.isEmpty()) {
            if (m.subs.none { it.id == only }) throw BridgeError("bad-args", "Такой подписки нет")
            throw BridgeError("bad-args", "Эту подписку нечем обновить — она из вставленных ссылок")
        }
        val errors = ArrayList<String>()
        val changed = ArrayList<String>()
        for (s in targets) {
            val before = subs.engineName(s.id)
            try {
                val f = subs.fetch(s.url!!)
                val warn = SubHeaders.deviceWarn(f.headers, f.hwidSent)
                val (title, link) = SubHeaders.brand(f.url, SubHeaders.title(f.headers))
                subs.store(s.id, f.body, f.headers, f.stats) {
                    put("title", title); link?.let { put("link", it) }; warn?.let { put("warn", it) }
                }
                if (f.url != s.url) synchronized(modelLock) {
                    val cur = loadModel()
                    saveModel(cur.copy(subs = cur.subs.map { if (it.id == s.id) it.copy(url = f.url) else it }))
                }
                if (subs.engineName(s.id) != before) changed.add(s.id)
            } catch (e: BridgeError) {
                errors.add("${s.name.ifEmpty { s.id }}: ${e.message}")
            }
        }
        if (changed.isNotEmpty()) {
            val am = appliedModel()
            if (am != null && am.outputs.any { it.sub in changed }) {
                try {
                    synchronized(engineLock) {
                        val built = builder.build(am, Src(lists.cachedCatalog()))
                        push(built.files, false)
                        applyBuilt(built, am)
                    }
                } catch (e: BridgeError) {
                    errors.add(e.message ?: "")
                }
            }
        }
        if (emit) onEvent?.invoke("subs.updated", subsList().toString())
        if (only != null && errors.isNotEmpty()) throw BridgeError("network", errors.first().substringAfter(": "))
        return errors
    }

    // ---- backup ---------------------------------------------------------------------------

    private fun backupExport(): JSONObject {
        val m = loadModel()
        val subsContent = JSONObject()
        for (s in m.subs) subs.content(s.id)?.let { subsContent.put(s.id, String(it, Charsets.UTF_8)) }
        val o = JSONObject()
            .put("format", Backup.FORMAT).put("version", Backup.VERSION).put("created", nowSec())
            // Как в шапке архива роутера: внутри ссылки vless:// — то же, что пароль от всех
            // подключений. Предупреждение лежит в самом файле, потому что файл уходит дальше
            // экрана, на котором его сохранили.
            .put("note", "Настройки splify2. ВНИМАНИЕ: внутри ссылки подписок — это доступ ко всем подключениям; не выкладывайте файл в чаты и общие папки.")
            .put("model", m.toJson()).put("subs", subsContent)
        val name = "splify2-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".json"
        val f = File(dir, "exports/$name")
        File(dir, "exports").listFiles()?.forEach { it.delete() }   // старые выгрузки не копим
        Files.writeText(f, o.toString(2))
        return JSONObject().put("file", f.absolutePath).put("name", name).put("bytes", f.length())
    }

    private fun backupImport(a: JSONObject): JSONObject {
        val text = a.str("json") ?: throw BridgeError("bad-args", "Файл настроек пуст")
        val parsed = Backup.parse(text)
        synchronized(modelLock) {
            // Сначала файлы подписок, потом модель: модель со ссылкой на подписку без файла
            // собралась бы в отказ («подписка ещё не скачана»).
            for ((id, body) in parsed.subs) {
                val b = body.toByteArray(Charsets.UTF_8)
                subs.store(id, b, null, SubParse.stats(body))
            }
            if (parsed.model.catalogUrl != loadModel().catalogUrl) lists.dropCatalog()
            saveModel(parsed.model)
        }
        return JSONObject().put("saved", true)
    }
}

/** Резервная копия — JSON модели плюс содержимое подписок. */
internal object Backup {
    const val FORMAT = "splify2-android-backup"
    const val VERSION = 1
    private const val MAX_SUB = 1 shl 20

    class Parsed(val model: Model, val subs: Map<String, String>)

    /** Разбор строгий: чужой или испорченный файл — отказ целиком, а не «что понял — то взял»
     *  (как backup_split на роутере: молча восстановить половину хуже, чем ничего). */
    fun parse(text: String): Parsed {
        val o = try { JSONObject(text) } catch (e: Exception) {
            throw BridgeError("bad-args", "Это не файл настроек splify2")
        }
        if (o.str("format") != FORMAT) throw BridgeError("bad-args", "Это не файл настроек splify2 для телефона")
        if (o.optInt("version", 0) > VERSION) throw BridgeError("bad-args", "Файл сохранён более новой версией приложения — обновите приложение")
        val mj = o.optJSONObject("model") ?: throw BridgeError("bad-args", "В файле настроек нет самих настроек")
        val model = Model.parse(mj)
        val subs = LinkedHashMap<String, String>()
        val sj = o.optJSONObject("subs") ?: JSONObject()
        for (s in model.subs) {
            val body = sj.str(s.id)
            if (body == null) {
                if (s.kind == "links") throw BridgeError("bad-args", "В файле нет ссылок подписки «${s.name}»")
                continue   // подписку со ссылкой можно скачать заново
            }
            if (body.length > MAX_SUB) throw BridgeError("bad-args", "Подписка «${s.name}» в файле слишком велика")
            subs[s.id] = body
        }
        return Parsed(model, subs)
    }
}
