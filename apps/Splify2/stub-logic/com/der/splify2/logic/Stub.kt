// ВРЕМЕННАЯ заглушка пакета logic — только для проверочной сборки оболочки вне дерева
// (tools/app-check/build.sh). В Android.bp она не входит (там srcs: src/**/*.kt), в APK
// прошивки не попадает; настоящий пакет пишется в src/com/der/splify2/logic/.
//
// Здесь ровно те объявления, которыми пользуется оболочка, в той форме, в какой их пишет логика
// (logic/Contract.kt, Dispatcher.kt, Background.kt): оболочка реализует Engine (поверх
// EngineClient) и Http (HttpURLConnection), создаёт Dispatcher и зовёт его call, берёт итог
// последнего apply (lastApply — для Private DNS), принимает события (onEvent), подставляет свои
// Engine и Http фоновой работе (Background.engineFactory/httpFactory) и ловит BridgeError. Как
// только настоящий пакет готов, build.sh берёт его вместо этого каталога сам, а каталог можно
// удалить.
package com.der.splify2.logic

import android.content.Context
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

class HttpResult(val code: Int, val body: ByteArray, val headers: Map<String, String>)

interface Http {
    fun get(url: String, headers: Map<String, String>): HttpResult
}

class BridgeError(val code: String, message: String) : Exception(message)

data class ApplyResult(
    val applied: Boolean,
    val saved: Boolean,
    val needsLocalDns: Boolean,
    val message: String? = null,
)

data class DeviceInfo(
    val os: String = "Android",
    val osVersion: String = "",
    val model: String = "",
)

@Suppress("UNUSED_PARAMETER")
class Dispatcher(filesDir: File, engine: Engine, http: Http, device: DeviceInfo = DeviceInfo()) {
    @Volatile
    var lastApply: ApplyResult? = null
        private set

    @Volatile
    var onEvent: ((name: String, payloadJson: String) -> Unit)? = null

    fun call(method: String, argsJson: String): String =
        throw BridgeError("unknown-method", method)
}

object Background {
    @Volatile
    var engineFactory: ((Context) -> Engine)? = null

    @Volatile
    var httpFactory: ((Context) -> Http)? = null

    @Suppress("UNUSED_PARAMETER")
    fun runDaily(ctx: Context) {}
}
