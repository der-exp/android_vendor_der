// Скачивание для логики: logic.Http поверх HttpURLConnection.
//
// Логика качает каталог списков, сами списки и подписки. Своего HTTP-клиента (OkHttp и т.п.)
// в приложении нет нарочно: HttpURLConnection платформы — это и есть OkHttp внутри, со
// сжатием gzip, пулом соединений и системным хранилищем корней, и он не добавляет в APK ни
// байта. Cleartext HTTP запрещён политикой сети по умолчанию (targetSdk 28+), и это оставлено:
// в ссылке подписки — ключ доступа, по http он ушёл бы открытым текстом. Адрес http:// даст
// ошибку network.
//
// Отказ связи — IOException, как записано в договоре (logic/Contract.kt): логика ловит его и
// пробует зеркало, а наверх отдаёт уже свою BridgeError("network") с фразой для человека.
// Ответ с кодом 4xx/5xx — не ошибка связи, а HttpResult с этим кодом: что значит 404 у
// подписки или 304 у каталога, решает логика.
package com.der.splify2

import android.content.Context
import com.der.splify2.logic.Http
import com.der.splify2.logic.HttpResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class HttpClient(ctx: Context) : Http {

    // Имя и версия в User-Agent: издатель списков видит, какой клиент к нему ходит, — как
    // и роутерный splify2, который представляется своим именем.
    private val userAgent: String = run {
        val v = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
        "splify2-android/${v ?: "0"}"
    }

    override fun get(url: String, headers: Map<String, String>): HttpResult {
        val u = URL(url)
        if (u.protocol != "https" && u.protocol != "http") throw IOException("не http(s): ${u.protocol}")
        val c = u.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            c.instanceFollowRedirects = true
            c.useCaches = false
            c.setRequestProperty("User-Agent", userAgent)
            for ((k, v) in headers) c.setRequestProperty(k, v)

            val code = c.responseCode
            val body = (if (code >= 400) c.errorStream else c.inputStream)?.use { read(it) } ?: ByteArray(0)
            val h = HashMap<String, String>()
            for ((k, v) in c.headerFields) {
                if (k != null && v.isNotEmpty()) h[k.lowercase()] = v.first()
            }
            return HttpResult(code = code, body = body, headers = h)
        } finally {
            c.disconnect()
        }
    }

    /** Тело до MAX_BODY: список или подписка крупнее — ошибка, а не память процесса. */
    private fun read(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BODY) throw IOException("ответ больше ${MAX_BODY shr 20} МиБ")
        }
        return out.toByteArray()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_BODY = 32 shl 20
    }
}
