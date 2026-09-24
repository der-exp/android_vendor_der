/*
 * Договор логики splify2 с оболочкой приложения (см. apps/Splify2/BRIDGE.md).
 *
 * Здесь только типы, которыми логика и оболочка обмениваются: как логика зовёт движок и сеть и
 * чем она отвечает. Реализации — у оболочки (сокет движка, настоящая сеть Android); логика их
 * не создаёт, а получает в конструкторе Dispatcher. ПОЧЕМУ ТАК: всё, что здесь не зависит от
 * Android, обязано проверяться на обычной JVM (tools/app-check/logic-test.sh), а сокет
 * `LocalSocket` и сеть Android на JVM не поднять. Подменой этих двух швов стенд гоняет ту же
 * логику против НАСТОЯЩЕГО движка (управляющий сокет ctl-serve хостовой сборки) и против
 * записанных ответов сети, и ни одна строка логики не знает, что она на стенде.
 */
package com.der.splify2.logic

/** Ответ управляющего сокета движка (steer/docs/ctl.md), разобранный оболочкой.
 *
 *  `code` — код возврата одноимённой подкоманды движка, `null`, если команда не исполнялась
 *  (отказ сервера: тогда заполнен `error`). `raw` — вся строка ответа как пришла: поля, которые
 *  знает только отдельная команда (`saved`, `applied`, `rolled_back` у `apply`), логика берёт
 *  оттуда сама, чтобы договор с оболочкой не рос с каждой новой командой. */
data class CtlReply(
    val code: Int?,
    val stdout: String,
    val stderr: String,
    val error: String?,
    val raw: String,
)

/** Движок — через управляющий сокет. Реализует оболочка.
 *
 *  `putFile` — команда сокета `put-file` (появится в движке следующей волной): положить файл
 *  списка или подписки под именем `name` в каталог списков движка. До того оболочка отвечает на
 *  неё отказом сервера `unknown-command`, и логика обязана это пережить (см. SpecPusher). */
interface Engine {
    fun check(spec: String): CtlReply
    fun apply(spec: String): CtlReply
    fun putFile(name: String, data: ByteArray): CtlReply
    fun status(): CtlReply
}

/** Ответ сети. `headers` — имена в любом регистре; логика сравнивает их без учёта регистра. */
data class HttpResult(val code: Int, val body: ByteArray, val headers: Map<String, String>) {
    // Автоматический equals у data class сравнивал бы ByteArray по ссылке, то есть «равны ли
    // два ответа» отвечало бы неправду. Форма класса — как в договоре с оболочкой, а сравнение
    // — по содержимому.
    override fun equals(other: Any?): Boolean =
        other is HttpResult && code == other.code && body.contentEquals(other.body) && headers == other.headers
    override fun hashCode(): Int = (code * 31 + body.contentHashCode()) * 31 + headers.hashCode()
    override fun toString() = "HttpResult(code=$code, body=${body.size} B, headers=$headers)"
}

/** Сеть. Реализует оболочка (или UrlHttp в Fetcher.kt — чистый java.net, для фоновой работы).
 *  Отказ соединения — исключение (IOException); любой пришедший ответ, включая 404, — HttpResult. */
interface Http {
    fun get(url: String, headers: Map<String, String>): HttpResult
}

/** Ошибка метода моста. `code` — из BRIDGE.md (bad-args, unknown-method, engine-down, engine,
 *  network, io, internal); `message` видит человек, поэтому он про состояние и действие, а не
 *  про устройство программы. */
class BridgeError(val code: String, message: String) : Exception(message)

/** Итог последнего `spec.apply` — для оболочки.
 *
 *  `needsLocalDns`: есть ли в спеке, которая сейчас у движка, доменные каналы на сам телефон.
 *  По нему оболочка выключает Private DNS (и возвращает прежний, когда таких каналов не
 *  осталось): DNS поверх TLS идёт на порт 853 мимо резолвера движка, и доменный канал телефона
 *  при включённом Private DNS не совпал бы ни с чем. Значение — про спеку, которую движок
 *  СОХРАНИЛ: если новую он отверг, в силе прежняя, и признак остаётся прежним. */
data class ApplyResult(
    val applied: Boolean,
    val saved: Boolean,
    val needsLocalDns: Boolean,
    val message: String? = null,
)

/** Что сказать панели подписки об устройстве (заголовки x-device-os, x-ver-os, x-device-model).
 *  По умолчанию — нейтральные значения: Dispatcher создаётся и на JVM-стенде, где Build нет;
 *  на телефоне значения подставляет Background/оболочка из android.os.Build. */
data class DeviceInfo(
    val os: String = "Android",
    val osVersion: String = "",
    val model: String = "",
)
