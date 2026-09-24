/*
 * Стенд выхода WireGuard/AmneziaWG (kind: awg) и вложенных выходов (via).
 *
 * Разбор файла сверяется с движком ВЕРДИКТ В ВЕРДИКТ: каждый образец — годный и негодный —
 * кладётся в каталог стенда, и `steer apply --dry-run` спеки с этим файлом говорит, принял ли
 * его разбор движка (awg_check_all печатает «awg: выход …» на негодный). Логика обязана
 * отказать ровно там, где откажет движок: откажет строже — человек не добавит файл, который
 * работает; мягче — добавит тот, что не встанет.
 *
 * via проверяется в два слоя. Модель (круг, глубина, допустимые виды) — всегда: это слова
 * человека, и движок для них не нужен. Спеки с via — против движка, только если он объявляет
 * умение `via` (features в status). Движок стенда (STEER_DIR) без via, а второй (STEER_VIA,
 * собран logic-test.sh из STEER_VIA_DIR) есть — via-спеки идут через него, и те из них, где
 * есть kind awg, — только если он умеет и awg. Чего проверить нечем, попадает в `pending` и
 * печатается отдельно: зелёный итог не должен выдавать непроверенное за проверенное.
 */
import com.der.splify2.logic.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

val pending = ArrayList<String>()

fun key(seed: Int): String = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 7 + seed * 31 + 1).toByte() })

val K_PRIV = key(1)
val K_PUB = key(2)
val K_PSK = key(3)

val AWG_FULL = """
    [Interface]
    PrivateKey = $K_PRIV
    Address = 10.8.0.2/32, fd00:8::2/128
    DNS = 1.1.1.1, 1.0.0.1
    MTU = 1280
    Jc = 4
    Jmin = 40
    Jmax = 70
    S1 = 52
    S2 = 18
    S3 = 20
    S4 = 8
    H1 = 1020325451-1020325460
    H2 = 3288052141
    H3 = 1766607858
    H4 = 2114175328
    I1 = <b 0xc6000000010843290a47ba8ba2ed000044d0e3efd9326adb60561baa3bc4b52471b2d459><r 16>

    [Peer]
    PublicKey = $K_PUB
    PresharedKey = $K_PSK
    Endpoint = vpn.example.net:51820
    AllowedIPs = 0.0.0.0/0, ::/0
    PersistentKeepalive = 25
""".trimIndent() + "\n"

/** Обычный WireGuard, как его сохраняет блокнот Windows: BOM, \r\n, комментарии. */
val WG_PLAIN_RAW = "﻿# Мой сервер\r\n[Interface]\r\nPrivateKey = ${key(4)}\r\nAddress = 10.66.66.2/32\r\nListenPort = 51820 # порт\r\n\r\n" +
    "[Peer]\r\nPublicKey = ${key(5)}\r\nEndpoint = 203.0.113.9:51820\r\nAllowedIPs = 0.0.0.0/0\r\n"

val WG_V6 = """
    [Interface]
    PrivateKey = ${key(6)}
    Address = fd42:42:42::2/64
    [Peer]
    PublicKey = ${key(7)}
    Endpoint = [2001:db8::1]:51820
    AllowedIPs = ::/0
""".trimIndent() + "\n"

/** Файл Amnezia с выключенной обфускацией: всё задано, но значения — обычный WireGuard. */
val AWG_OFF = """
    [Interface]
    PrivateKey = ${key(8)}
    Address = 10.9.0.2/32
    Jc = 0
    Jmin = 0
    Jmax = 0
    S1 = 0
    S2 = 0
    H1 = 1
    H2 = 2
    H3 = 3
    H4 = 4
    [Peer]
    PublicKey = ${key(9)}
    Endpoint = 198.51.100.7:443
    AllowedIPs = 0.0.0.0/0
""".trimIndent() + "\n"

val WG_TWO_PEERS = """
    [interface]
    privatekey = ${key(10)}
    address = 10.1.0.2/24
    [Peer]
    PublicKey = ${key(11)}
    Endpoint = a.example:1
    AllowedIPs = 10.1.0.0/24
    [Peer]
    PublicKey = ${key(12)}
    AllowedIPs = 10.2.0.0/24
    PersistentKeepalive = off
""".trimIndent() + "\n"

fun wg(iface: String, peer: String = "PublicKey = ${key(21)}\nEndpoint = 192.0.2.1:51820\nAllowedIPs = 0.0.0.0/0") =
    "[Interface]\nPrivateKey = ${key(20)}\nAddress = 10.0.0.2/32\n$iface\n[Peer]\n$peer\n"

/** Негодные файлы: текст → что обязано быть в отказе логики. */
fun badSamples(): List<Triple<String, String, String>> {
    val tailBits = K_PRIV.substring(0, 42) + (if (K_PRIV[42] == 'B') 'C' else 'B') + "="
    return listOf(
        Triple("незнакомый параметр в [Interface]", wg("Foo = 1"), "неизвестный параметр Foo"),
        Triple("незнакомый параметр в [Peer]", wg("", "PublicKey = ${key(21)}\nBar = 2"), "неизвестный параметр Bar в [Peer]"),
        Triple("PrivateKey не base64", "[Interface]\nPrivateKey = abc\n[Peer]\nPublicKey = ${key(21)}\n", "PrivateKey"),
        Triple("PrivateKey с лишними битами хвоста", "[Interface]\nPrivateKey = $tailBits\n[Peer]\nPublicKey = ${key(21)}\n", "PrivateKey"),
        Triple("нет PrivateKey", "[Interface]\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = ${key(21)}\n", "нет PrivateKey"),
        Triple("нет [Peer]", "[Interface]\nPrivateKey = ${key(20)}\n", "нет ни одного [Peer]"),
        Triple("IPv6 Endpoint без скобок", wg("", "PublicKey = ${key(21)}\nEndpoint = 2001:db8::1:51820"), "Endpoint"),
        Triple("Endpoint без порта", wg("", "PublicKey = ${key(21)}\nEndpoint = vpn.example.net"), "Endpoint"),
        Triple("Endpoint с портом 0", wg("", "PublicKey = ${key(21)}\nEndpoint = vpn.example.net:0"), "Endpoint"),
        Triple("Jmin больше Jmax", wg("Jmin = 80\nJmax = 40"), "Jmin больше Jmax"),
        Triple("H1 и H2 пересекаются", wg("H1 = 5-10\nH2 = 8"), "пересекаются"),
        Triple("MTU меньше 1280", wg("MTU = 1000"), "MTU"),
        Triple("S1 больше 65535", wg("S1 = 70000"), "S1"),
        Triple("повтор PublicKey", "[Interface]\nPrivateKey = ${key(20)}\n[Peer]\nPublicKey = ${key(21)}\n[Peer]\nPublicKey = ${key(21)}\n", "повторяет"),
        Triple("нулевой PublicKey", "[Interface]\nPrivateKey = ${key(20)}\n[Peer]\nPublicKey = ${"A".repeat(43)}=\n", "нет PublicKey"),
        Triple("адрес с октетом 300", wg("Address = 10.0.0.300/32"), "Address"),
        Triple("адрес с ведущим нулём", wg("Address = 10.0.0.02/32"), "Address"),
        Triple("AllowedIPs /33", wg("", "PublicKey = ${key(21)}\nAllowedIPs = 10.0.0.0/33"), "AllowedIPs"),
        Triple("PersistentKeepalive не число", wg("", "PublicKey = ${key(21)}\nPersistentKeepalive = abc"), "PersistentKeepalive"),
        Triple("параметр вне раздела", "PrivateKey = ${key(20)}\n[Interface]\n[Peer]\nPublicKey = ${key(21)}\n", "вне раздела"),
        Triple("неизвестный раздел", wg("") + "[Foo]\n", "неизвестный раздел"),
        Triple("второй [Interface]", wg("") + "[Interface]\n", "второй раздел"),
        Triple("девять пиров", "[Interface]\nPrivateKey = ${key(20)}\n" + (30..38).joinToString("") { "[Peer]\nPublicKey = ${key(it)}\n" }, "пиров больше 8"),
        Triple("нет знака «=»", wg("Jc 4"), "нет знака"),
        Triple("ключ vpn:// из AmneziaVPN", "vpn://AAAAeJzLSM3JyVcozy_KSQEAGgQEXQ==", "vpn://"),
        Triple("ссылка вместо файла", "vless://$UUID@1.2.3.4:443?security=reality#x", "ссылка"),
    )
}

/** Вердикт движка на файл: null — принял, иначе его строка предупреждения. */
fun engineVerdict(eng: HostEngine, text: String): String? {
    val f = File(eng.listsDir, "awg-verdict.conf")
    f.writeText(text)
    val spec = JSONObject().put("schema", 1)
        .put("outputs", JSONObject().put("direct", JSONObject().put("kind", "direct"))
            .put("t", JSONObject().put("kind", "awg").put("conf", f.path)))
        .put("channels", JSONArray().put(JSONObject().put("name", "x").put("from", JSONArray().put("self"))
            .put("match", JSONObject().put("any", true).put("allow_all", true)).put("out", "t")))
    val (code, err) = eng.dryRun(spec.toString())
    check("движок: спека с файлом WireGuard принята (код 0)", 0, code)
    if (code != 0) println("  stderr: $err")
    return err.lines().firstOrNull { it.contains("awg: выход t:") }
}

/** Умения движка — из `steer status` по пустой спеке: status сокета без сохранённой спеки не
 *  отвечает, а стенду умения нужны до первого применения. */
fun features(eng: HostEngine): Set<String> = try {
    val f = File(eng.work, "features-spec.json")
    f.writeText("""{"schema":1,"outputs":{"direct":{"kind":"direct"}},"channels":[]}""")
    val p = ProcessBuilder(eng.steer, "status", "--spec", f.path, "--state-dir", File(eng.work, "state-features").path)
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
    p.waitFor()
    val a = JSONObject(out.trim()).optJSONArray("features") ?: JSONArray()
    (0 until a.length()).map { a.getString(it) }.toSet()
} catch (e: Exception) {
    emptySet()
}

/** Спеки, ушедшие движку, кроме вопроса «знаешь ли via» (его движок с via отвергает нарочно). */
fun realSpecs(eng: HostEngine) = eng.specs.filter { !it.contains("via-probe") }

fun testAwgParse(eng: HostEngine) {
    // ---- годные файлы: описание и вердикт движка ----
    val full = AwgConf.parse(AwgConf.normalize(AWG_FULL))
    check("полный AmneziaWG: сервер", "vpn.example.net:51820", full.endpoint)
    check("полный AmneziaWG: обфускация", true, full.obfs)
    check("полный AmneziaWG: MTU", 1280, full.mtu)
    check("полный AmneziaWG: адреса v4 и v6", listOf("10.8.0.2/32", "fd00:8::2/128"), full.addresses)
    check("полный AmneziaWG: DNS назван как неисполняемый", listOf("DNS"), full.ignored)
    check("полный AmneziaWG: в описании нет ключей", false, full.toJson().toString().let { it.contains(K_PRIV) || it.contains(K_PSK) })

    val plain = AwgConf.normalize(WG_PLAIN_RAW)
    val pi = AwgConf.parse(plain)
    check("чистый WireGuard: без обфускации", false, pi.obfs)
    check("чистый WireGuard: MTU не задан", null, pi.mtu)
    check("чистый WireGuard: BOM и \\r\\n убраны", true, !plain.startsWith("﻿") && !plain.contains('\r'))
    check("чистый WireGuard: движок отверг бы файл с BOM как есть", true, engineVerdict(eng, WG_PLAIN_RAW) != null)

    val v6 = AwgConf.parse(AwgConf.normalize(WG_V6))
    check("IPv6 Endpoint: сервер в скобках", "[2001:db8::1]:51820", v6.endpoint)
    check("Amnezia с выключенной обфускацией — обычный WireGuard", false, AwgConf.parse(AWG_OFF).obfs)
    val two = AwgConf.parse(WG_TWO_PEERS)
    check("два пира, имена в нижнем регистре: пиров", 2, two.peers)

    for ((name, text) in listOf("полный AmneziaWG" to AWG_FULL, "чистый WireGuard" to plain, "IPv6 Endpoint" to WG_V6,
            "Amnezia без обфускации" to AWG_OFF, "два пира" to WG_TWO_PEERS)) {
        val v = engineVerdict(eng, AwgConf.normalize(text))
        check("$name: движок тоже принял", null, v)
    }

    // ---- негодные: отказ логики — там же, где у движка, и без значений ключей ----
    for ((name, text, frag) in badSamples()) {
        var msg: String? = null
        expectError("файл WireGuard: $name", "bad-args", frag) { AwgConf.parse(AwgConf.normalize(text)) }
        try { AwgConf.parse(AwgConf.normalize(text)) } catch (e: BridgeError) { msg = e.message }
        check("файл WireGuard: $name — в отказе нет значения ключа", false,
            msg?.let { m -> listOf(K_PRIV, key(20), key(21)).any { m.contains(it) } } ?: false)
        check("файл WireGuard: $name — движок тоже отказал", true, engineVerdict(eng, AwgConf.normalize(text)) != null)
    }
}

fun testAwgSpecs(eng: HostEngine) {
    val b = SpecBuilder(eng.listsDir.path)
    val idFull = "00000000000000aa"
    val idPlain = "00000000000000bb"
    val idV6 = "00000000000000cc"
    File(eng.listsDir, "awg-$idFull.conf").writeText(AwgConf.normalize(AWG_FULL))
    File(eng.listsDir, "awg-$idPlain.conf").writeText(AwgConf.normalize(WG_PLAIN_RAW))
    File(eng.listsDir, "awg-$idV6.conf").writeText(AwgConf.normalize(WG_V6))
    File(eng.listsDir, "sub-s1-00000000.txt").writeText("vless://$UUID@1.2.3.4:443?security=reality&pbk=K&sni=x.com#One\n")
    File(eng.listsDir, "sub-s2-00000000.txt").writeText("vless://$UUID@5.6.7.8:443?security=reality&pbk=K&sni=y.com#Two\n")
    val src = StubSource(null, eng.listsDir, subs = mapOf("s1" to "sub-s1-00000000.txt", "s2" to "sub-s2-00000000.txt"),
        awgs = mapOf(idFull to "awg-$idFull.conf", idPlain to "awg-$idPlain.conf", idV6 to "awg-$idV6.conf"))
    val subs = """"subs":[{"id":"s1","name":"a","kind":"links"},{"id":"s2","name":"b","kind":"links"}]"""

    fun accepted(e: HostEngine, name: String, m: Model): BuiltSpec? {
        val built = SpecBuilder(e.listsDir.path).build(m, src)
        val (code, err) = e.dryRun(built.text)
        check("$name: steer apply --dry-run — код 0", 0, code)
        if (code != 0) println("  spec: ${built.text}\n  stderr: $err")
        check("$name: файл WireGuard движок разобрал без замечаний", false, err.contains("awg: выход"))
        if (err.contains("awg: выход")) println("  stderr: $err")
        val cr = e.check(built.text)
        check("$name: check управляющего сокета — код 0", 0, cr.code)
        if (cr.code != 0) println("  check: ${cr.raw}")
        return built
    }

    // 1. Выход WireGuard: путь к файлу в каталоге движка, файл в перечне заливки.
    val built = accepted(eng, "выход WireGuard", model("""{"outputs":[{"name":"fi","kind":"awg","conf":"$idFull","on_fail":"drop"},
        {"name":"home","kind":"awg","conf":"$idV6"}],
        "channels":[{"name":"Всё","who":{"kind":"phone"},"what":{"all":true},"out":"fi"}]}"""))!!
    val fi = built.spec.getJSONObject("outputs").getJSONObject("fi")
    check("выход WireGuard: kind awg", "awg", fi.getString("kind"))
    check("выход WireGuard: conf — файл в каталоге движка", "${eng.listsDir}/awg-$idFull.conf", fi.getString("conf"))
    check("выход WireGuard: файл в перечне заливки", true, "awg-$idFull.conf" in built.files)
    check("выход WireGuard: ключей в спеке нет", false, built.text.contains(K_PRIV))
    check("выход WireGuard: без via — умение via не нужно", false, built.usesVia)

    expectError("выход WireGuard без файла у приложения", "bad-args", "не найден") {
        b.build(model("""{"outputs":[{"name":"x","kind":"awg","conf":"00000000000000dd"}]}"""), src)
    }

    // 2. Модель: via — словами человека, до движка.
    val m1 = model("""{$subs,"outputs":[{"name":"nl","kind":"vless","sub":"s1"},{"name":"fi","kind":"awg","conf":"$idFull","via":"nl"}]}""")
    check("via: в модели", "nl", m1.outputs[1].via)
    check("via: в спеке", "nl", b.build(m1, src).spec.getJSONObject("outputs").getJSONObject("fi").getString("via"))
    check("via: сборка просит умение via", true, b.build(m1, src).usesVia)
    check("via: «напрямую» — то же, что нет", null,
        model("""{$subs,"outputs":[{"name":"nl","kind":"vless","sub":"s1","via":"direct"}]}""").outputs[0].via)
    check("via: круг JSON сохраняет via", "nl", model(m1.toJson().toString()).outputs[1].via)
    expectError("via: круг из двух", "bad-args", "по кругу: nl → fi → nl") {
        model("""{$subs,"outputs":[{"name":"nl","kind":"vless","sub":"s1","via":"fi"},{"name":"fi","kind":"awg","conf":"$idFull","via":"nl"}]}""")
    }
    expectError("via: круг из трёх", "bad-args", "по кругу") {
        model("""{$subs,"outputs":[{"name":"a","kind":"vless","sub":"s1","via":"b"},{"name":"b","kind":"awg","conf":"$idFull","via":"c"},{"name":"c","kind":"vless","sub":"s2","via":"a"}]}""")
    }
    expectError("via: через себя", "bad-args", "самого себя") {
        model("""{$subs,"outputs":[{"name":"nl","kind":"vless","sub":"s1","via":"nl"}]}""")
    }
    expectError("via: через несуществующий", "bad-args", "которого нет") {
        model("""{$subs,"outputs":[{"name":"nl","kind":"vless","sub":"s1","via":"ghost"}]}""")
    }
    expectError("via: у выхода-интерфейса", "bad-args", "только VLESS или WireGuard") {
        model("""{"outputs":[{"name":"vpn","kind":"interface","devices":["wg0"],"via":"x"},{"name":"x","kind":"awg","conf":"$idFull"}]}""")
    }
    expectError("via: через мост Telegram", "bad-args", "выберите туннель") {
        model("""{$subs,"outputs":[{"name":"tg","kind":"tgws","domain":"example.com"},{"name":"nl","kind":"vless","sub":"s1","via":"tg"}]}""")
    }
    expectError("via: четыре перехода", "bad-args", "длиннее трёх") {
        model("""{$subs,"outputs":[{"name":"a","kind":"vless","sub":"s1","via":"b"},{"name":"b","kind":"awg","conf":"$idFull","via":"c"},
            {"name":"c","kind":"vless","sub":"s2","via":"d"},{"name":"d","kind":"awg","conf":"$idPlain","via":"e"},{"name":"e","kind":"interface","devices":["wg0"]}]}""")
    }
    expectError("модель: два выхода с одним файлом WireGuard", "bad-args", "один и тот же файл") {
        model("""{"outputs":[{"name":"a","kind":"awg","conf":"$idFull"},{"name":"b","kind":"awg","conf":"$idFull"}]}""")
    }
    expectError("модель: выход WireGuard без файла", "bad-args", "добавьте выход заново") { model("""{"outputs":[{"name":"a","kind":"awg"}]}""") }
    check("имя устройства WireGuard: неприметное имя — как есть", "fi", Model.awgDevice("fi"))
    // Сверено с awg_default_ifname движка: FNV-1a 32 от «wg-home».
    check("имя устройства WireGuard: имя, выдающее туннель, — хэш", true, Regex("^if[0-9a-f]{8}$").matches(Model.awgDevice("wg-home")))

    // 3. via против движка: сценарии владельца и цепочка из трёх переходов.
    val chain3 = """{$subs,"outputs":[{"name":"a","kind":"vless","sub":"s1","via":"b"},{"name":"b","kind":"awg","conf":"$idFull","via":"c"},
        {"name":"c","kind":"vless","sub":"s2","via":"d"},{"name":"d","kind":"interface","devices":["wg0"]}],
        "channels":[{"name":"Всё","who":{"kind":"phone"},"what":{"all":true},"out":"a"}]}"""
    val scenarios = listOf(
        Triple("WireGuard через VLESS", true, """{$subs,"outputs":[{"name":"nl","kind":"vless","sub":"s1"},{"name":"fi","kind":"awg","conf":"$idFull","via":"nl"}],
            "channels":[{"name":"Всё","who":{"kind":"phone"},"what":{"all":true},"out":"fi"}]}"""),
        Triple("VLESS поверх WireGuard", true, """{$subs,"outputs":[{"name":"home","kind":"awg","conf":"$idPlain"},{"name":"nl","kind":"vless","sub":"s1","via":"home"}],
            "channels":[{"name":"Всё","who":{"kind":"phone"},"what":{"all":true},"out":"nl"}]}"""),
        Triple("цепочка из трёх переходов", true, chain3),
        Triple("цепочка VLESS из трёх переходов", false, """{$subs,"outputs":[{"name":"a","kind":"vless","sub":"s1","via":"b"},{"name":"b","kind":"vless","sub":"s2","via":"c"},
            {"name":"c","kind":"vless","sub":"s1","via":"d"},{"name":"d","kind":"interface","devices":["wg0"]}],
            "channels":[{"name":"Всё","who":{"kind":"phone"},"what":{"all":true},"out":"a"}]}"""),
        Triple("VLESS через выход-интерфейс", false, """{$subs,"outputs":[{"name":"vpn","kind":"interface","devices":["wg0"]},{"name":"nl","kind":"vless","sub":"s1","via":"vpn"}],
            "channels":[{"name":"Всё","who":{"kind":"phone"},"what":{"all":true},"out":"nl"}]}"""),
        Triple("VLESS через VLESS", false, """{$subs,"outputs":[{"name":"nl","kind":"vless","sub":"s1"},{"name":"de","kind":"vless","sub":"s2","via":"nl"}],
            "channels":[{"name":"Всё","who":{"kind":"phone"},"what":{"all":true},"out":"de"}]}"""),
    )
    val mainF = features(eng)
    var viaEng: HostEngine? = null
    val viaBin = System.getenv("STEER_VIA")
    if ("via" !in mainF && !viaBin.isNullOrEmpty()) {
        viaEng = HostEngine(viaBin, tmp("via-engine")).also { it.start() }
        // Файлы — туда же, где их ждёт спека этого движка.
        eng.listsDir.listFiles()?.forEach { it.copyTo(File(viaEng.listsDir, it.name), overwrite = true) }
    }
    try {
        val (e, f) = if ("via" in mainF) eng to mainF else viaEng?.let { it to features(it) } ?: (null to emptySet())
        for ((name, needsAwg, json) in scenarios) {
            if (e == null || "via" !in f) { pending.add("via, $name: движка с умением via нет"); continue }
            if (needsAwg && "awg" !in f) { pending.add("via, $name: движок с via ещё без kind awg (ждёт слияния android-via)"); continue }
            val bs = SpecBuilder(e.listsDir.path).build(model(json), StubSource(null, e.listsDir, subs = src.subs, awgs = src.awgs))
            val (code, err) = e.dryRun(bs.text)
            check("via, $name: steer apply --dry-run — код 0", 0, code)
            if (code != 0) println("  spec: ${bs.text}\n  stderr: $err")
            check("via, $name: check сокета — код 0", 0, e.check(bs.text).code)
        }
        // Движок с via отвергает и то, что модель не пропустила бы: круг, собранный мимо модели.
        if (e != null && "via" in f) {
            val loop = JSONObject(SpecBuilder(e.listsDir.path).build(model(scenarios[5].third), StubSource(null, e.listsDir, subs = src.subs)).text)
            loop.getJSONObject("outputs").getJSONObject("nl").put("via", "de")
            check("via: круг мимо модели движок тоже отвергает", true, e.dryRun(loop.toString()).let { it.first != 0 && it.second.contains("круг") })
            // Предел глубины — тот же, что у модели: четвёртый переход движок отвергает.
            val deep = JSONObject(SpecBuilder(e.listsDir.path).build(model(scenarios[3].third), StubSource(null, e.listsDir, subs = src.subs)).text)
            val o = deep.getJSONObject("outputs")
            o.getJSONObject("d").put("kind", "vless").put("sub_file", o.getJSONObject("a").getString("sub_file")).put("via", "e").remove("devices")
            o.put("e", JSONObject().put("kind", "interface").put("devices", JSONArray().put("wg1")))
            check("via: четыре перехода мимо модели движок тоже отвергает", true, e.dryRun(deep.toString()).let { it.first != 0 && it.second.contains("длиннее") })
        } else pending.add("via: отказ движка на круг — движка с умением via нет")
    } finally {
        viaEng?.stop()
    }
}

fun testAwgDispatcher() {
    val eng = HostEngine(STEER, tmp("awg-engine"))
    eng.start()
    try {
        val files = tmp("awg-files")
        val d = Dispatcher(files, eng, FakeHttp(), DeviceInfo(), eng.listsDir.path)
        fun call(m: String, a: String = "{}") = d.call(m, a)
        fun callJ(m: String, a: JSONObject) = d.call(m, a.toString())

        // Импорт: ссылка и описание, ключей в ответе нет.
        val imp = JSONObject(callJ("outputs.importAwg", JSONObject().put("name", "fi").put("text", AWG_FULL)))
        val id = imp.getString("conf")
        check("outputs.importAwg: ссылка — 16 знаков хэша", true, Regex("^[0-9a-f]{16}$").matches(id))
        check("outputs.importAwg: сервер", "vpn.example.net:51820", imp.getJSONObject("info").getString("endpoint"))
        check("outputs.importAwg: обфускация", true, imp.getJSONObject("info").getBoolean("obfs"))
        check("outputs.importAwg: ключей в ответе нет", false, imp.toString().let { it.contains(K_PRIV) || it.contains(K_PSK) })
        check("outputs.importAwg: тот же текст — та же ссылка", id,
            JSONObject(callJ("outputs.importAwg", JSONObject().put("text", AWG_FULL.replace("\n", "\r\n")))).getString("conf"))
        expectError("outputs.importAwg: пустой текст", "bad-args", "Вставьте") { call("outputs.importAwg", """{"text":"  "}""") }
        expectError("outputs.importAwg: незнакомый параметр", "bad-args", "Foo") { callJ("outputs.importAwg", JSONObject().put("text", wg("Foo = 1"))) }
        expectError("outputs.importAwg: негодное имя", "bad-args", "латиница") { callJ("outputs.importAwg", JSONObject().put("name", "my vpn").put("text", AWG_FULL)) }

        // settings.put: описание — из файла, а не от экрана.
        val out = JSONObject().put("name", "fi").put("kind", "awg").put("conf", id).put("on_fail", "drop")
            .put("info", JSONObject().put("endpoint", "подделка").put("peers", 5))
        val ch = JSONArray().put(JSONObject().put("name", "Всё").put("who", JSONObject().put("kind", "phone"))
            .put("what", JSONObject().put("all", true)).put("out", "fi"))
        callJ("settings.put", JSONObject().put("outputs", JSONArray().put(out)).put("channels", ch))
        val got = JSONObject(call("settings.get"))
        val gi = got.getJSONArray("outputs").getJSONObject(0).getJSONObject("info")
        check("settings.put: описание выхода из файла, а не присланное", "vpn.example.net:51820" to 1, gi.getString("endpoint") to gi.getInt("peers"))
        check("settings.get: ключей в модели нет", false, got.toString().contains(K_PRIV))
        expectError("settings.put: ссылка на файл, которого нет", "bad-args", "не найден") {
            callJ("settings.put", JSONObject().put("outputs", JSONArray().put(JSONObject(out.toString()).put("conf", "0123456789abcdef"))))
        }

        // spec.apply: файл залит в каталог движка, спека ссылается на него.
        val ap = JSONObject(call("spec.apply"))
        check("spec.apply с WireGuard: сохранено движком", true, ap.getBoolean("saved"))
        val engName = "awg-$id.conf"
        check("spec.apply с WireGuard: файл у движка — текст, который увидел разбор", AwgConf.normalize(AWG_FULL),
            File(eng.listsDir, engName).takeIf { it.isFile }?.readText())
        val saved = JSONObject(File(eng.work, "spec.json").readText())
        check("spec.apply с WireGuard: conf в спеке — путь из ответа put-file", "${eng.listsDir}/$engName",
            saved.getJSONObject("outputs").getJSONObject("fi").getString("conf"))

        // Замена файла: новый залит, прежний убран у движка.
        val id2 = JSONObject(callJ("outputs.importAwg", JSONObject().put("text", WG_PLAIN_RAW))).getString("conf")
        callJ("settings.put", JSONObject().put("outputs", JSONArray().put(JSONObject(out.toString()).put("conf", id2))))
        check("замена файла: применено", true, JSONObject(call("spec.apply")).getBoolean("saved"))
        check("замена файла: новый у движка", true, File(eng.listsDir, "awg-$id2.conf").isFile)
        check("замена файла: прежний убран у движка", false, File(eng.listsDir, engName).exists())
        check("замена файла: описание обновилось", false,
            JSONObject(call("settings.get")).getJSONArray("outputs").getJSONObject(0).getJSONObject("info").getBoolean("obfs"))

        // Уборка у приложения: ничей файл старше суток уходит, свежий — остаётся (черновик).
        val old = File(files, "awg/$id.conf")
        old.setLastModified(System.currentTimeMillis() - 2 * 86400 * 1000L)
        val id3 = JSONObject(callJ("outputs.importAwg", JSONObject().put("text", WG_V6))).getString("conf")
        callJ("settings.put", JSONObject().put("update", JSONObject().put("unmetered_only", true)))
        check("уборка у приложения: ничей старый файл убран", false, old.exists())
        check("уборка у приложения: свежий файл черновика на месте", true, File(files, "awg/$id3.conf").isFile)

        // Резервная копия: файл WireGuard едет в ней и возвращается.
        val bj = File(JSONObject(call("backup.export")).getString("file")).readText()
        check("backup.export: файл WireGuard в копии", true, JSONObject(bj).getJSONObject("awg").has(id2))
        callJ("settings.put", JSONObject().put("outputs", JSONArray()).put("channels", JSONArray()))
        File(files, "awg").deleteRecursively()
        check("backup.import с WireGuard", true, JSONObject(callJ("backup.import", JSONObject().put("json", bj))).getBoolean("saved"))
        check("backup.import: выход вернулся с описанием", "203.0.113.9:51820",
            JSONObject(call("settings.get")).getJSONArray("outputs").getJSONObject(0).getJSONObject("info").getString("endpoint"))
        check("backup.import: применяется", true, JSONObject(call("spec.apply")).getBoolean("saved"))
        val tampered = JSONObject(bj)
        tampered.getJSONObject("awg").put(id2, tampered.getJSONObject("awg").getString(id2).replace("51820", "51821"))
        expectError("backup.import: подменённый файл WireGuard", "bad-args", "повреждены") { callJ("backup.import", JSONObject().put("json", tampered.toString())) }

        // via: движок без умения via — отказ до применения, словами человека; с умением — применяется.
        call("subs.add", """{"url":"vless://$UUID@1.2.3.4:443?security=reality&pbk=K&sni=x.com#One"}""")
        val sid = JSONObject(call("settings.get")).getJSONArray("subs").getJSONObject(0).getString("id")
        val viaOuts = JSONArray().put(JSONObject().put("name", "nl").put("kind", "vless").put("sub", sid))
            .put(JSONObject(out.toString()).put("conf", id2).put("via", "nl"))
        callJ("settings.put", JSONObject().put("outputs", viaOuts).put("channels", ch))
        if ("via" in features(eng)) {
            check("via: движок с умением — применено", true, JSONObject(call("spec.apply")).getBoolean("saved"))
            check("via: у движка в спеке", "nl", JSONObject(File(eng.work, "spec.json").readText()).getJSONObject("outputs").getJSONObject("fi").getString("via"))
        } else {
            val before = realSpecs(eng).size
            expectError("via: движок без умения — отказ", "engine", "обновите систему") { call("spec.apply") }
            check("via: движку без умения спека не ушла", before, realSpecs(eng).size)
            check("via: spec.preview предупреждает", true, JSONObject(call("spec.preview")).getJSONArray("warnings").toString().contains("через другой выход"))
        }
        // Круг — отказ модели, до движка.
        val before = eng.specs.size
        expectError("via: круг через settings.put", "bad-args", "по кругу") {
            callJ("settings.put", JSONObject().put("outputs", JSONArray().put(JSONObject().put("name", "nl").put("kind", "vless").put("sub", sid).put("via", "fi"))
                .put(JSONObject(out.toString()).put("conf", id2).put("via", "nl"))))
        }
        check("via: круг не дошёл до движка", before, eng.specs.size)
        check("методы моста с WireGuard: каждая спека принята dry-run", true, realSpecs(eng).all { eng.dryRun(it).first == 0 })
    } finally {
        eng.stop()
    }
}

fun testAwg() {
    val eng = HostEngine(STEER, tmp("awg-spec-engine"))
    eng.start()
    try {
        if ("awg" !in features(eng)) {
            pending.add("выход WireGuard: движок стенда без kind awg")
            return
        }
        testAwgParse(eng)
        testAwgSpecs(eng)
    } finally {
        eng.stop()
    }
    testAwgDispatcher()
}
