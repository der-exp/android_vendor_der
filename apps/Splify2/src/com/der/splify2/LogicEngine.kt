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
     * Команды put-file у сервера ещё нет: он отвечает unknown-command, и этот ответ уходит
     * логике как есть (CtlReply с error). Логика превращает его в отказ engine со своей фразой
     * (Dispatcher.push) — так spec.apply со списками честно не проходит, а не собирает спеку со
     * ссылкой на несуществующий файл. Слова отказа — одни, у логики, а не два разных текста.
     */
    override fun putFile(name: String, data: ByteArray): CtlReply = wrap { client.putFile(name, data) }

    private inline fun wrap(f: () -> CtlResult): CtlReply {
        val r = try {
            f()
        } catch (e: EngineDown) {
            throw BridgeError(Shell.E_ENGINE_DOWN, e.message ?: "Движок не отвечает")
        } catch (e: EngineError) {
            throw BridgeError(Shell.E_ENGINE, e.message ?: "Движок отказал")
        } catch (e: IllegalArgumentException) {
            throw BridgeError(Shell.E_BAD_ARGS, e.message ?: "Недопустимое значение")
        }
        return CtlReply(code = r.code, stdout = r.stdout, stderr = r.stderr, error = r.error, raw = r.raw)
    }
}
