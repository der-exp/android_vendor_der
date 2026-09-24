/*
 * Швы стенда логики: движок — НАСТОЯЩИЙ (хостовая сборка steer под Android через свой
 * управляющий сокет ctl-serve), сеть — записанные ответы.
 *
 * Почему движок настоящий, а не заглушка. Главное, что делает логика, — собирает спеку, и
 * единственный судья её правильности — компилятор движка. Заглушка, которая «принимает всё»,
 * проверяла бы сборщик против моего же представления о спеке, то есть ничего. Здесь каждая
 * спека проходит два пути движка: команду `check` сокета (тот же протокол, что на телефоне) и
 * `steer apply --dry-run` из командной строки, и коды обязаны совпасть.
 *
 * `put-file` у движка ещё нет. Стенд умеет оба режима: `putFileSupported = true` — файл кладётся
 * в каталог списков стенда (так, как это будет делать команда), и dry-run читает настоящие
 * списки; `false` — запрос уходит в настоящий сокет, и сервер отвечает `unknown-command`, как
 * ответит сегодняшний движок на телефоне.
 */
import com.der.splify2.logic.CtlReply
import com.der.splify2.logic.Engine
import com.der.splify2.logic.Http
import com.der.splify2.logic.HttpResult
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.TimeUnit

class HostEngine(
    private val steer: String,
    val work: File,
    var putFileSupported: Boolean = true,
) : Engine {
    val listsDir = File(work, "lists").also { it.mkdirs() }
    private val sock = File(work, "s.sock")
    private val specFile = File(work, "spec.json")
    private val state = File(work, "state").also { it.mkdirs() }
    private var proc: Process? = null
    val specs = ArrayList<String>()          // всё, что ушло в check/apply
    var dryRunMismatch = 0

    fun start() {
        sock.delete()
        val pb = ProcessBuilder(steer, "ctl-serve", "--socket", sock.path, "--spec", specFile.path,
            "--state-dir", state.path)
        // Движок «выключен»: apply только сохраняет спеку и не трогает ядро машины стенда (ctl.c,
        // ctl_enabled). Проверка dry-run при этом та же, что при включённом.
        pb.environment()["STEER_CTL_ENABLED"] = "0"
        pb.redirectErrorStream(true).redirectOutput(File(work, "serve.log"))
        proc = pb.start()
        repeat(100) { if (sock.exists()) return; Thread.sleep(50) }
        throw IllegalStateException("ctl-serve не поднялся: " + File(work, "serve.log").readText())
    }

    fun stop() { proc?.destroy(); proc?.waitFor(5, TimeUnit.SECONDS) }

    fun raw(line: String, body: ByteArray?): CtlReply {
        SocketChannel.open(UnixDomainSocketAddress.of(sock.toPath())).use { ch ->
            val head = (if (body != null) "$line ${body.size}\n" else "$line\n").toByteArray(Charsets.US_ASCII)
            ch.write(ByteBuffer.wrap(head))
            if (body != null) {
                val bb = ByteBuffer.wrap(body)
                while (bb.hasRemaining()) ch.write(bb)
            }
            val out = ByteArrayOutputStream()
            val buf = ByteBuffer.allocate(65536)
            loop@ while (true) {
                buf.clear()
                val n = ch.read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    val b = buf.get(i)
                    if (b == '\n'.code.toByte()) break@loop
                    out.write(b.toInt())
                }
            }
            val s = out.toString("UTF-8")
            val o = JSONObject(s)
            return CtlReply(if (o.has("code")) o.getInt("code") else null, o.optString("stdout"), o.optString("stderr"),
                if (o.has("error")) o.getString("error") else null, s)
        }
    }

    /** `steer apply --dry-run` из командной строки — второй путь того же компилятора. */
    fun dryRun(spec: String): Pair<Int, String> {
        val f = File(work, "cli-spec.json")
        f.writeText(spec)
        val p = ProcessBuilder(steer, "apply", "--dry-run", "--spec", f.path, "--state-dir", File(work, "state-cli").path)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.PIPE).start()
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        return p.exitValue() to err
    }

    private fun both(cmd: String, spec: String): CtlReply {
        specs.add(spec)
        val r = raw(cmd, spec.toByteArray(Charsets.UTF_8))
        val (code, _) = dryRun(spec)
        if (r.code != null && r.error == null && r.code != code && cmd == "check") dryRunMismatch++
        return r
    }

    override fun check(spec: String) = both("check", spec)
    override fun apply(spec: String) = both("apply", spec)
    override fun status() = raw("status", null)
    override fun putFile(name: String, data: ByteArray): CtlReply {
        if (!putFileSupported) return raw("put-file $name", data)
        File(listsDir, name).writeBytes(data)
        return CtlReply(0, "", "", null, "{\"v\":1,\"cmd\":\"put-file\",\"code\":0}")
    }
}

/** Сеть по записи: адрес → ответ. Незнакомый адрес — 404; `down` — сети нет вовсе. */
class FakeHttp : Http {
    val routes = HashMap<String, HttpResult>()
    val seen = ArrayList<Pair<String, Map<String, String>>>()
    var down = false

    fun put(url: String, body: ByteArray, headers: Map<String, String> = emptyMap(), code: Int = 200) {
        routes[url] = HttpResult(code, body, headers)
    }
    fun put(url: String, body: String, headers: Map<String, String> = emptyMap()) = put(url, body.toByteArray(), headers)

    override fun get(url: String, headers: Map<String, String>): HttpResult {
        seen.add(url to headers)
        if (down) throw IOException("сети нет")
        return routes[url] ?: HttpResult(404, ByteArray(0), emptyMap())
    }
}
