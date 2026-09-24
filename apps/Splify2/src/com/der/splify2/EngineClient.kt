// Клиент управляющего сокета движка — протокол версии 1 (docs/ctl.md в дереве steer).
//
// Это единственная дверь приложения к движку: исполнить steer или прочитать его файлы домену
// splify2_app политика не даёт (sepolicy/private/steerd.te), разрешён ровно connectto к
// сокету /data/misc/steer/steer.sock (splify2_app.te). Всё, что на роутере rpcd делает вызовом
// программы, здесь идёт запросом через этот сокет.
//
// Форма протокола и почему клиент устроен так:
//   - одно соединение — один запрос и один ответ; соединения не переиспользуются, пула нет:
//     сервер после ответа закрывает сокет сам, а держать открытое соединение к демону в фоне
//     против требования батареи;
//   - строка ASCII до 512 байт с \n; у команд с телом последнее слово — длина тела, и сразу за
//     \n идут ровно эти байты. Слова проверяются здесь по тем же правилам, что и на сервере, —
//     чтобы негодный аргумент давал понятную ошибку bad-args, а не bad-request сервера;
//   - ответ — одна строка JSON; читаем до \n, а не до закрытия (так написано в ctl.md: сервер
//     может закрыть сокет чуть позже);
//   - сроки: сервер держит у каждой команды свой срок и убивает подкоманду по нему, отвечая
//     error=timeout. Клиенту остаётся ждать немного дольше серверного срока — тогда ответ о
//     таймауте с частью вывода дойдёт, а не потеряется на нашем обрыве.
//
// Ошибки делятся на три рода, и мост показывает их по-разному:
//   EngineDown  — не удалось соединиться или ответа нет (сервис не запущен, сокета нет);
//   EngineError — сервер ответил отказом (error в ответе) или ответ не разобрать;
//   успех       — CtlResult с кодом подкоманды: ненулевой код не ошибка протокола, решает
//                 вызывающий (у diag код 1 — «есть поломка», и JSON при этом полный).
package com.der.splify2

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Разобранный ответ сервера. [json] — весь объект: у apply в нём ещё saved/applied/enabled. */
class CtlResult(
    val cmd: String?,
    val code: Int?,
    val stdout: String,
    val stderr: String,
    val error: String?,
    val message: String?,
    val truncated: Boolean,
    val json: JSONObject,
    val raw: String,
)

class EngineDown(message: String, cause: Throwable? = null) : IOException(message, cause)
class EngineError(val kind: String, message: String) : Exception(message)

class EngineClient(private val path: String = SOCKET_PATH) {

    fun version(): CtlResult = run("version", timeoutSec = 5)
    fun status(fast: Boolean = false): CtlResult =
        run(if (fast) "status fast" else "status", timeoutSec = 15)
    fun diag(): CtlResult = run("diag", timeoutSec = 30)
    fun explain(q: String): CtlResult = run("explain ${word(q, ADDR_WORD)}", timeoutSec = 15)
    fun vlessNodes(out: String): CtlResult =
        run("vless-nodes ${word(out, NAME_WORD)}", timeoutSec = 15)

    /** [node] −1…9999 (−1 — все узлы, как у steer vless-probe); срок пробы оставляем движку. */
    fun vlessProbe(out: String, node: Int?): CtlResult {
        var line = "vless-probe ${word(out, NAME_WORD)}"
        if (node != null) {
            require(node in -1..9999) { "узел вне -1…9999" }
            line += " $node"
        }
        return run(line, timeoutSec = 180)
    }

    fun check(spec: ByteArray): CtlResult = run("check", body = spec, timeoutSec = 120)
    // apply: 120 с на проверку и 300 с на само применение (ctl.md, таблица команд).
    fun apply(spec: ByteArray): CtlResult = run("apply", body = spec, timeoutSec = 420)
    fun reload(): CtlResult = run("reload", timeoutSec = 30)
    fun conns(): CtlResult = run("conns", timeoutSec = 15)
    fun dnsLog(): CtlResult = run("dns-log", timeoutSec = 15)

    /**
     * Положить файл списка в /data/misc/steer/lists/<name>. Команды put-file у сервера пока нет
     * (BRIDGE.md, «Файлы списков и движок»): до её появления сервер отвечает unknown-command —
     * это обычный ответ с error, и логика показывает человеку отказ engine, вместо того чтобы
     * молча собрать спеку со ссылками на несуществующие файлы (LogicEngine.putFile).
     * Форма запроса выбрана по образцу apply: имя и длина тела последними словами.
     */
    fun putFile(name: String, data: ByteArray): CtlResult =
        run("put-file ${word(name, FILE_WORD)}", body = data, timeoutSec = 60)

    /**
     * Один запрос. busy (у сервера уже четыре запроса) повторяем дважды с короткой паузой:
     * это обычная толчея, когда экран открылся и разом спрашивает status, diag и версию.
     */
    fun run(line: String, body: ByteArray? = null, timeoutSec: Int): CtlResult {
        var attempt = 0
        while (true) {
            val r = once(line, body, timeoutSec)
            if (r.error != "busy" || attempt >= 2) return r
            attempt++
            try {
                Thread.sleep(300L * attempt)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return r
            }
        }
    }

    private fun once(line: String, body: ByteArray?, timeoutSec: Int): CtlResult {
        if (body != null && body.size > MAX_BODY) {
            throw EngineError("too-large", "Слишком большой файл: ${body.size / 1024} КиБ")
        }
        val head = if (body != null) "$line ${body.size}\n" else "$line\n"
        val headBytes = head.toByteArray(Charsets.US_ASCII)
        if (headBytes.size > MAX_LINE) throw IllegalArgumentException("строка запроса длиннее $MAX_LINE байт")

        val s = LocalSocket()
        try {
            try {
                s.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            } catch (e: IOException) {
                // ENOENT (сервиса нет) и ECONNREFUSED (сокет остался от упавшего сервера) —
                // одно и то же для человека: движок не отвечает.
                throw EngineDown("Движок не отвечает", e)
            }
            // Срок чтения — серверный срок команды плюс запас: ответ о таймауте должен успеть
            // дойти. Отправка тела ограничена сервером (5 с на весь запрос), здесь не нужна.
            s.soTimeout = (timeoutSec + REPLY_MARGIN_SEC) * 1000
            // Отказ на строке (unknown-command, too-large, denied) сервер шлёт, не читая тела, и
            // закрывает сокет — наша запись тела тогда падает с EPIPE. Ответ к этому моменту уже
            // лежит в приёмном буфере, поэтому ошибку записи запоминаем и всё равно читаем.
            var writeFailure: IOException? = null
            try {
                val out = s.outputStream
                out.write(headBytes)
                if (body != null) out.write(body)
                out.flush()
            } catch (e: IOException) {
                writeFailure = e
            }
            val raw = try {
                readLine(s)
            } catch (e: IOException) {
                throw EngineDown("Движок не ответил", writeFailure ?: e)
            }
            return parse(raw)
        } finally {
            try {
                s.close()
            } catch (_: IOException) {
            }
        }
    }

    /** Читает до \n. Предел — с запасом на экранирование: stdout до 1 МиБ, stderr до 64 КиБ. */
    private fun readLine(s: LocalSocket): String {
        val input = s.inputStream
        val buf = ByteArray(16 * 1024)
        val acc = ByteArrayOutputStream(4096)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            val nl = indexOf(buf, n, '\n'.code.toByte())
            if (nl >= 0) {
                acc.write(buf, 0, nl)
                return acc.toString(Charsets.UTF_8)
            }
            acc.write(buf, 0, n)
            if (acc.size() > MAX_REPLY) throw IOException("ответ длиннее ${MAX_REPLY / 1024 / 1024} МиБ")
        }
        if (acc.size() == 0) throw IOException("соединение закрыто без ответа")
        // Без \n, но что-то пришло: пробуем разобрать как есть — лучше, чем потерять ответ.
        return acc.toString(Charsets.UTF_8)
    }

    private fun parse(raw: String): CtlResult {
        val o = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            Log.w(TAG, "ответ сокета не JSON: ${raw.take(200)}")
            throw EngineError("bad-reply", "Движок ответил непонятно")
        }
        return CtlResult(
            cmd = o.optString("cmd").ifEmpty { null },
            code = if (o.has("code") && !o.isNull("code")) o.optInt("code") else null,
            stdout = o.optString("stdout"),
            stderr = o.optString("stderr"),
            error = o.optString("error").ifEmpty { null },
            message = o.optString("message").ifEmpty { null },
            truncated = o.optBoolean("truncated"),
            json = o,
            raw = raw,
        )
    }

    private fun indexOf(b: ByteArray, n: Int, v: Byte): Int {
        for (i in 0 until n) if (b[i] == v) return i
        return -1
    }

    /**
     * Слово запроса по правилам сервера: ни пробелов, ни управляющих знаков, не начинается с
     * «-» (сервер отвергает, чтобы слово не стало флагом подкоманды) и с «.».
     */
    private fun word(v: String, re: Regex): String {
        require(re.matches(v)) { "недопустимое значение: $v" }
        return v
    }

    companion object {
        const val SOCKET_PATH = "/data/misc/steer/steer.sock"
        private const val TAG = "splify2.engine"
        private const val MAX_LINE = 512
        private const val MAX_BODY = 1 shl 20
        // stdout 1 МиБ + stderr 64 КиБ, каждый байт в JSON может стать \uXXXX (6 байт) —
        // верхняя граница с запасом; больше сервер прислать не может.
        private const val MAX_REPLY = 8 shl 20
        private const val REPLY_MARGIN_SEC = 10

        // Имя выхода — [A-Za-z0-9_.-] до 31 знака; адрес или имя у explain — ещё «:» и «/»,
        // до 253 знаков (ctl.md, «Запрос»). Имя файла списка — как имя выхода, но длиннее:
        // имена списков каталога бывают длиннее 31 знака.
        private val NAME_WORD = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,30}")
        private val ADDR_WORD = Regex("[A-Za-z0-9_:][A-Za-z0-9_.:/-]{0,252}")
        private val FILE_WORD = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")
    }
}
