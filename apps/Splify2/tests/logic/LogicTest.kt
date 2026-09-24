/*
 * Стенд логики splify2 на JVM: tools/app-check/logic-test.sh.
 *
 * Что проверяется и против чего:
 *   - сборка спеки из моделей (весь телефон, приложения по UID, раздача, несколько выходов,
 *     порядок каналов, списки каталога и свои, сужение портами, «весь трафик») — и КАЖДАЯ
 *     собранная спека проходит настоящий движок: `check` его управляющего сокета и
 *     `steer apply --dry-run`, код 0 (HostEngine);
 *   - разбор каталога — на настоящем lists.json из splify2-lists;
 *   - разбор наборов .srs — байт в байт с `steer srs-read` на настоящих наборах издателя;
 *   - счёт узлов подписки — с разбором движка (subcount.c) на образцах его стенда;
 *   - методы моста целиком (Dispatcher) против того же движка и записанной сети.
 *
 * Без фреймворка: на машине нет JUnit, а стенду нужна одна функция сравнения и счётчик.
 */
import com.der.splify2.logic.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

var pass = 0
var fail = 0

fun check(name: String, expected: Any?, actual: Any?) {
    if (expected == actual) pass++ else {
        fail++
        println("FAIL $name\n  ожидалось: $expected\n  получено:  $actual")
    }
}

fun expectError(name: String, code: String, contains: String? = null, f: () -> Unit) {
    try {
        f()
        fail++; println("FAIL $name: отказа не было")
    } catch (e: BridgeError) {
        check("$name: код", code, e.code)
        if (contains != null) check("$name: текст содержит «$contains»", true, e.message?.contains(contains) == true)
        // Слова разработки человеку не показываются (память проекта: только состояние и действие).
        val m = e.message ?: ""
        check("$name: без слов разработки", false, Regex("steer|spec\\.json|сокет|rpcd|ubus").containsMatchIn(m))
    }
}

lateinit var STEER: String
lateinit var STEER_SRC: File
lateinit var LISTS_JSON: File
lateinit var SUBCOUNT: String
lateinit var WORK: File

fun tmp(name: String): File = File(WORK, name).also { it.deleteRecursively(); it.mkdirs() }

/** Источник файлов для сборщика без скачивания: файл «есть», если он лежит в каталоге списков стенда. */
internal class StubSource(val cat: Catalog?, val dir: File, val narrows: Map<String, Narrow> = emptyMap(), val subs: Map<String, String> = emptyMap()) : FileSource {
    override fun service(id: String) = cat?.service(id)
    override fun has(name: String) = File(dir, name).isFile
    override fun narrow(name: String) = narrows[name]
    override fun extraPrefixes(name: String): String? = null
    override fun custom(c: CustomList) =
        (if (c.domains.isNotEmpty()) Names.flat("ud-", c.name, ".lst") else null) to (if (c.prefixes.isNotEmpty()) Names.flat("up-", c.name, ".lst") else null)
    override fun subFile(id: String) = subs[id]
}

fun model(json: String): Model = Model.parse(JSONObject(json))

fun channelNames(spec: JSONObject): List<String> {
    val a = spec.getJSONArray("channels")
    return (0 until a.length()).map { a.getJSONObject(it).getString("name") }
}

// ---------------------------------------------------------------------------------------

internal fun testCatalog(): Catalog {
    val cat = Catalog.parse(LISTS_JSON.readText())
    val raw = JSONObject(LISTS_JSON.readText())
    val nc = raw.getJSONArray("categories").length()
    val nd = raw.getJSONArray("domain_lists").length()
    var paired = 0
    val catIds = (0 until nc).map { raw.getJSONArray("categories").getJSONObject(it).getString("id") }.toSet()
    for (i in 0 until nd) {
        val d = raw.getJSONArray("domain_lists").getJSONObject(i)
        val s = d.optJSONArray("same_as_ip")
        if (s != null && (0 until s.length()).any { s.getString(it) in catIds }) paired++
    }
    check("каталог: служб = подсети + домены − пары same_as_ip", nc + nd - paired, cat.services.size)
    val tg = cat.service("itdoginfo:telegram")
    check("каталог: Telegram — одна служба с двумя половинами", setOf(ListText.Kind.DOMAINS, ListText.Kind.PREFIXES), tg?.parts?.map { it.kind }?.toSet())
    check("каталог: имя службы — по-человечески", "Telegram", tg?.name)
    check("каталог: MyDyson — свой список, обычный файл", "mydyson.lst", cat.service("mydyson")?.parts?.single()?.file)
    check("каталог: у MyDyson описание", true, cat.service("mydyson")?.description?.isNotEmpty())
    check("каталог: base_url взят", raw.getString("base_url").trimEnd('/'), cat.baseUrl)
    val names = cat.services.flatMap { s -> s.parts.map { it.engineName } }
    check("каталог: имена файлов движка уникальны", names.size, names.toSet().size)
    check("каталог: имена файлов движка — до 31 знака и безопасные", true,
        names.all { it.length <= 31 && Regex("^[A-Za-z0-9_][A-Za-z0-9_.-]*$").matches(it) })
    check("каталог: имя половины читаемо", "p-itdoginfo.telegram.lst", tg?.parts?.first { it.kind == ListText.Kind.PREFIXES }?.engineName)
    expectError("каталог: HTML вместо каталога", "network") { Catalog.parse("<html>captive</html>") }
    // Путь издателя с «..» — запись пропускается, каталог не падает.
    val evil = Catalog.parse("""{"base_url":"https://x","domain_lists":[{"id":"a","file":"../../etc/x","kind":"domains"},{"id":"b","file":"ok.lst","kind":"domains"}]}""")
    check("каталог: запись с «..» в пути пропущена", listOf("b"), evil.services.map { it.id })
    check("имена: длинный id — с хешем и в пределе", true,
        Names.flat("d-", "x".repeat(60), ".lst").let { it.length <= 31 && it != Names.flat("d-", "x".repeat(59) + "y", ".lst") })
    return cat
}

fun testSrs() {
    val dir = File(STEER_SRC, "tests/srs")
    for (n in listOf("youtube", "telegram", "discord")) {
        val f = File(dir, "$n.srs")
        val t = tmp("srs-$n")
        val p = ProcessBuilder(STEER, "srs-read", f.path, "--out", "$t/d", "--prefixes-out", "$t/p", "--meta-out", "$t/m")
            .redirectErrorStream(true).start()
        p.inputStream.readBytes(); p.waitFor()
        val r = Srs.read(f.readBytes())
        check("srs $n: домены байт в байт с движком", File(t, "d").readText(), r.domains)
        check("srs $n: подсети байт в байт с движком", File(t, "p").readText(), r.prefixes)
        check("srs $n: сужение как у движка", File(t, "m").readText(), Srs.metaText(r.narrow))
    }
    val d = Srs.read(File(dir, "discord.srs").readBytes())
    check("srs discord: сужение udp", "udp", d.narrow?.proto)
    try { Srs.read("SRS\u0001garbage-garbage".toByteArray()); fail++; println("FAIL srs: мусор принят") } catch (e: SrsError) { pass++ }
    try { Srs.read("<html>".toByteArray() + ByteArray(10)); fail++; println("FAIL srs: html принят") } catch (e: SrsError) { pass++ }
}

val UUID = "11111111-2222-3333-4444-555555555555"
fun subSamples(): List<Pair<String, String>> {
    val links = "vless://$UUID@1.2.3.4:443?security=reality&pbk=K&sni=x.com&type=tcp#One\n" +
        "vless://$UUID@5.6.7.8:443?security=tls&type=grpc&serviceName=s#Two\n" +
        "vless://$UUID@9.9.9.9:443?security=tls#НетИмени\n" +
        "vless://$UUID@h.example:443?security=xtls#X\n" +
        "vless://$UUID@h.example:443?security=none&type=ws#WS\n" +
        "ss://abc@h:1#ss\ntrojan://p@h:443#t\n" +
        "vless://TMG_74317ba5f91@1.2.3.4:443?security=reality&pbk=K&sni=x.com#Короткий\n" +
        "vless://0123456789abcdef0123456789abcde@9.9.9.9:443?security=reality&pbk=K#Щель\n" +
        "vless://$UUID@0.0.0.0:1?security=none#%F0%9F%93%B1%20stub\n" +
        "vless://$UUID@127.0.0.53:443?security=none#loop\n" +
        "vless://$UUID@h.example:443?security=none&type=xhttp&mode=weird#xh\n" +
        "vless://$UUID@h.example:443?security=none&type=xhttp&mode=packet-up#xh2\n" +
        "vless://nohost\n" +
        "vless://a@h1:443#one" + "vless://b@h2:443#two\n" +
        "vless://$UUID@h.example:443?security=reality#nopbk\n"
    val xray = "[{\"outbounds\":[{\"tag\":\"ch01\",\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"179.237.82.105\",\"port\":443," +
        "\"users\":[{\"id\":\"$UUID\"}]}]},\"streamSettings\":{\"network\":\"tcp\",\"security\":\"reality\",\"realitySettings\":{\"serverName\":\"a\",\"publicKey\":\"P\"}}}," +
        "{\"protocol\":\"freedom\"}]},{\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"8.8.4.4\",\"port\":\"2087\"," +
        "\"users\":[{\"id\":\"$UUID\"}]}]},\"streamSettings\":{\"network\":\"ws\",\"security\":\"tls\"}}]}]"
    val single = "{\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"1.2.3.4\",\"port\":443,\"users\":[{\"id\":\"$UUID\"}]}]}," +
        "\"streamSettings\":{\"network\":\"raw\",\"security\":\"reality\",\"realitySettings\":{\"publicKey\":\"P\"}}}]}"
    return listOf(
        "ссылки разных видов" to links,
        "base64 ссылок" to Base64.getEncoder().encodeToString(links.toByteArray()),
        "base64 URL-safe без выравнивания" to Base64.getUrlEncoder().withoutPadding().encodeToString(links.toByteArray()),
        "конфиг Xray массивом" to xray,
        "конфиг Xray одним объектом" to single,
        "пусто" to "\n\n",
    )
}

fun testSubs() {
    for ((name, text) in subSamples()) {
        val f = File(WORK, "sub-sample.txt"); f.writeText(text)
        val p = ProcessBuilder(SUBCOUNT, f.path).start()
        val c = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        p.waitFor()
        val s = SubParse.stats(text)
        check("подписка «$name»: пригодные/пропущенные/чужие как у движка", c, "${s.usable} ${s.skipped} ${s.foreign}")
    }
    val h = mapOf("Profile-Title" to "base64:" + Base64.getEncoder().encodeToString("Моя подписка".toByteArray()),
        "subscription-userinfo" to "upload=100; download=200; total=1000; expire=1900000000")
    check("подписка: название из base64", "Моя подписка", SubHeaders.title(h))
    check("подписка: название из content-disposition", "Riot VPN", SubHeaders.title(mapOf("content-disposition" to "attachment; filename=\"Riot VPN\"")))
    check("подписка: поле остатка", 1000L, SubHeaders.uiField(h["subscription-userinfo"]!!, "total"))
    check("подписка: нечисло в остатке — нет значения", null, SubHeaders.uiField("total=abc", "total"))
    check("подписка: длинное название режется по букве", true, SubHeaders.title(mapOf("profile-title" to "Ж".repeat(40))).let { it.toByteArray().size <= 48 && it.all { c -> c == 'Ж' } })
}

// ---------------------------------------------------------------------------------------

internal fun testSpecs(cat: Catalog, eng: HostEngine) {
    val b = SpecBuilder(eng.listsDir.path)
    // Настоящие файлы списков — чтобы dry-run читал содержимое, а не предупреждал об отсутствии.
    val yt = cat.service("itdoginfo:youtube")!!.parts.single()
    val r = Srs.read(File(STEER_SRC, "tests/srs/youtube.srs").readBytes())
    File(eng.listsDir, yt.engineName).writeText(r.domains)
    val tg = cat.service("itdoginfo:telegram")!!
    val tr = Srs.read(File(STEER_SRC, "tests/srs/telegram.srs").readBytes())
    for (p in tg.parts) File(eng.listsDir, p.engineName).writeText(if (p.kind == ListText.Kind.DOMAINS) tr.domains else tr.prefixes)
    val dc = cat.service("itdoginfo:discord")!!
    val dr = Srs.read(File(STEER_SRC, "tests/srs/discord.srs").readBytes())
    val dcP = dc.parts.first { it.kind == ListText.Kind.PREFIXES }
    File(eng.listsDir, dcP.engineName).writeText(dr.prefixes)
    File(eng.listsDir, "ud-work.lst").writeText("corp.example\n")
    File(eng.listsDir, "up-work.lst").writeText("203.0.113.0/24\n")
    File(eng.listsDir, "sub-s1-00000000.txt").writeText("vless://$UUID@1.2.3.4:443?security=reality&pbk=K&sni=x.com#One\n")
    val src = StubSource(cat, eng.listsDir, mapOf(dcP.engineName to dr.narrow!!), mapOf("s1" to "sub-s1-00000000.txt"))

    fun accepted(name: String, m: Model): BuiltSpec {
        val built = b.build(m, src)
        val (code, err) = eng.dryRun(built.text)
        check("$name: steer apply --dry-run — код 0", 0, code)
        if (code != 0) println("  spec: ${built.text}\n  stderr: $err")
        val cr = eng.check(built.text)
        check("$name: check управляющего сокета — код 0", 0, cr.code)
        return built
    }

    // 1. Весь телефон → туннель по списку.
    var built = accepted("весь телефон", model("""{"outputs":[{"name":"vpn","kind":"interface","devices":["wg0"]}],
        "channels":[{"name":"YouTube","who":{"kind":"phone"},"what":{"lists":["itdoginfo:youtube"]},"out":"vpn"}]}"""))
    var ch = built.spec.getJSONArray("channels").getJSONObject(0)
    check("весь телефон: from self", "[\"self\"]", ch.getJSONArray("from").toString())
    check("весь телефон: путь списка в каталоге движка", "${eng.listsDir}/${yt.engineName}", ch.getJSONObject("match").getJSONArray("domains_files").getString(0))
    check("весь телефон: доменный канал телефона — нужен свой DNS", true, built.needsLocalDns)
    check("весь телефон: файл в перечне заливки", setOf(yt.engineName), built.files)

    // 2. Приложения по UID.
    built = accepted("приложения", model("""{"outputs":[{"name":"vpn","kind":"interface","devices":["wg0"]}],
        "channels":[{"name":"Браузеры","who":{"kind":"apps","uids":[10123,10200]},"what":{"lists":["itdoginfo:telegram"]},"out":"vpn"}]}"""))
    ch = built.spec.getJSONArray("channels").getJSONObject(0)
    check("приложения: from uid:N", "[\"uid:10123\",\"uid:10200\"]", ch.getJSONArray("from").toString())
    check("приложения: обе половины службы", true, ch.getJSONObject("match").has("prefixes_files") && ch.getJSONObject("match").has("domains_files"))

    // 3. Раздача: вся и по адресам; lan_devices — устройства раздачи.
    built = accepted("раздача", model("""{"outputs":[{"name":"vpn","kind":"interface","devices":["wg0"]}],
        "channels":[{"name":"Гости","who":{"kind":"tether","from":["192.168.43.10","192.168.43.0/28"]},"what":{"lists":["itdoginfo:telegram"]},"out":"vpn"},
                    {"name":"Все клиенты","who":{"kind":"tether"},"what":{"lists":["itdoginfo:youtube"]},"out":"direct"}]}"""))
    val chs = built.spec.getJSONArray("channels")
    check("раздача: адреса в from", "[\"192.168.43.10\",\"192.168.43.0/28\"]", chs.getJSONObject(0).getJSONArray("from").toString())
    check("раздача: вся — без from", false, chs.getJSONObject(1).has("from"))
    check("раздача: lan_devices по умолчанию", JSONArray(Model.DEFAULT_TETHER).toString(), built.spec.getJSONArray("lan_devices").toString())
    check("раздача: доменный канал раздачи не требует своего DNS телефона", false, built.needsLocalDns)

    // 4. Несколько выходов, порядок правил, «весь трафик», свои списки, выключенное правило.
    built = accepted("несколько выходов", model("""{
        "subs":[{"id":"s1","name":"x","kind":"links"}],
        "custom":[{"name":"work","domains":["corp.example"],"prefixes":["203.0.113.0/24"]}],
        "outputs":[{"name":"vpn","kind":"interface","devices":["wg0","wg1"],"on_fail":"direct"},
                   {"name":"nl","kind":"vless","sub":"s1","nodes":[0]}],
        "channels":[
          {"name":"Работа","who":{"kind":"phone"},"what":{"custom":["work"]},"out":"vpn"},
          {"name":"Telegram","who":{"kind":"apps","uids":[10300]},"what":{"lists":["itdoginfo:telegram"]},"out":"nl"},
          {"name":"Выкл","enabled":false,"who":{"kind":"phone"},"what":{"lists":["itdoginfo:youtube"]},"out":"nl"},
          {"name":"Остальное","who":{"kind":"phone"},"what":{"all":true},"out":"vpn"}]}"""))
    check("несколько выходов: порядок каналов = порядок правил", listOf("Работа", "Telegram", "Выкл", "Остальное"), channelNames(built.spec))
    val outs = built.spec.getJSONObject("outputs")
    check("несколько выходов: direct есть всегда", "direct", outs.getJSONObject("direct").getString("kind"))
    check("несколько выходов: vless со своим файлом подписки", "${eng.listsDir}/sub-s1-00000000.txt", outs.getJSONObject("nl").getString("sub_file"))
    check("несколько выходов: устройства по предпочтению", "[\"wg0\",\"wg1\"]", outs.getJSONObject("vpn").getJSONArray("devices").toString())
    val all = built.spec.getJSONArray("channels").getJSONObject(3).getJSONObject("match")
    check("весь трафик: any вместе с allow_all", true, all.optBoolean("any") && all.optBoolean("allow_all"))
    check("выключенное правило: enabled=false", false, built.spec.getJSONArray("channels").getJSONObject(2).getBoolean("enabled"))
    check("свои списки: обе половины", true, built.files.containsAll(listOf("ud-work.lst", "up-work.lst")))

    // 5. Сужение портами: Discord — только подсети с udp и портами; правило несёт сужение само.
    built = accepted("сужение портами", model("""{"outputs":[{"name":"vpn","kind":"interface","devices":["wg0"]}],
        "channels":[{"name":"Discord","who":{"kind":"phone"},"what":{"lists":["itdoginfo:discord"]},"out":"vpn"},
                    {"name":"Discord и работа","who":{"kind":"phone"},"what":{"lists":["itdoginfo:discord","itdoginfo:youtube"]},"out":"vpn"}]}"""))
    check("сужение: схема 2", 2, built.spec.getInt("schema"))
    check("сужение: спутник сразу за своим правилом", listOf("Discord", "Discord и работа", "Discord и раб (порты)"), channelNames(built.spec))
    val sat = built.spec.getJSONArray("channels").getJSONObject(2)
    check("сужение: у спутника proto udp", "udp", sat.getJSONObject("match").getString("proto"))
    check("сужение: у родителя подсетей Discord нет", false, built.spec.getJSONArray("channels").getJSONObject(1).getJSONObject("match").has("prefixes_files"))
    check("сужение: у правила из одних суженных подсетей порты — у него самого", "udp", built.spec.getJSONArray("channels").getJSONObject(0).getJSONObject("match").optString("proto"))

    // 6. Длинное русское имя — до 31 байта, спека принимается.
    built = accepted("длинное имя", model("""{"outputs":[{"name":"vpn","kind":"interface","devices":["wg0"]}],
        "channels":[{"name":"Очень длинное название правила для телефона","who":{"kind":"phone"},"what":{"lists":["itdoginfo:discord"]},"out":"vpn"},
                    {"name":"Очень длинное название правила для планшета","who":{"kind":"phone"},"what":{"lists":["itdoginfo:youtube"]},"out":"vpn"}]}"""))
    val nms = channelNames(built.spec)
    check("длинное имя: не длиннее 31 байта", true, nms.all { it.toByteArray().size <= 31 })
    check("длинное имя: имена уникальны", nms.size, nms.toSet().size)

    // 7. Пустое правило пропускается с предупреждением, остальные собираются.
    built = accepted("пустое правило", model("""{"outputs":[],
        "channels":[{"name":"Черновик","who":{"kind":"phone"},"what":{},"out":"direct"},
                    {"name":"YT","who":{"kind":"phone"},"what":{"lists":["itdoginfo:youtube"]},"out":"direct"}]}"""))
    check("пустое правило: пропущено", listOf("YT"), channelNames(built.spec))
    check("пустое правило: предупреждение названо", true, built.warnings.any { it.contains("Черновик") })

    // 8. Мост Telegram — только раздача.
    built = accepted("мост Telegram для раздачи", model("""{"outputs":[{"name":"tg","kind":"tgws","domain":"example.com"}],
        "channels":[{"name":"TG","who":{"kind":"tether"},"what":{"lists":["itdoginfo:telegram"]},"out":"tg"}]}"""))

    // Проверки модели — отказы словами человека.
    expectError("модель: мост Telegram для телефона", "bad-args", "раздачи") {
        model("""{"outputs":[{"name":"tg","kind":"tgws","domain":"example.com"}],"channels":[{"name":"x","who":{"kind":"phone"},"what":{"all":true},"out":"tg"}]}""")
    }
    expectError("модель: uid 0", "bad-args") { model("""{"channels":[{"name":"x","who":{"kind":"apps","uids":[0]},"what":{"all":true},"out":"direct"}]}""") }
    expectError("модель: имя выхода с пробелом", "bad-args", "латиница") { model("""{"outputs":[{"name":"my vpn","kind":"interface","devices":["wg0"]}]}""") }
    expectError("модель: zapret при отказе", "bad-args") { model("""{"outputs":[{"name":"v","kind":"interface","devices":["wg0"],"on_fail":"zapret"}]}""") }
    expectError("модель: выход на несуществующую подписку", "bad-args", "подписки") { model("""{"outputs":[{"name":"v","kind":"vless","sub":"zz"}]}""") }
    expectError("модель: правило на несуществующий выход", "bad-args") { model("""{"channels":[{"name":"x","what":{"all":true},"out":"nope"}]}""") }
    expectError("модель: весь трафик вместе со списками", "bad-args") { model("""{"channels":[{"name":"x","what":{"all":true,"lists":["a"]},"out":"direct"}]}""") }
    expectError("модель: смесь MAC и адресов", "bad-args", "MAC") {
        model("""{"channels":[{"name":"x","who":{"kind":"tether","from":["aa:bb:cc:dd:ee:ff","1.2.3.4"]},"what":{"all":true},"out":"direct"}]}""")
    }
    expectError("модель: версия новее", "bad-args", "обновите") { model("""{"version":99}""") }
    expectError("модель: два vless с общим началом имени", "bad-args", "15") {
        model("""{"subs":[{"id":"s1","kind":"links"}],"outputs":[{"name":"netherlands_one","kind":"vless","sub":"s1"},{"name":"netherlands_one2","kind":"vless","sub":"s1"}]}""")
    }
    val rt = model(model("""{"subs":[{"id":"s1","name":"n","kind":"links"}],"custom":[{"name":"w","domains":["A.example."],"prefixes":["1.2.3.4"]}],
        "outputs":[{"name":"nl","kind":"vless","sub":"s1","nodes":[3,1]}],"channels":[{"name":"x","who":{"kind":"apps","uids":[10001]},"what":{"custom":["w"]},"out":"nl"}]}""").toJson().toString())
    check("модель: круг JSON сохраняет всё", "[3,1]", JSONArray(rt.outputs[0].nodes).toString())
    check("модель: свой список прошёл санитайзер", listOf("a.example") to listOf("1.2.3.4/32"), rt.custom[0].domains to rt.custom[0].prefixes)
}

// ---------------------------------------------------------------------------------------

fun setupHttp(http: FakeHttp) {
    http.put("https://github.com/xyzmean/splify2-lists/releases/latest/download/lists.json", LISTS_JSON.readBytes())
    val base = "https://github.com/itdoginfo/allow-domains/releases/download/2026-09-21_15-16/"
    for (n in listOf("youtube", "telegram", "discord")) http.put("$base$n.srs", File(STEER_SRC, "tests/srs/$n.srs").readBytes())
    val cat = JSONObject(LISTS_JSON.readText())
    http.put(cat.getString("base_url").trimEnd('/') + "/mydyson.lst", File(LISTS_JSON.parentFile, "lists/mydyson.lst").readBytes())
}

fun testDispatcher() {
    val eng = HostEngine(STEER, tmp("disp-engine"))
    eng.start()
    try {
        val http = FakeHttp()
        setupHttp(http)
        val files = tmp("disp-files")
        val events = ArrayList<Pair<String, String>>()
        val d = Dispatcher(files, eng, http, DeviceInfo("Android", "16", "POCOPHONE F1"), eng.listsDir.path)
        d.onEvent = { n, p -> synchronized(events) { events.add(n to p) } }
        fun call(m: String, a: String = "{}") = d.call(m, a)

        check("settings.get: пустая модель", 0, JSONObject(call("settings.get")).getJSONArray("channels").length())
        val cat = JSONObject(call("lists.catalog"))
        val items = cat.getJSONArray("items")
        check("lists.catalog: все службы каталога", 46, items.length())
        val tgItem = (0 until items.length()).map { items.getJSONObject(it) }.first { it.getString("id") == "itdoginfo:telegram" }
        check("lists.catalog: у службы оба вида", "[\"prefixes\",\"domains\"]", tgItem.getJSONArray("kinds").toString())
        check("lists.catalog: не выбрана по умолчанию", false, tgItem.getBoolean("selected"))
        check("lists.select", true, JSONObject(call("lists.select", """{"id":"itdoginfo:youtube","on":true}""")).getBoolean("saved"))
        check("lists.select: в модели", "[\"itdoginfo:youtube\"]", JSONObject(call("settings.get")).getJSONArray("lists").toString())

        // Подписка: панель отдаёт заглушку без /json и узлы по /json, с остатком и названием.
        val stub = "vless://00000000-0000-0000-0000-000000000000@0.0.0.0:1?security=none#Wrong client\n"
        http.put("https://panel.example/sub/abc", stub)
        http.put("https://panel.example/sub/abc/json", "{\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"1.2.3.4\",\"port\":443," +
            "\"users\":[{\"id\":\"$UUID\"}]}]},\"streamSettings\":{\"network\":\"tcp\",\"security\":\"reality\",\"realitySettings\":{\"publicKey\":\"P\",\"serverName\":\"a.example\"}}}]}",
            mapOf("profile-title" to "base64:" + Base64.getEncoder().encodeToString("Панель".toByteArray()),
                "subscription-userinfo" to "upload=1; download=2; total=100; expire=1900000000"))
        val sl = JSONArray(call("subs.add", """{"url":"https://panel.example/sub/abc"}"""))
        val s1 = sl.getJSONObject(0)
        check("subs.add: узлы по /json", 1, s1.getInt("nodes"))
        check("subs.add: ссылка — та, по которой пришли узлы", "https://panel.example/sub/abc/json", s1.getString("url"))
        check("subs.add: название от панели", "Панель", s1.getString("name"))
        check("subs.add: остаток трафика", "100", s1.getJSONObject("quota").getString("total"))
        check("subs.add: панели ушёл идентификатор устройства", true, http.seen.any { it.first.startsWith("https://panel.example") && it.second["x-hwid"]?.length == 20 })
        check("subs.add: и модель телефона", "POCOPHONE F1", http.seen.last { it.first.startsWith("https://panel.example") }.second["x-device-model"])
        val sl2 = JSONArray(call("subs.add", """{"url":"vless://$UUID@5.6.7.8:443?security=reality&pbk=K&sni=x.com#A vless://$UUID@9.9.9.9:443?security=none#B"}"""))
        check("subs.add: вставленные ссылки", 2, sl2.getJSONObject(1).getInt("nodes"))
        expectError("subs.add: не ссылка", "bad-args") { call("subs.add", """{"url":"hello"}""") }

        // Модель целиком: выходы, правила — и применение.
        val put = """{"custom":[{"name":"work","domains":["corp.example"],"prefixes":["203.0.113.0/24"]}],
          "subs":[{"id":"s1","name":"Моя"},{"id":"s2","name":"Свои"}],
          "outputs":[{"name":"vpn","kind":"interface","devices":["wg0"],"on_fail":"drop"},{"name":"nl","kind":"vless","sub":"s1"}],
          "channels":[{"name":"YouTube","who":{"kind":"phone"},"what":{"lists":["itdoginfo:youtube"]},"out":"nl"},
                      {"name":"Telegram","who":{"kind":"apps","uids":[10123]},"what":{"lists":["itdoginfo:telegram","itdoginfo:discord"]},"out":"vpn"},
                      {"name":"Работа","who":{"kind":"tether"},"what":{"custom":["work"]},"out":"vpn"},
                      {"name":"MyDyson","who":{"kind":"phone"},"what":{"lists":["mydyson"]},"out":"vpn"}],
          "lists":["itdoginfo:youtube"]}"""
        check("settings.put", true, JSONObject(call("settings.put", put)).getBoolean("saved"))
        val m = JSONObject(call("settings.get"))
        check("settings.put: подписки не теряются, название обновлено", "Моя", m.getJSONArray("subs").getJSONObject(0).getString("name"))
        expectError("settings.put: негодная модель", "bad-args") { call("settings.put", """{"outputs":[{"name":"","kind":"interface"}]}""") }
        check("settings.get: обновление по умолчанию — только без лимитной сети", true, m.getJSONObject("update").getBoolean("unmetered_only"))
        call("settings.put", """{"update":{"unmetered_only":false}}""")
        check("settings.put частью: настройка обновления", false, d.updateUnmeteredOnly())
        val mp = JSONObject(call("settings.get"))
        check("settings.put частью: правила и списки на месте", 4 to "work", mp.getJSONArray("channels").length() to mp.getJSONArray("custom").getJSONObject(0).getString("name"))
        call("settings.put", """{"update":{"unmetered_only":true}}""")
        expectError("settings.put: настройка обновления не да/нет", "bad-args") { call("settings.put", """{"update":{"unmetered_only":"да"}}""") }

        val pv = JSONObject(call("spec.preview"))
        check("spec.preview: check движка — код 0 (списки ещё не скачаны)", 0, pv.getJSONObject("check").getInt("code"))
        check("spec.preview: предупреждение о нескачанных списках", true, pv.getJSONArray("warnings").length() > 0)

        // put-file у движка ещё нет: понятный отказ, а не падение.
        eng.putFileSupported = false
        expectError("spec.apply без put-file у движка", "engine", "Обновите систему") { call("spec.apply") }
        check("spec.apply без put-file: итога применения нет", null, d.lastApply)

        eng.putFileSupported = true
        val ap = JSONObject(call("spec.apply"))
        check("spec.apply: сохранено движком", true, ap.getBoolean("saved"))
        check("spec.apply: движок выключен — правила не поставлены", false, ap.getBoolean("applied"))
        check("spec.apply: сказано, когда заработают", true, ap.optString("message").contains("когда движок включат"))
        check("spec.apply: needsLocalDns — есть доменные каналы телефона", true, d.lastApply?.needsLocalDns)
        val saved = JSONObject(File(eng.work, "spec.json").readText())
        check("spec.apply: у движка — собранная спека, порядок правил сохранён",
            listOf("YouTube", "Telegram", "Telegram (порты)", "Работа", "MyDyson"), channelNames(saved))
        check("spec.apply: списки докачаны и залиты", true, File(eng.listsDir, "d-itdoginfo.youtube.lst").readText().contains("youtube.com"))
        check("spec.apply: подписка залита под именем с хешем", true, eng.listsDir.list()!!.any { it.startsWith("sub-s1-") })
        check("spec.apply: свой список каталога (обычный файл) у движка", true, File(eng.listsDir, "d-mydyson.lst").readLines().contains("dyson.com"))
        check("spec.apply: каждая спека прошла и сокет, и dry-run одинаково", 0, eng.dryRunMismatch)
        check("spec.apply: отказа ни на одной спеке", true, eng.specs.all { eng.dryRun(it).first == 0 })

        // Ответ list-files: форма ctl.md и терпимые запасные.
        check("list-files: разбор ответа ctl.md", listOf("a.lst", "b.lst"),
            Dispatcher.fileNames(CtlReply(0, "", "", null, """{"v":1,"cmd":"list-files","code":0,"files":[{"name":"a.lst","size":1},{"name":"b.lst"}]}""")))
        check("list-files: разбор строк вывода", listOf("a.lst", "sub-s1-0011aabb.txt"),
            Dispatcher.fileNames(CtlReply(0, "a.lst\nsub-s1-0011aabb.txt\n", "", null, """{"v":1,"code":0}""")))
        check("list-files: пустой каталог", emptyList<String>(), Dispatcher.fileNames(CtlReply(0, "", "", null, """{"v":1,"cmd":"list-files","code":0,"files":[]}""")))

        // Доменные правила только у раздачи, у приложения — одни подсети: Private DNS не нужен.
        call("settings.put", put.replace("\"who\":{\"kind\":\"phone\"},\"what\":{\"lists\":[\"itdoginfo:youtube\"]}", "\"who\":{\"kind\":\"tether\"},\"what\":{\"lists\":[\"itdoginfo:youtube\"]}")
            .replace("{\"name\":\"MyDyson\",\"who\":{\"kind\":\"phone\"}", "{\"name\":\"MyDyson\",\"who\":{\"kind\":\"tether\"}")
            .replace("\"who\":{\"kind\":\"apps\",\"uids\":[10123]},\"what\":{\"lists\":[\"itdoginfo:telegram\",\"itdoginfo:discord\"]}",
                     "\"who\":{\"kind\":\"apps\",\"uids\":[10123]},\"what\":{\"custom\":[\"nets\"]}")
            .replace("\"custom\":[{\"name\":\"work\"", "\"custom\":[{\"name\":\"nets\",\"prefixes\":[\"198.51.100.0/24\"]},{\"name\":\"work\""))
        call("spec.apply")
        check("spec.apply: без доменных каналов телефона Private DNS не нужен", false, d.lastApply?.needsLocalDns)
        check("уборка: списки ушедшего из правил Telegram убраны у движка", false, File(eng.listsDir, "d-itdoginfo.telegram.lst").exists())
        check("уборка: используемые списки на месте", true, File(eng.listsDir, "d-itdoginfo.youtube.lst").exists())
        check("уборка: новый свой список у движка", true, File(eng.listsDir, "up-nets.lst").exists())

        // Движок с put-file, но без list-files/rm-file: применение не страдает, файлы остаются.
        eng.filesListSupported = false
        call("settings.put", put)
        check("движок без list-files: применение проходит", true, JSONObject(call("spec.apply")).getBoolean("saved"))
        eng.filesListSupported = true

        // Отказ движка словами человека: выход без устройства не пройдёт модель, поэтому ломаем
        // спеку тем, что знает только движок, — 65 каналов.
        val many = JSONArray()
        for (i in 0 until 65) many.put(JSONObject("""{"name":"r$i","who":{"kind":"phone"},"what":{"all":true},"out":"direct"}"""))
        call("settings.put", JSONObject(put).put("channels", many).toString())
        expectError("spec.apply: правил больше, чем держит движок", "bad-args", "не больше 64") { call("spec.apply") }
        call("settings.put", put)

        // Свои списки.
        val cu = JSONObject(call("lists.custom", """{"put":{"name":"home","text":"example.org\n*.Media.example\n10.0.0.1\n300.1.1.1\nbad line"}}"""))
        check("lists.custom: домены", 2, cu.getInt("domains"))
        check("lists.custom: подсети", 1, cu.getInt("prefixes"))
        check("lists.custom: отброшенные посчитаны", 2, cu.getInt("dropped"))
        check("lists.custom: список", 2, JSONArray(call("lists.custom")).length())
        expectError("lists.custom: удалить используемый", "bad-args", "Работа") { call("lists.custom", """{"remove":"work"}""") }
        check("lists.custom: удалить свободный", true, JSONObject(call("lists.custom", """{"remove":"home"}""")).getBoolean("saved"))

        // Как экран: черновик — снимок settings.get; пока он правится, свой список создаётся из
        // редактора правила (сохраняется сразу); в settings.put уходят только выходы и правила.
        val snap = JSONObject(call("settings.get"))
        call("lists.custom", """{"put":{"name":"fromeditor","text":"example.net\n192.0.2.0/24"}}""")
        val draftCh = JSONArray().put(JSONObject("""{"name":"Новое правило","enabled":true,"who":{"kind":"apps","uids":[10150,10151]},
            "what":{"lists":["itdoginfo:telegram"],"custom":["fromeditor"],"all":false},"out":"nl"}"""))
        val sc = snap.getJSONArray("channels")
        for (i in 0 until sc.length()) draftCh.put(sc.getJSONObject(i))
        draftCh.getJSONObject(2).put("enabled", false)
        call("settings.put", JSONObject().put("outputs", snap.getJSONArray("outputs")).put("channels", draftCh).toString())
        check("как экран: свой список из редактора не затёрт снимком", true, JSONArray(call("lists.custom")).toString().contains("fromeditor"))
        val sa = JSONObject(call("spec.apply"))
        check("как экран: применено", true, sa.getBoolean("saved"))
        val ss = JSONObject(File(eng.work, "spec.json").readText())
        check("как экран: новое правило — первым у движка", "Новое правило", channelNames(ss)[0])
        check("как экран: приложения — from uid", "[\"uid:10150\",\"uid:10151\"]", ss.getJSONArray("channels").getJSONObject(0).getJSONArray("from").toString())
        check("как экран: выключенное правило выключено у движка", false,
            (0 until ss.getJSONArray("channels").length()).map { ss.getJSONArray("channels").getJSONObject(it) }.first { it.getString("name") == "Telegram" }.optBoolean("enabled", true))
        call("settings.put", put)

        // Подписки: удалить занятую нельзя, обновить — можно; новое содержимое — новое имя файла.
        expectError("subs.remove: занятая выходом", "bad-args", "nl") { call("subs.remove", """{"id":"s1"}""") }
        call("spec.apply")
        val before = eng.listsDir.list()!!.filter { it.startsWith("sub-s1-") }.toSet()
        http.put("https://panel.example/sub/abc/json", "vless://$UUID@1.2.3.4:443?security=reality&pbk=K&sni=x.com#One\nvless://$UUID@1.2.3.5:443?security=reality&pbk=K&sni=x.com#Two\n")
        val rf = JSONArray(call("subs.refresh", """{"id":"s1"}"""))
        check("subs.refresh: узлов стало два", 2, rf.getJSONObject(0).getInt("nodes"))
        val after = eng.listsDir.list()!!.filter { it.startsWith("sub-s1-") }.toSet()
        check("subs.refresh: применённый выход получил новый файл подписки", true, (after - before).size == 1)
        check("subs.refresh: прежний файл подписки убран у движка", true, before.isNotEmpty() && before.none { it in after })
        check("subs.refresh: и спека у движка ссылается на него", true,
            JSONObject(File(eng.work, "spec.json").readText()).getJSONObject("outputs").getJSONObject("nl").getString("sub_file").endsWith((after - before).first()))
        expectError("subs.refresh: вставленные ссылки обновить нечем", "bad-args") { call("subs.refresh", """{"id":"s2"}""") }

        // Обновление списков в фоне: событие по окончании.
        check("lists.update: запущено", true, JSONObject(call("lists.update", """{"force":true}""")).getBoolean("started"))
        var waited = 0
        while (synchronized(events) { events.none { it.first == "lists.updated" } } && waited < 200) { Thread.sleep(50); waited++ }
        val ev = synchronized(events) { events.firstOrNull { it.first == "lists.updated" } }
        check("lists.update: событие lists.updated", true, ev != null)
        if (ev != null) {
            val p = JSONObject(ev.second)
            check("lists.update: в событии ok и changed", true, p.has("ok") && p.has("changed"))
        }

        // Резервная копия: выгрузить, испортить модель, вернуть.
        val ex = JSONObject(call("backup.export"))
        val bj = File(ex.getString("file")).readText()
        check("backup.export: файл с моделью и подписками", true, JSONObject(bj).getJSONObject("subs").has("s1"))
        call("settings.put", """{"outputs":[],"channels":[]}""")
        check("backup.import", true, JSONObject(d.call("backup.import", JSONObject().put("json", bj).toString())).getBoolean("saved"))
        check("backup.import: правила вернулись", 4, JSONObject(call("settings.get")).getJSONArray("channels").length())
        expectError("backup.import: чужой файл", "bad-args", "не файл настроек") { d.call("backup.import", """{"json":"{\"a\":1}"}""") }

        expectError("неизвестный метод", "unknown-method") { call("engine.status") }
        expectError("негодный JSON аргументов", "bad-args") { call("settings.put", "{") }

        // Сети нет: каталог с диска, подписка — понятный отказ.
        http.down = true
        check("без сети: каталог с диска", 46, JSONObject(call("lists.catalog")).getJSONArray("items").length())
        expectError("без сети: подписка", "network", "подключение") { call("subs.add", """{"url":"https://panel.example/other"}""") }
        http.down = false
        check("методы моста: каждая спека, ушедшая движку, принята dry-run", true, eng.specs.all { eng.dryRun(it).first == 0 })
        check("методы моста: сокет и dry-run ни разу не разошлись", 0, eng.dryRunMismatch)
    } finally {
        eng.stop()
    }
}

/** Обновление списков: просадка и HTML вместо списка — остаётся прежнее; изменение —
 *  переприменение с новыми файлами. Свой маленький каталог, чтобы управлять содержимым. */
fun testUpdate() {
    val eng = HostEngine(STEER, tmp("upd-engine"))
    eng.start()
    try {
        val http = FakeHttp()
        val catUrl = "https://lists.example/lists.json"
        http.put(catUrl, """{"version":"1","base_url":"https://lists.example/l","domain_lists":[{"id":"big","kind":"domains","name_ru":"Большой","file":"big.lst"},{"id":"mixed","kind":"domains","name_ru":"Смесь","file":"mixed.lst"}],
            "categories":[{"id":"nets","name_ru":"Сети","file":"nets.lst"}]}""")
        val big = (1..200).joinToString("\n", postfix = "\n") { "host$it.example" }
        http.put("https://lists.example/l/big.lst", big)
        http.put("https://lists.example/l/nets.lst", "203.0.113.0/24\n198.51.100.0/24\n")
        http.put("https://lists.example/l/mixed.lst", "# name: Смесь\noffice.example\n*.corp.example\n192.0.2.0/24\n192.0.2.77\n")
        val files = tmp("upd-files")
        // Каталог списков — по умолчанию телефона, а у движка стенда он свой: логика обязана
        // взять путь из ответа put-file (ctl.md) и собрать спеку с ним.
        val d = Dispatcher(files, eng, http, DeviceInfo())
        d.call("settings.put", """{"catalog_url":"$catUrl","outputs":[{"name":"vpn","kind":"interface","devices":["wg0"]}],
            "channels":[{"name":"B","who":{"kind":"phone"},"what":{"lists":["big","nets","mixed"]},"out":"vpn"}]}""")
        val ap = JSONObject(d.call("spec.apply", "{}"))
        check("свой каталог: применено", true, ap.getBoolean("saved"))
        check("каталог движка из ответа put-file — в путях спеки", true,
            File(eng.work, "spec.json").readText().contains("\"${eng.listsDir.path}/d-big.lst\""))
        check("свой каталог: список у движка", 200, File(eng.listsDir, "d-big.lst").readLines().size)
        check("смешанный список: домены — в доменный файл", "office.example\n*.corp.example\n", File(eng.listsDir, "d-mixed.lst").readText())
        check("смешанный список: адреса — в адресный", "192.0.2.0/24\n192.0.2.77\n", File(eng.listsDir, "m-mixed.lst").readText())
        check("смешанный список: адресная половина в спеке", true,
            JSONObject(File(eng.work, "spec.json").readText()).getJSONArray("channels").getJSONObject(0).getJSONObject("match").getJSONArray("prefixes_files").toString().contains("m-mixed.lst"))

        http.put("https://lists.example/l/big.lst", "host1.example\nhost2.example\n")
        var rep = d.updateListsNow(false)
        check("просадка: прежний список остался", 200, File(eng.listsDir, "d-big.lst").readLines().size)
        check("просадка: отчёт называет числа", true, rep.optString("message").contains("2 против 200"))
        check("просадка: ok=false", false, rep.getBoolean("ok"))

        http.put("https://lists.example/l/big.lst", "<html><body>Доступ ограничен</body></html>\n")
        rep = d.updateListsNow(false)
        check("HTML вместо списка: прежний остался", 200, File(eng.listsDir, "d-big.lst").readLines().size)
        check("HTML вместо списка: отчёт", true, rep.optString("message").contains("не похожи"))

        val specsBefore = eng.specs.size
        http.put("https://lists.example/l/big.lst", big + "extra.example\n")
        rep = d.updateListsNow(false)
        check("изменение: список обновлён у движка", 201, File(eng.listsDir, "d-big.lst").readLines().size)
        check("изменение: переприменено", true, eng.specs.size > specsBefore)
        check("изменение: отчёт", 1, rep.getInt("changed"))

        rep = d.updateListsNow(false)
        check("без изменений: ничего не применяется", 0, rep.getInt("changed"))
        check("обновление: каждая спека принята dry-run", true, eng.specs.all { eng.dryRun(it).first == 0 })
    } finally {
        eng.stop()
    }
}

fun main(args: Array<String>) {
    STEER = System.getenv("STEER") ?: error("STEER")
    STEER_SRC = File(System.getenv("STEER_SRC") ?: error("STEER_SRC"))
    LISTS_JSON = File(System.getenv("LISTS_JSON") ?: error("LISTS_JSON"))
    SUBCOUNT = System.getenv("SUBCOUNT") ?: error("SUBCOUNT")
    WORK = File(System.getenv("WORK") ?: error("WORK")).also { it.mkdirs() }

    val cat = testCatalog()
    testSrs()
    testSubs()
    val eng = HostEngine(STEER, tmp("spec-engine"))
    eng.start()
    try { testSpecs(cat, eng) } finally { eng.stop() }
    testDispatcher()
    testUpdate()

    println("logic: $pass passed, $fail failed")
    System.exit(if (fail == 0) 0 else 1)
}
