// ВРЕМЕННАЯ заглушка пакета logic — только для проверочной сборки оболочки вне дерева
// (tools/app-check/build.sh). В Android.bp она не входит (там srcs: src/**/*.kt), в APK
// прошивки не попадает; настоящий пакет пишется в src/com/der/splify2/logic/.
//
// Здесь ровно те объявления, о которых договорились оболочка и логика: оболочка реализует
// Engine (поверх EngineClient) и Http (HttpURLConnection), зовёт Dispatcher.call и
// Background.runDaily и ловит BridgeError. Как только настоящий пакет готов, build.sh берёт его
// вместо этого каталога сам (см. там), а каталог можно удалить.
//
// HttpResult в договоре назван, но не расписан: здесь — код ответа, заголовки (имя в нижнем
// регистре → значение) и тело. Если у логики он другой, поправить надо HttpClient.kt.
package com.der.splify2.logic

import java.io.File

data class CtlReply(
    val code: Int?,
    val stdout: String,
    val stderr: String,
    val error: String?,
    val raw: String,
)

interface Engine {
    fun check(spec: String): CtlReply
    fun apply(spec: String): CtlReply
    fun putFile(name: String, data: ByteArray): CtlReply
    fun status(): CtlReply
}

class HttpResult(val code: Int, val headers: Map<String, String>, val body: ByteArray)

interface Http {
    fun get(url: String, headers: Map<String, String>): HttpResult
}

class BridgeError(val code: String, message: String) : Exception(message)

@Suppress("UNUSED_PARAMETER")
class Dispatcher(filesDir: File, engine: Engine, http: Http) {
    fun call(method: String, argsJson: String): String =
        throw BridgeError("unknown-method", method)
}

object Background {
    @Suppress("UNUSED_PARAMETER")
    fun runDaily(ctx: android.content.Context) {}
}
