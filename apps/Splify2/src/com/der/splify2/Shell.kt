// Оболочка: одна на процесс, живёт в Splify2App. Держит всё, что переживает экран, — клиент
// движка, пул потоков моста, логику, оркестрацию Private DNS — и разводит вызовы моста по
// владельцам (BRIDGE.md, «Методы»): engine, system, apps — здесь; settings, spec, lists, subs,
// backup — в logic.Dispatcher.
//
// Почему на процесс, а не на Activity: вызов моста может идти долго (apply — до семи минут,
// проба узлов — до трёх), а Activity пересоздаётся сменой темы. Работа при этом не должна
// обрываться на середине: спека, начавшая применяться, должна примениться и перечитаться, а
// ответ просто не найдёт страницу, которая его ждала, — это безопасно, страница спросит
// состояние заново.
package com.der.splify2

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemProperties
import android.util.Log
import com.der.splify2.logic.BridgeError
import com.der.splify2.logic.DeviceInfo
import com.der.splify2.logic.Dispatcher
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Отказ оболочки с кодом из BRIDGE.md («Коды ошибок») и фразой для человека. */
class ShellError(val code: String, message: String) : Exception(message)

/** Куда идут события без запроса (window.__splifyEvent). Ставит открытый экран. */
fun interface EventSink {
    fun event(name: String, payloadJson: String)
}

class Shell(val app: Context) {

    val prefs: SharedPreferences = app.getSharedPreferences("shell", Context.MODE_PRIVATE)
    val engine = EngineClient()
    val privateDns = PrivateDns(app, prefs)
    val apps = AppList(app)
    val system = SystemInfo(app)
    private val http = HttpClient(app)

    // Логика создаётся при первом вызове и в потоке пула: конструктор может читать файлы.
    // Её фоновая работа (обновление списков, подписок) шлёт события через onEvent — они идут
    // на экран как есть. DeviceInfo — что логика говорит панели подписки об устройстве.
    private val dispatcher: Dispatcher by lazy {
        Dispatcher(app.filesDir, LogicEngine(engine), http, deviceInfo()).also {
            it.onEvent = { name, payload -> emit(name, payload) }
        }
    }

    @Volatile var events: EventSink? = null

    /**
     * Пул моста. Четыре потока — столько запросов одновременно принимает сервер сокета
     * (больше — busy); лишнее ждёт в очереди. Потоки гаснут через минуту простоя: в фоне
     * процесс не держит ни одного живого потока сверх того, что держит сама платформа.
     */
    val pool: ExecutorService = ThreadPoolExecutor(
        POOL_SIZE, POOL_SIZE, 60, TimeUnit.SECONDS, LinkedBlockingQueue(),
        object : ThreadFactory {
            private val n = AtomicInteger()
            override fun newThread(r: Runnable) = Thread(r, "splify2-bridge-${n.incrementAndGet()}")
        },
    ).apply { allowCoreThreadTimeOut(true) }

    fun emit(name: String, payloadJson: String) {
        events?.event(name, payloadJson)
    }

    /**
     * Вызов моста целиком: метод → ответ в форме BRIDGE.md
     * `{"ok":true,"result":…}` или `{"ok":false,"error":"<код>","message":"…"}`. Никогда не
     * бросает: всё, что пошло не так, становится ответом с кодом.
     */
    fun reply(method: String, argsJson: String?): String = try {
        val result = route(method, normalizeArgs(argsJson))
        "{\"ok\":true,\"result\":$result}"
    } catch (e: BridgeError) {
        fail(e.code, e.message ?: "")
    } catch (e: ShellError) {
        fail(e.code, e.message ?: "")
    } catch (e: EngineDown) {
        fail(E_ENGINE_DOWN, e.message ?: "Движок не отвечает")
    } catch (e: EngineError) {
        fail(E_ENGINE, e.message ?: "Движок отказал")
    } catch (e: JSONException) {
        fail(E_BAD_ARGS, "Неверные данные запроса")
    } catch (e: IllegalArgumentException) {
        fail(E_BAD_ARGS, e.message ?: "Неверные данные запроса")
    } catch (e: IOException) {
        Log.w(TAG, "$method: ввод-вывод", e)
        fail(E_IO, "Не удалось прочитать или записать данные")
    } catch (e: Exception) {
        Log.e(TAG, "$method: сбой", e)
        fail(E_INTERNAL, "Внутренняя ошибка")
    }

    /** Результат метода — готовый текст JSON (объект, массив или значение). */
    private fun route(method: String, argsJson: String): String {
        val args = JSONObject(argsJson)
        return when (method) {
            "engine.state" -> engineState().toString()
            "engine.setEnabled" -> setEnabled(args.getBoolean("on")).toString()
            "engine.status" -> engineJson(engine.status(args.optBoolean("fast")))
            "engine.diag" -> engineJson(engine.diag())
            "engine.explain" -> engineJson(engine.explain(args.getString("q").trim()))
            "engine.vlessNodes" -> engineJson(engine.vlessNodes(args.getString("out")))
            "engine.vlessProbe" -> engineJson(
                engine.vlessProbe(args.getString("out"), optNode(args)),
            )
            "engine.conns" -> engineJson(engine.conns())
            "engine.dnsLog" -> engineJson(engine.dnsLog())

            "system.network" -> system.network().toString()
            "system.privateDns" -> privateDns.state().toString()

            "apps.list" -> apps.list(args.optBoolean("system")).toString()

            "spec.apply" -> specApply(argsJson)

            else -> if (method.substringBefore('.') in LOGIC_GROUPS) {
                logic(method, argsJson)
            } else {
                throw ShellError(E_UNKNOWN_METHOD, "Эта версия приложения не знает такого действия")
            }
        }
    }

    /** Метод логики как есть. Результат проверяется: в ответ моста уходит только JSON. */
    fun logic(method: String, argsJson: String): String {
        val r = dispatcher.call(method, argsJson)
        JSONTokener(r).nextValue()
        return r
    }

    // --- engine ---------------------------------------------------------------------------

    fun engineEnabled(): Boolean = SystemProperties.get(PROP_ENABLED) == "1"

    /** engine.state: выключатель и отвечает ли сокет (запрос version, срок 5 с). */
    fun engineState(): JSONObject {
        var reachable = false
        var version = ""
        try {
            val r = engine.version()
            if (r.error == null) {
                reachable = true
                version = r.stdout.trim().lineSequence().firstOrNull()?.trim() ?: ""
            }
        } catch (_: EngineDown) {
        } catch (_: EngineError) {
        }
        return JSONObject()
            .put("enabled", engineEnabled())
            .put("reachable", reachable)
            .put("version", version)
    }

    /**
     * engine.setEnabled. Выключатель — свойство persist.der.steer.enabled: init по нему
     * поднимает или гасит демонов и снимает правила (init/steerd.rc). Через сокет он не идёт
     * нарочно — движку писать это свойство запрещено политикой, чтобы он не мог включить сам
     * себя; писать его может только splify2 (set_prop в splify2_app.te).
     *
     * Перед включением — spec.apply: при выключенном движке сервер сокета спеку только
     * сохраняет, и init, подняв steerd-apply, применит именно текущую модель, а не ту, что
     * осталась в spec.json с прошлого раза. Не собралась спека — не включаем: включённый
     * движок со старыми правилами хуже, чем выключенный с понятной ошибкой.
     */
    private fun setEnabled(on: Boolean): JSONObject {
        if (on) specApply("{}")
        try {
            SystemProperties.set(PROP_ENABLED, if (on) "1" else "0")
        } catch (e: RuntimeException) {
            Log.e(TAG, "не удалось поставить $PROP_ENABLED", e)
            throw ShellError(E_INTERNAL, if (on) "Не удалось включить" else "Не удалось выключить")
        }
        // Доменные каналы телефона работают только при включённом движке — и Private DNS
        // держим выключенным только тогда.
        privateDns.reconcile(on)
        val state = engineState()
        emit("engine.changed", state.toString())
        return state
    }

    /**
     * Ответ подкоманды движка как JSON для экрана. У status, diag, vless-* stdout — сам JSON;
     * его и отдаём, даже при ненулевом коде (у diag код 1 значит «есть поломка», а подробности
     * — в том же JSON). Не JSON — `{"text":…}` при коде 0 (explain бывает текстом), иначе
     * отказ с последней строкой stderr.
     */
    private fun engineJson(r: CtlResult): String {
        if (r.error != null) throw EngineError(r.error, refusal(r))
        val out = r.stdout.trim()
        if (out.startsWith("{") || out.startsWith("[")) {
            try {
                JSONTokener(out).nextValue()
                return out
            } catch (_: JSONException) {
            }
        }
        if (r.code == 0) return JSONObject().put("text", r.stdout).toString()
        throw EngineError(E_ENGINE, lastLine(r.stderr) ?: "Движок завершился с кодом ${r.code}")
    }

    private fun optNode(args: JSONObject): Int? =
        if (args.has("node") && !args.isNull("node")) args.getString("node").trim().toInt() else null

    // --- логика с поведением оболочки -----------------------------------------------------

    /**
     * spec.apply: собирает, заливает и применяет логика; оболочке остаются Private DNS и
     * событие engine.changed.
     *
     * Признак доменных каналов телефона — logic.ApplyResult.needsLocalDns (BRIDGE.md,
     * «Оркестрация Private DNS»), итог последнего apply у Dispatcher.lastApply. Логика уже
     * учла, встала ли спека на место: если движок новую отверг, в силе прежняя, и признак в
     * lastApply прежний. Поэтому он берётся и после отказа — это просто сверка.
     * BridgeError из логики (проверка не прошла, движок не ответил) уходит на экран как есть,
     * Private DNS тогда не трогается: спека у движка та же, что была.
     */
    private fun specApply(argsJson: String): String {
        val r = logic("spec.apply", argsJson)
        dispatcher.lastApply?.let { privateDns.onSpecApplied(it.needsLocalDns, engineEnabled()) }
        emit("engine.changed", engineState().toString())
        return r
    }

    // --- мелочи ---------------------------------------------------------------------------

    private fun deviceInfo() = DeviceInfo(
        os = "Android",
        osVersion = Build.VERSION.RELEASE ?: "",
        model = Build.MODEL ?: "",
    )

    private fun normalizeArgs(a: String?): String {
        val t = a?.trim()
        if (t.isNullOrEmpty() || t == "null" || t == "undefined") return "{}"
        if (!t.startsWith("{")) throw ShellError(E_BAD_ARGS, "Неверные данные запроса")
        return t
    }

    private fun fail(code: String, message: String): String =
        JSONObject().put("ok", false).put("error", code).put("message", message).toString()

    companion object {
        private const val TAG = "splify2"
        private const val POOL_SIZE = 4
        const val PROP_ENABLED = "persist.der.steer.enabled"

        const val E_BAD_ARGS = "bad-args"
        const val E_UNKNOWN_METHOD = "unknown-method"
        const val E_ENGINE_DOWN = "engine-down"
        const val E_ENGINE = "engine"
        const val E_NETWORK = "network"
        const val E_IO = "io"
        const val E_INTERNAL = "internal"

        private val LOGIC_GROUPS = setOf("settings", "spec", "lists", "subs", "backup")

        fun lastLine(s: String): String? =
            s.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }

        /** Фраза отказа сервера: его message (он пишет её для человека), иначе по виду. */
        fun refusal(r: CtlResult): String = r.message ?: when (r.error) {
            "unknown-command" -> "Эта версия движка так не умеет — нужно обновление прошивки"
            "denied" -> "Движок не принимает запросы от приложения"
            "busy" -> "Движок занят, повторите позже"
            "timeout" -> "Движок не успел ответить"
            "too-large" -> "Слишком большой запрос"
            else -> lastLine(r.stderr) ?: "Движок отказал"
        }
    }
}
