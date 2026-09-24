// Источник страницы: https://appassets.androidplatform.net/ из shouldInterceptRequest.
//
// Это то же, что делает WebViewAssetLoader из androidx, только своим кодом: androidx в
// приложении нет (Android.bp). Адрес https://appassets.androidplatform.net — зарезервированное
// Android имя именно для такой подмены: в сеть запрос не уходит, WebView отдаёт наш ответ.
// Зачем https-адрес, а не file:///android_asset: у file:// нет нормального происхождения
// (origin) — модули ES, fetch и localStorage ведут себя на нём по-особому, а доступ к файлам
// из страницы — лишняя дверь. С https-адресом страница — обычный сайт с одним происхождением,
// и file:// в WebView выключен совсем.
//
// Что отдаётся:
//   /web/<путь>          — файлы из assets/web/ (собранный экран, BRIDGE.md); путь без
//                          расширения, которого нет, — index.html (маршруты страницы);
//   /icon/<пакет>.png    — значок приложения телефона (AppList.iconPng);
//   всё прочее на этом имени — 404, на любом другом имени — 403: страница не ходит никуда,
//   кроме себя (всё внешнее идёт через мост), и это держит не только CSP, но и сам WebView.
package com.der.splify2

import android.content.res.AssetManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

class WebAssets(private val assets: AssetManager, private val apps: AppList) {

    fun handle(req: WebResourceRequest): WebResourceResponse {
        val u = req.url
        if (u.scheme != "https" || u.host != HOST || (u.port != -1 && u.port != 443)) {
            return status(403, "Forbidden")
        }
        if (req.method != "GET" && req.method != "HEAD") return status(405, "Method Not Allowed")
        val path = u.path ?: "/"
        return when {
            path.startsWith(WEB_PREFIX) -> page(path.removePrefix(WEB_PREFIX))
            path.startsWith(ICON_PREFIX) && path.endsWith(".png") ->
                icon(path.removePrefix(ICON_PREFIX).removeSuffix(".png"))
            else -> status(404, "Not Found")
        }
    }

    private fun page(rel: String): WebResourceResponse {
        // Путь уже раскодирован (Uri.getPath). «..» и пустые куски отвергаем сами, хотя
        // AssetManager за пределы assets/ и так не выпустит: так ответ предсказуем.
        val segs = rel.split('/')
        if (segs.any { it == ".." || it == "." || it.contains('\\') }) return status(404, "Not Found")
        val clean = segs.filter { it.isNotEmpty() }.joinToString("/")
        var file = clean.ifEmpty { "index.html" }
        var stream = open("web/$file")
        if (stream == null && !file.substringAfterLast('/').contains('.')) {
            file = "index.html"
            stream = open("web/$file")
        }
        if (stream == null) return status(404, "Not Found")
        return ok(mime(file), stream, headersFor(file))
    }

    private fun open(path: String): InputStream? = try {
        assets.open(path, AssetManager.ACCESS_STREAMING)
    } catch (_: FileNotFoundException) {
        null
    } catch (_: IOException) {
        null
    }

    private fun icon(pkg: String): WebResourceResponse {
        if (!PKG.matches(pkg)) return status(404, "Not Found")
        val png = apps.iconPng(pkg) ?: return status(404, "Not Found")
        return ok("image/png", ByteArrayInputStream(png), mapOf("Cache-Control" to "max-age=3600"))
    }

    private fun headersFor(file: String): Map<String, String> {
        val h = HashMap<String, String>()
        h["X-Content-Type-Options"] = "nosniff"
        // Ассеты меняются только с обновлением APK; no-cache — чтобы после обновления
        // прошивки WebView не показал старый экран из своего кэша.
        h["Cache-Control"] = "no-cache"
        if (file.endsWith(".html")) h["Content-Security-Policy"] = CSP
        return h
    }

    private fun ok(mime: String, body: InputStream, headers: Map<String, String>) =
        WebResourceResponse(mime, if (mime.startsWith("text/") || mime.endsWith("json")) "utf-8" else null,
            200, "OK", headers, body)

    private fun status(code: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", code, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$HOST"
        const val START_URL = "$ORIGIN/web/index.html"
        private const val WEB_PREFIX = "/web/"
        private const val ICON_PREFIX = "/icon/"
        private val PKG = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

        // Политика содержимого страницы: только своё происхождение. style-src с
        // 'unsafe-inline' — React и @andromeda/ui ставят стили атрибутом style; скриптам
        // встроенного кода не нужно (сборка экрана кладёт их файлами). data:/blob: у картинок —
        // для значков и графиков, которые страница рисует сама.
        private const val CSP = "default-src 'self'; script-src 'self'; " +
            "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; " +
            "connect-src 'self'; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'"

        private fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json", "map" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "ico" -> "image/x-icon"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "wasm" -> "application/wasm"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
