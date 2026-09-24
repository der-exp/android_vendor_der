/*
 * Скачивание: каталог, списки, наборы — с обходом закрытого GitHub.
 *
 * ЗАЧЕМ ОБХОД. splify2#15: у части аудитории провайдер закрыл raw.githubusercontent.com (и
 * вместе с ним релизы github.com), а каталог и сами списки лежат именно там. На роутере это
 * лечится двумя способами (fetch.sh): зеркалами того же репозитория и временным правилом «к
 * адресам издателя — через туннель». Второго на телефоне нет и не нужно: приложение splify2 —
 * обычный UID приложения, и если человек завёл правило «весь телефон/это приложение → туннель»,
 * его запросы и так идут туннелем. Правила «только на время скачивания» приложению ставить
 * нечем — и это правильно: движок не должен молча уводить трафик, которого никто не выбирал.
 *
 * Зеркала переносятся как есть, в том же порядке и по той же причине, что в fetch.sh: это копия
 * ТОГО ЖЕ репозитория у другого хозяина (gitlab.com) или тот же файл через api.github.com, а не
 * чужой прокси, которому пришлось бы доверить то, что станет правилами маршрутизации. Архив
 * через codeload (третий обход роутера) не переносится: для него нужен разбор tar, а два первых
 * покрывают те же репозитории.
 *
 * Релизный файл (`/releases/download/…`) на роутере берётся из ветки `dist` того же репозитория
 * — её ведёт сборка splify2-lists; то же правило здесь.
 */
package com.der.splify2.logic

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class Fetched(val body: ByteArray, val headers: Map<String, String>, val note: String?)

internal class Fetcher(private val http: Http) {
    companion object {
        const val GITLAB = "https://gitlab.com"
        const val API = "https://api.github.com/repos"
        const val DIST_BRANCH = "dist"
    }

    private fun direct(url: String, headers: Map<String, String>): HttpResult? = try {
        val r = http.get(url, headers)
        if (r.code in 200..299 && r.body.isNotEmpty()) r else null
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        // Реализация сети у оболочки может бросать не только IOException (например, отказ
        // SecurityException без сети). Для скачивания это одно и то же: «не отдали».
        null
    }

    /** Скачать. Отказ всех путей — BridgeError("network"). */
    fun get(url: String, headers: Map<String, String> = emptyMap()): Fetched {
        direct(url, headers)?.let { return Fetched(it.body, it.headers, null) }
        val gh = ghParts(url)
        if (gh != null) {
            val (repo, branch, path) = gh
            direct("$GITLAB/$repo/-/raw/$branch/$path", headers)?.let {
                return Fetched(it.body, it.headers, "прямой адрес не отдал — взято с зеркала gitlab.com")
            }
            direct("$API/$repo/contents/$path?ref=$branch", headers + ("Accept" to "application/vnd.github.raw"))?.let {
                return Fetched(it.body, it.headers, "прямой адрес не отдал — взято через api.github.com")
            }
        }
        throw BridgeError("network", "Не удалось скачать — проверьте подключение к интернету")
    }

    /** Репозиторий, ветка и путь файла на GitHub — или null, если адрес не GitHub.
     *  Та же разборка, что fetch_gh_parts в fetch.sh. */
    internal fun ghParts(url: String): Triple<String, String, String>? {
        val rel = Regex("^https://github\\.com/([^/]+/[^/]+)/releases/(?:latest/)?download/(?:[^/]+/)?([^/]+)$")
        rel.find(url)?.let {
            // У «latest/download/файл» тега в пути нет, у «download/тег/файл» он есть; в обоих
            // случаях файл берётся из ветки dist по имени — так её раскладывает сборка.
            return Triple(it.groupValues[1], DIST_BRANCH, it.groupValues[2])
        }
        if (!url.startsWith("https://raw.githubusercontent.com/")) return null
        var p = url.removePrefix("https://raw.githubusercontent.com/").substringBefore('?')
        val parts = p.split('/')
        if (parts.size < 4) return null
        val repo = parts[0] + "/" + parts[1]
        var rest = parts.drop(2)
        if (rest.size >= 3 && rest[0] == "refs" && rest[1] == "heads") rest = rest.drop(2)
        if (rest.size < 2) return null
        p = rest.drop(1).joinToString("/")
        if (parts[0].isEmpty() || parts[1].isEmpty() || rest[0].isEmpty() || p.isEmpty()) return null
        return Triple(repo, rest[0], p)
    }
}

/** Сеть на чистом java.net — для фоновой работы, где сети оболочки под рукой нет (Background).
 *
 *  Сроки те же, что у роутера (FETCH_CONNECT_TIMEOUT 8 с, FETCH_TIMEOUT 60 с): восемь секунд на
 *  соединение отличают «адрес закрыт» от «медленно» и не держат фоновую работу минутами. */
class UrlHttp(private val connectMs: Int = 8000, private val readMs: Int = 60000) : Http {
    override fun get(url: String, headers: Map<String, String>): HttpResult {
        var u = URL(url)
        // Перенаправления — руками: HttpURLConnection не переходит между http и https, а
        // релизы GitHub уводят на другой хост. Предел — как у curl в subfetch.c (10).
        repeat(10) {
            val c = u.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = connectMs
                c.readTimeout = readMs
                for ((k, v) in headers) c.setRequestProperty(k, v)
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return HttpResult(code, ByteArray(0), emptyMap())
                    u = URL(u, loc)
                    return@repeat
                }
                val h = LinkedHashMap<String, String>()
                for ((k, v) in c.headerFields) if (k != null && v != null && v.isNotEmpty()) h[k.lowercase()] = v.last()
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val body = stream?.use { it.readBytes() } ?: ByteArray(0)
                return HttpResult(code, body, h)
            } finally {
                c.disconnect()
            }
        }
        throw IOException("слишком много перенаправлений")
    }
}
