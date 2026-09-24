// Приложения телефона для каналов «на приложение» (apps.list) и их значки (/icon/<pkg>.png).
//
// Канал движка выбирает трафик по uid (`from: "uid:N"`), а не по имени пакета: ядро видит
// только uid сокета. Поэтому список сгруппирован по uid, а у приложений с общим uid
// (sharedUserId) все пакеты идут одной строкой в `shared` — выбрать из них одно нельзя, канал
// возьмёт их все, и экран должен это показать.
//
// Кого показываем:
//   - только uid приложений (от 10000): ниже — системные службы (system_server, radio,
//     bluetooth). Канал «весь телефон» (`from: "self"`) их тоже не берёт — у движка это uid
//     от 10000, — и по отдельности им в каналах делать нечего;
//   - без флага system — приложения, которые человек видит: несистемные и системные со
//     значком в лаунчере (браузер, магазин, YouTube из прошивки). Служебные системные пакеты
//     без лаунчера — только с system:true;
//   - себя не показываем: канал «на splify2» ничего полезного не делает.
//
// Видимость всех пакетов даёт QUERY_ALL_PACKAGES (с targetSdk 30 без неё PackageManager
// отвечает не про всех). Список — только пользователя, в котором работает приложение (0):
// сервер сокета пускает splify2 только у владельца устройства.
package com.der.splify2

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class AppList(private val ctx: Context) {

    private val pm: PackageManager = ctx.packageManager

    // Значки кэшируются готовыми PNG: список на экране прокручивается, и каждый показ строки —
    // запрос картинки. Предел — по байтам, около 4 МиБ (примерно 300 значков).
    private val icons = object : LruCache<String, ByteArray>(4 shl 20) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    fun list(includeSystem: Boolean): JSONArray {
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.ResolveInfoFlags.of(0),
        ).mapTo(HashSet()) { it.activityInfo.packageName }

        val byUid = HashMap<Int, MutableList<ApplicationInfo>>()
        for (ai in pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))) {
            if (ai.uid < Process.FIRST_APPLICATION_UID || ai.uid == Process.myUid()) continue
            byUid.getOrPut(ai.uid) { ArrayList() }.add(ai)
        }

        class Row(val uid: Int, val pkg: String, val label: String, val system: Boolean, val shared: List<String>)
        val rows = ArrayList<Row>()
        for ((uid, pkgs) in byUid) {
            val visible = pkgs.any { !isSystem(it) || it.packageName in launchable }
            if (!includeSystem && !visible) continue
            // Лицо строки — пакет, который человек узнает: со значком в лаунчере и
            // несистемный впереди, дальше по имени — чтобы порядок не зависел от PackageManager.
            val sorted = pkgs.sortedWith(
                compareBy<ApplicationInfo>(
                    { it.packageName !in launchable },
                    { isSystem(it) },
                    { it.packageName },
                ),
            )
            val main = sorted.first()
            rows.add(
                Row(
                    uid = uid,
                    pkg = main.packageName,
                    label = main.loadLabel(pm).toString().trim().ifEmpty { main.packageName },
                    system = isSystem(main),
                    shared = if (pkgs.size > 1) sorted.map { it.packageName } else emptyList(),
                ),
            )
        }
        rows.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

        val out = JSONArray()
        for (r in rows) {
            out.put(
                JSONObject()
                    .put("uid", r.uid)
                    .put("pkg", r.pkg)
                    .put("label", r.label)
                    .put("system", r.system)
                    .put("shared", JSONArray(r.shared)),
            )
        }
        return out
    }

    /** PNG значка пакета или null, если такого пакета нет. Размер — 48 dp в пикселях экрана. */
    fun iconPng(pkg: String): ByteArray? {
        icons.get(pkg)?.let { return it }
        val d = try {
            pm.getApplicationIcon(pkg)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val px = (48 * ctx.resources.displayMetrics.density).toInt().coerceIn(48, 192)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px)
        d.draw(Canvas(bmp))
        val png = ByteArrayOutputStream(8 * 1024).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        bmp.recycle()
        icons.put(pkg, png)
        return png
    }

    private fun isSystem(ai: ApplicationInfo) =
        ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
}
