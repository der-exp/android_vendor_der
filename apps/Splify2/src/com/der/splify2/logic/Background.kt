/*
 * Фоновое обновление списков и подписок — раз в сутки, из JobService оболочки.
 *
 * ТРЕБОВАНИЕ ВЛАДЕЛЬЦА: низкий расход батареи и нормальный сон. Поэтому здесь нет ни своего
 * расписания, ни будильника, ни сервиса: когда и при какой сети запускать, решает JobScheduler
 * оболочки (условие сети, «не на лимитной» — по настройке, не чаще раза в сутки), а этот код
 * делает одну работу и возвращается. Всё, что он делает, — обычные запросы в сеть и разговор с
 * движком по локальному сокету; ни одного ожидания по таймеру.
 *
 * Единственный файл логики, которому нужен Android: Context (каталог приложения) и LocalSocket
 * (управляющий сокет движка) — остальное проверяется на JVM. Сокет здесь свой, маленький, а не
 * клиент оболочки: договор даёт фоновой работе только Context, и логика не должна знать классов
 * оболочки. Оболочка может подставить свои реализации через `engineFactory`/`httpFactory`.
 */
package com.der.splify2.logic

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object Background {
    @Volatile
    var engineFactory: ((Context) -> Engine)? = null

    @Volatile
    var httpFactory: ((Context) -> Http)? = null

    /** Одна ежедневная работа: списки, затем подписки. Отказ одной части не отменяет другую —
     *  списки и подписки приходят из разных мест, и закрытый GitHub не повод оставить человека
     *  без свежих узлов. Исключения наружу не выпускаются: JobService не должен падать. */
    @JvmStatic
    fun runDaily(ctx: Context) {
        val engine = engineFactory?.invoke(ctx) ?: SocketEngine()
        val http = httpFactory?.invoke(ctx) ?: UrlHttp()
        val d = Dispatcher(ctx.filesDir, engine, http,
            DeviceInfo(os = "Android", osVersion = Build.VERSION.RELEASE ?: "", model = Build.MODEL ?: ""))
        try { d.updateListsNow(false) } catch (e: Exception) { }
        try { d.refreshSubsNow(null, emit = false) } catch (e: Exception) { }
    }
}

/** Клиент управляющего сокета движка (steer/docs/ctl.md) — для фоновой работы.
 *
 *  Одно соединение — один запрос: строка «команда [слова] [длина]\n», за ней тело; ответ —
 *  одна строка JSON, читается до \n, а не до закрытия. */
internal class SocketEngine(private val path: String = "/data/misc/steer/steer.sock") : Engine {
    private fun call(line: String, body: ByteArray?): CtlReply {
        val s = LocalSocket()
        try {
            s.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            // Срок ответа — с запасом на apply (до 420 с у сервера, см. ctl.md); ожидание здесь
            // блокирующее чтение, а не таймер: во сне устройства оно ничего не будит.
            s.soTimeout = 450_000
            val out = s.outputStream
            out.write((if (body != null) "$line ${body.size}\n" else "$line\n").toByteArray(Charsets.US_ASCII))
            if (body != null) out.write(body)
            out.flush()
            val inp = s.inputStream
            val buf = ByteArrayOutputStream()
            while (true) {
                val c = inp.read()
                if (c < 0 || c == '\n'.code) break
                buf.write(c)
            }
            val raw = buf.toString("UTF-8")
            val o = JSONObject(raw)
            return CtlReply(if (o.has("code")) o.optInt("code") else null, o.optString("stdout", ""),
                o.optString("stderr", ""), o.str("error"), raw)
        } catch (e: java.io.IOException) {
            throw BridgeError("engine-down", "Движок не отвечает — включите его или перезагрузите телефон")
        } catch (e: org.json.JSONException) {
            throw BridgeError("engine-down", "Движок ответил непонятно — перезагрузите телефон")
        } finally {
            try { s.close() } catch (e: Exception) { }
        }
    }

    override fun check(spec: String) = call("check", spec.toByteArray(Charsets.UTF_8))
    override fun apply(spec: String) = call("apply", spec.toByteArray(Charsets.UTF_8))
    override fun putFile(name: String, data: ByteArray) = call("put-file $name", data)
    override fun status() = call("status", null)
}
