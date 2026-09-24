// Движок для логики: logic.Engine поверх EngineClient.
//
// Логика собирает спеку и заливает файлы списков, но с сокетом не говорит — это дело оболочки
// (BRIDGE.md: «оболочка — сокет движка»). Граница проведена так, чтобы логику можно было
// проверять без Android: ей нужен только этот интерфейс, а не LocalSocket.
//
// Ошибки связи становятся BridgeError с кодами моста: engine-down — сокет не ответил, engine —
// сервер отказал на уровне протокола. Отказ самой подкоманды (ненулевой code, stderr) — не
// ошибка: это обычный CtlReply, и что с ним делать (показать причину отказа check, откатить
// модель), решает логика.
package com.der.splify2

import com.der.splify2.logic.BridgeError
import com.der.splify2.logic.CtlReply
import com.der.splify2.logic.Engine

class LogicEngine(private val client: EngineClient) : Engine {

    override fun check(spec: String): CtlReply = wrap { client.check(spec.toByteArray(Charsets.UTF_8)) }

    override fun apply(spec: String): CtlReply = wrap { client.apply(spec.toByteArray(Charsets.UTF_8)) }

    override fun status(): CtlReply = wrap { client.status() }

    /**
     * Файловые команды. Отказ сервера (unknown-command у старого движка, in-use у rm-file
     * файла из сохранённой спеки, too-large) уходит логике как есть — CtlReply с error: что
     * сказать человеку и можно ли продолжать, решает она (Dispatcher.push, sweep). Слова отказа
     * — одни, у логики, а не два разных текста.
     */
    override fun putFile(name: String, data: ByteArray): CtlReply = wrap { client.putFile(name, data) }

    override fun listFiles(): CtlReply = wrap { client.listFiles() }

    override fun rmFile(name: String): CtlReply = wrap { client.rmFile(name) }

    private inline fun wrap(f: () -> CtlResult): CtlReply {
        val r = try {
            f()
        } catch (e: EngineDown) {
            throw BridgeError(Shell.E_ENGINE_DOWN, e.message ?: "Движок не отвечает")
        } catch (e: EngineError) {
            // too-large до отправки — тоже ответ, а не исключение: логика знает, что сказать.
            if (e.kind == "too-large") return CtlReply(null, "", "", "too-large", "{\"error\":\"too-large\"}")
            throw BridgeError(Shell.E_ENGINE, e.message ?: "Движок отказал")
        } catch (e: IllegalArgumentException) {
            throw BridgeError(Shell.E_BAD_ARGS, e.message ?: "Недопустимое значение")
        }
        return CtlReply(code = r.code, stdout = r.stdout, stderr = r.stderr, error = r.error, raw = r.raw)
    }
}
