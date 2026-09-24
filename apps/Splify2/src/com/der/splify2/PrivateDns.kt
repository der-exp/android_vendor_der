// Оркестрация Private DNS — решение владельца (ANDROID_AGENT_TASK.md §6, BRIDGE.md).
//
// Зачем. Доменные каналы на сам телефон работают так: движок заворачивает DNS приложений
// (UDP/53) на свой резолвер, тот узнаёт имя и кладёт адрес в набор канала. Private DNS (DoT,
// порт 853) идёт мимо: запрос зашифрован, резолвер его не видит, и канал «youtube.com → выход»
// молча перестаёт срабатывать. Поэтому, пока в применённой спеке есть доменные каналы
// телефона и движок включён, Private DNS должен быть выключен.
//
// Почему в приложении, а не в прошивке. Значения по умолчанию для private_dns_default_mode в
// SettingsProvider AOSP и Lineage нет — задать его прошивкой можно только форком
// frameworks/base. splify2 — платформенное приложение с WRITE_SECURE_SETTINGS, ему это можно
// сделать настройкой.
//
// Что делается:
//   1. при первом запуске private_dns_default_mode = off. Это значение, которым система
//      пользуется, пока человек сам не выбрал режим: без него Android включает
//      «автоматический» DoT сам, чего владелец не хочет («не включаться самому»);
//   2. когда доменные каналы телефона появляются (и движок включён) — запоминаем режим
//      человека (private_dns_mode как есть, в том числе «не задан») и ставим off;
//   3. когда они пропадают (или движок выключают) — возвращаем запомненное.
//
// Решения по краям, которых в брифе нет:
//   - смотрим на ПЕРЕХОДЫ: «нужно» стало true — забрали, стало false — вернули. Если человек,
//     пока мы держим off, сам включил Private DNS в настройках, мы его не перебиваем при
//     следующем применении спеки — экран показывает это состоянием (mode не off при
//     managed); а при возврате не затираем его новый выбор своим запомненным;
//   - адрес сервера (private_dns_specifier) не трогаем вовсе: выключает DoT режим, а адрес
//     человека остаётся на месте и сам вернётся с режимом hostname;
//   - состояние (держим ли, что было у человека) — в SharedPreferences приложения: переживает
//     перезапуск и перезагрузку, а стирание данных приложения — это и так «начать сначала».
package com.der.splify2

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemProperties
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

class PrivateDns(ctx: Context, private val prefs: SharedPreferences) {

    private val cr: ContentResolver = ctx.contentResolver

    /** Шаг 1: один раз за жизнь данных приложения. */
    @Synchronized
    fun ensureDefaultOff() {
        if (prefs.getBoolean(K_DEFAULT_SET, false)) return
        if (put(DEFAULT_MODE, OFF)) prefs.edit().putBoolean(K_DEFAULT_SET, true).apply()
    }

    /** После spec.apply, спека которого встала на место. */
    @Synchronized
    fun onSpecApplied(needsLocalDns: Boolean, engineEnabled: Boolean) {
        prefs.edit().putBoolean(K_NEEDS, needsLocalDns).apply()
        reconcile(engineEnabled)
    }

    /** Сверить с тем, что нужно сейчас. Зовётся после apply, переключения и при запуске. */
    @Synchronized
    fun reconcile(engineEnabled: Boolean = SystemProperties.get(Shell.PROP_ENABLED) == "1") {
        val want = engineEnabled && prefs.getBoolean(K_NEEDS, false)
        val managed = prefs.getBoolean(K_MANAGED, false)
        if (want && !managed) takeOver() else if (!want && managed) giveBack()
    }

    /** system.privateDns: `{"mode","host"?,"managed","saved"?:{mode,host?}}`. */
    @Synchronized
    fun state(): JSONObject {
        val mode = effectiveMode()
        val o = JSONObject().put("mode", mode)
        if (mode == HOSTNAME) Settings.Global.getString(cr, SPECIFIER)?.let { o.put("host", it) }
        val managed = prefs.getBoolean(K_MANAGED, false)
        o.put("managed", managed)
        if (managed) {
            val saved = JSONObject().put("mode", prefs.getString(K_SAVED_EFFECTIVE, OPPORTUNISTIC))
            prefs.getString(K_SAVED_HOST, null)?.let { saved.put("host", it) }
            o.put("saved", saved)
        }
        return o
    }

    private fun takeOver() {
        val raw = Settings.Global.getString(cr, MODE)
        val effective = effectiveMode()
        val host = Settings.Global.getString(cr, SPECIFIER)
        // Сначала запоминаем, потом выключаем: упади запись настройки — нечего будет и
        // возвращать, а запомненное без managed ни на что не влияет.
        prefs.edit()
            .putBoolean(K_SAVED_UNSET, raw == null)
            .putString(K_SAVED_RAW, raw)
            .putString(K_SAVED_EFFECTIVE, effective)
            .putString(K_SAVED_HOST, if (effective == HOSTNAME) host else null)
            .commit()
        if (put(MODE, OFF)) prefs.edit().putBoolean(K_MANAGED, true).apply()
    }

    private fun giveBack() {
        val now = Settings.Global.getString(cr, MODE)
        // Человек сам сменил режим, пока мы держали off, — его новый выбор главнее запомненного.
        val restore = now == null || now == OFF
        val ok = if (!restore) {
            true
        } else if (prefs.getBoolean(K_SAVED_UNSET, false)) {
            put(MODE, null)
        } else {
            put(MODE, prefs.getString(K_SAVED_RAW, null))
        }
        if (ok) {
            prefs.edit()
                .putBoolean(K_MANAGED, false)
                .remove(K_SAVED_UNSET).remove(K_SAVED_RAW).remove(K_SAVED_EFFECTIVE).remove(K_SAVED_HOST)
                .apply()
        }
    }

    /** Режим, которым система пользуется на деле: свой человека, иначе по умолчанию. */
    private fun effectiveMode(): String =
        Settings.Global.getString(cr, MODE)
            ?: Settings.Global.getString(cr, DEFAULT_MODE)
            ?: OPPORTUNISTIC

    private fun put(key: String, value: String?): Boolean = try {
        Settings.Global.putString(cr, key, value)
    } catch (e: SecurityException) {
        // Без WRITE_SECURE_SETTINGS (сборка не на платформенной подписи) — оставляем как есть.
        Log.w(TAG, "нет права писать $key", e)
        false
    }

    companion object {
        private const val TAG = "splify2.dns"

        // Имена настроек — Settings.Global.PRIVATE_DNS_* в платформе; константы там скрыты от
        // SDK, строки — часть договора SettingsProvider и не меняются между версиями.
        private const val MODE = "private_dns_mode"
        private const val SPECIFIER = "private_dns_specifier"
        private const val DEFAULT_MODE = "private_dns_default_mode"
        private const val OFF = "off"
        private const val OPPORTUNISTIC = "opportunistic"
        private const val HOSTNAME = "hostname"

        private const val K_DEFAULT_SET = "dns.default_set"
        private const val K_NEEDS = "dns.needs_local"
        private const val K_MANAGED = "dns.managed"
        private const val K_SAVED_UNSET = "dns.saved_unset"
        private const val K_SAVED_RAW = "dns.saved_raw"
        private const val K_SAVED_EFFECTIVE = "dns.saved_effective"
        private const val K_SAVED_HOST = "dns.saved_host"
    }
}
