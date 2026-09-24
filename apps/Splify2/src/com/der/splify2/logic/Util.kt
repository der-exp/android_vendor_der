/*
 * Мелкие помощники логики: файлы, JSON, хеши. Отдельным файлом, потому что пользуются ими все
 * остальные, а держать копию в каждом значило бы однажды записать файл в одном месте атомарно,
 * а в другом — нет.
 */
package com.der.splify2.logic

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal object Files {
    /** Запись целиком: сначала рядом, потом переименование.
     *
     *  Так же, как на роутере (`mv` после скачивания во временный файл): оборванная запись —
     *  нет места, убили процесс — иначе оставляет на месте рабочего файла обрубок, и модель
     *  настроек или список канала читались бы наполовину. rename внутри одного каталога
     *  атомарен, поэтому снаружи виден либо прежний файл, либо новый. */
    fun write(f: File, data: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        try {
            tmp.outputStream().use { it.write(data); it.fd.sync() }
            if (!tmp.renameTo(f)) {
                // На некоторых ФС renameTo поверх существующего отказывает; тогда явное
                // удаление и повтор. Окно между ними — единственная уступка, и она меньше,
                // чем запись на месте.
                f.delete()
                if (!tmp.renameTo(f)) throw BridgeError("io", "Не удалось сохранить данные приложения — проверьте свободное место")
            }
        } catch (e: BridgeError) {
            tmp.delete(); throw e
        } catch (e: Exception) {
            tmp.delete()
            throw BridgeError("io", "Не удалось сохранить данные приложения — проверьте свободное место")
        }
    }

    fun writeText(f: File, s: String) = write(f, s.toByteArray(Charsets.UTF_8))

    fun readText(f: File): String? = if (f.isFile) f.readText(Charsets.UTF_8) else null

    fun readJson(f: File): JSONObject? = try {
        readText(f)?.let { JSONObject(it) }
    } catch (e: Exception) {
        null
    }
}

internal fun sha256hex(b: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

internal fun nowSec(): Long = System.currentTimeMillis() / 1000

/** Строки массива JSON; не строки пропускаются (модель от экрана — ввод, а не доверенный файл). */
internal fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) {
        val v = opt(i)
        if (v is String) out.add(v)
    }
    return out
}

internal fun jsonArrayOf(items: Collection<Any?>): JSONArray {
    val a = JSONArray()
    for (x in items) a.put(x)
    return a
}

internal fun JSONObject.keyList(): List<String> {
    val out = ArrayList<String>()
    val it = keys()
    while (it.hasNext()) out.add(it.next())
    return out
}

/** Строка из JSON, где null и отсутствие значат одно — «нет». optString отдал бы "null" на
 *  JSONObject.NULL, а это уже непустая строка. */
internal fun JSONObject.str(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)
    return if (v is String) v else null
}

/** Обрезать строку до n байт UTF-8, не разрывая кодовую точку.
 *
 *  Нужна именам каналов: в спеке движок держит имя в 32-байтовом поле и на более длинном
 *  отказывает всей спеке (spec.c, js_str: «строка длиннее допустимого»), а русское имя в 16
 *  букв — это уже 32 байта. Резать по байтам нельзя: половина буквы в имени канала уезжает в
 *  JSON `status` битым UTF-8. */
internal fun utf8Cut(s: String, n: Int): String {
    val b = s.toByteArray(Charsets.UTF_8)
    if (b.size <= n) return s
    var end = n
    // Отступить с байта-продолжения (10xxxxxx) до начала кодовой точки.
    while (end > 0 && (b[end].toInt() and 0xC0) == 0x80) end--
    return String(b, 0, end, Charsets.UTF_8)
}
