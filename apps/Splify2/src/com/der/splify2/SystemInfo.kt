// system.network — текущая сеть телефона для главного экрана и события network.changed.
//
// Всё через ConnectivityManager: он знает сеть по умолчанию (ту, куда пойдёт трафик без
// каналов), её транспорт и лимитность. Имя сети:
//   - у сотовой — имя оператора (TelephonyManager, без разрешений);
//   - у Wi-Fi — SSID, если система его отдаёт. Без разрешения на местоположение
//     ConnectivityManager прячет SSID («<unknown ssid>»); просить местоположение ради подписи
//     на экране splify2 не будет — тогда имени просто нет (в договоре оно необязательное).
package com.der.splify2

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.telephony.TelephonyManager
import org.json.JSONObject

class SystemInfo(private val ctx: Context) {

    private val cm: ConnectivityManager = ctx.getSystemService(ConnectivityManager::class.java)

    fun network(): JSONObject = describe(cm.activeNetwork?.let { cm.getNetworkCapabilities(it) })

    /**
     * Описание по возможностям сети. Отдельной функцией — её зовёт и обратный вызов смены сети
     * с уже известными возможностями, не спрашивая ConnectivityManager второй раз.
     *
     * Тип: Wi-Fi, сотовая, Ethernet; прочее (Bluetooth, USB-модем) договор не различает — это
     * «none»: из трёх привычных сетей ни одной; metered при этом честный.
     * У VPN чужого приложения возможности включают транспорты нижней сети — тип берётся по ним.
     */
    fun describe(caps: NetworkCapabilities?): JSONObject {
        val o = JSONObject()
        if (caps == null) {
            return o.put("type", "none").put("metered", false)
        }
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "none"
        }
        o.put("type", type)
        when (type) {
            "wifi" -> (caps.transportInfo as? WifiInfo)?.ssid
                ?.removeSurrounding("\"")
                ?.takeIf { it.isNotEmpty() && it != WifiUnknownSsid }
                ?.let { o.put("name", it) }
            "cellular" -> ctx.getSystemService(TelephonyManager::class.java)
                ?.networkOperatorName
                ?.takeIf { it.isNotBlank() }
                ?.let { o.put("name", it) }
        }
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
        return o.put("metered", metered)
    }

    private companion object {
        // WifiManager.UNKNOWN_SSID без кавычек (в SDK константа скрыта).
        const val WifiUnknownSsid = "<unknown ssid>"
    }
}
