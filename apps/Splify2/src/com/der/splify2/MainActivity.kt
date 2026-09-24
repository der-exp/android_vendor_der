// Экран splify2: одна Activity с WebView, в котором — страница на @andromeda/ui (web/,
// собранное — assets/web/). Договор страницы и оболочки — BRIDGE.md.
//
// WebView в безопасной настройке, потому что это дверь к движку: страница, получившая мост,
// может менять маршрутизацию телефона. Поэтому:
//   - источник один — https://appassets.androidplatform.net (WebAssets), любой другой адрес
//     WebView получает 403, file:// и content:// выключены;
//   - навигация на чужой адрес не открывается в WebView, а уходит в браузер;
//   - мост отвечает только странице нашего источника: вызов с другой страницы (если бы она
//     как-то загрузилась) молча отбрасывается;
//   - отладка WebView — только в отлаживаемой сборке приложения.
//
// Мост: страница зовёт SplifyBridge.call(id, method, argsJson) — метод исполняется в пуле
// Shell, ответ приходит вызовом window.__splifyReply(id, replyJson) на главном потоке
// (evaluateJavascript можно звать только с него). События — window.__splifyEvent(name,
// payloadJson). И ответ, и данные события передаются СТРОКОЙ JSON, как и аргументы вызова:
// страница разбирает их сама (web/src/bridge.ts).
//
// Отступы под системные панели (edge-to-edge): страница рисуется под строкой состояния и
// панелью навигации, а их размеры получает CSS-переменными на <html>:
//   --splify-inset-top, --splify-inset-right, --splify-inset-bottom, --splify-inset-left
// (в CSS-пикселях, то есть dp). env(safe-area-inset-*) WebView до недавних версий не
// заполняет, поэтому переменные свои. Клавиатура не входит в --splify-inset-bottom: при
// открытой клавиатуре WebView просто становится ниже (нижний отступ окна), как было бы с
// adjustResize, и поле ввода страница прокручивает к себе сама.
package com.der.splify2

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.time.LocalDate

class MainActivity : Activity() {

    private lateinit var shell: Shell
    private lateinit var web: WebView
    private lateinit var assets: WebAssets
    private val main = Handler(Looper.getMainLooper())

    // Открыта ли сейчас страница нашего источника. Пишется на главном потоке (начало загрузки
    // страницы), читается потоком моста WebView — отсюда volatile.
    @Volatile private var ours = false
    private var insetsJs: String? = null

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastNetwork: String? = null

    private var backCallback: OnBackInvokedCallback? = null
    private val sink = EventSink { name, payload -> sendEvent(name, payload) }

    private var fileChooser: ValueCallback<Array<Uri>>? = null
    private val pendingSaves = HashMap<Int, PendingSave>()
    private val pendingPicks = HashMap<Int, PendingPick>()
    private var nextRequest = RC_SAVE_FIRST

    private class PendingSave(val id: String, val file: File, val result: String)
    private class PendingPick(val id: String, val args: JSONObject)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell = (application as Splify2App).shell
        assets = WebAssets(getAssets(), shell.apps)

        // Под системными панелями окно рисуется само: с targetSdk 35 edge-to-edge обязателен,
        // а платформенное приложение собирается с targetSdk платформы (36).
        web = WebView(this)
        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            applyInsets(insets)
            WindowInsets.CONSUMED
        }

        configureWebView()
        shell.events = sink
        web.loadUrl(WebAssets.START_URL)
    }

    private fun configureWebView() {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)

        with(web.settings) {
            javaScriptEnabled = true
            // localStorage страницы — для мелочей экрана (открытая вкладка); лежит в данных
            // приложения, чужим не видно.
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Safe Browsing сверяет адреса с Google; своих страниц нам проверять не у кого, а
            // лишние обращения наружу приложению маршрутизации ни к чему.
            safeBrowsingEnabled = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_NO_CACHE
            // Тёмную тему страница рисует сама по prefers-color-scheme (от DayNight-темы
            // приложения); алгоритмическое затемнение поверх испортило бы её цвета.
            isAlgorithmicDarkeningAllowed = false
            // Крупный шрифт системы: WebView сам его не учитывает.
            textZoom = (resources.configuration.fontScale * 100).toInt()
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(false)
            setAcceptThirdPartyCookies(web, false)
        }
        web.setBackgroundColor(getColor(R.color.page_background))
        web.addJavascriptInterface(JsBridge(), "SplifyBridge")
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
    }

    override fun onStart() {
        super.onStart()
        // Смену сети слушаем только пока экран виден (BRIDGE.md, «Батарея»): в фоне
        // приложение не держит даже обратного вызова ConnectivityManager.
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                networkChanged(caps)
            override fun onLost(network: Network) = networkChanged(null)
        }
        try {
            cm.registerDefaultNetworkCallback(cb, main)
            netCallback = cb
        } catch (e: RuntimeException) {
            Log.w(TAG, "обратный вызов сети не поставлен", e)
        }
    }

    override fun onStop() {
        netCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        netCallback = null
        super.onStop()
    }

    override fun onDestroy() {
        // Пересозданная Activity могла уже поставить свой приёмник — снимаем только свой.
        if (shell.events === sink) shell.events = null
        fileChooser?.onReceiveValue(null)
        fileChooser = null
        web.destroy()
        super.onDestroy()
    }

    /** Событие network.changed — только если описание сети и правда поменялось. */
    private fun networkChanged(caps: NetworkCapabilities?) {
        val now = shell.system.describe(caps).toString()
        if (now == lastNetwork) return
        val first = lastNetwork == null
        lastNetwork = now
        // Первый вызов приходит сразу после регистрации — это не смена, страница и так
        // спросит system.network при открытии.
        if (!first) sendEvent("network.changed", now)
    }

    // --- отступы -------------------------------------------------------------------------

    private fun applyInsets(insets: WindowInsets) {
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val ime = insets.getInsets(WindowInsets.Type.ime())
        val lp = web.layoutParams as FrameLayout.LayoutParams
        if (lp.bottomMargin != ime.bottom) {
            lp.bottomMargin = ime.bottom
            web.layoutParams = lp
        }
        val d = resources.displayMetrics.density
        fun px(v: Int) = "${(v / d).toInt()}px"
        val bottom = if (ime.bottom > 0) 0 else bars.bottom
        insetsJs = "(function(s){" +
            "s.setProperty('--splify-inset-top','${px(bars.top)}');" +
            "s.setProperty('--splify-inset-right','${px(bars.right)}');" +
            "s.setProperty('--splify-inset-bottom','${px(bottom)}');" +
            "s.setProperty('--splify-inset-left','${px(bars.left)}');" +
            "})(document.documentElement.style)"
        pushInsets()
    }

    private fun pushInsets() {
        val js = insetsJs ?: return
        if (ours) web.evaluateJavascript(js, null)
    }

    // --- мост ----------------------------------------------------------------------------

    private inner class JsBridge {
        /** Зовётся потоком моста WebView, не главным. Не блокирует: работа — в пуле Shell. */
        @JavascriptInterface
        fun call(id: String?, method: String?, argsJson: String?) {
            if (!ours || id == null || method == null) return
            if (method == "backup.export") {
                exportBackup(id, argsJson)
                return
            }
            if (method == "outputs.pickAwg") {
                val args = try {
                    JSONObject(argsJson?.takeIf { it.isNotBlank() && it != "null" } ?: "{}")
                } catch (_: Exception) {
                    deliver(id, errorReply(Shell.E_BAD_ARGS, "Неверные данные запроса"))
                    return
                }
                main.post { startPick(id, args) }
                return
            }
            shell.pool.execute { deliver(id, shell.reply(method, argsJson)) }
        }
    }

    private fun deliver(id: String, replyJson: String) {
        main.post {
            if (isDestroyed || !ours) return@post
            web.evaluateJavascript(
                "window.__splifyReply&&window.__splifyReply(${JSONObject.quote(id)},${JSONObject.quote(replyJson)})",
                null,
            )
        }
    }

    private fun sendEvent(name: String, payloadJson: String) {
        main.post {
            if (isDestroyed || !ours) return@post
            web.evaluateJavascript(
                "window.__splifyEvent&&window.__splifyEvent(${JSONObject.quote(name)},${JSONObject.quote(payloadJson)})",
                null,
            )
        }
    }

    private fun okReply(result: String) = "{\"ok\":true,\"result\":$result}"

    // --- резервная копия: «Сохранить как» ----------------------------------------------

    /**
     * backup.export: логика пишет JSON модели в свой файл и отвечает {"file","name","bytes"}; оболочка
     * отдаёт его системному «Сохранить как» (BRIDGE.md). Странице ответ приходит после того,
     * как человек выбрал место или закрыл окно: к результату логики добавляется saved.
     * Системное окно выбора файла — единственный путь наружу из данных приложения, который не
     * требует никаких разрешений на хранилище.
     */
    private fun exportBackup(id: String, argsJson: String?) {
        shell.pool.execute {
            val reply = shell.reply("backup.export", argsJson)
            val o = JSONObject(reply)
            if (!o.optBoolean("ok")) {
                deliver(id, reply)
                return@execute
            }
            val result = o.getJSONObject("result")
            val file = resolveOwnFile(result.optString("file"))
            if (file == null) {
                deliver(id, errorReply(Shell.E_IO, "Не удалось подготовить файл"))
                return@execute
            }
            main.post { startSave(id, file, result.toString()) }
        }
    }

    /** Файл логики — только внутри данных приложения: путь приходит строкой, проверяем. */
    private fun resolveOwnFile(path: String): File? {
        if (path.isEmpty()) return null
        val f = (if (path.startsWith("/")) File(path) else File(filesDir, path)).canonicalFile
        val roots = listOf(filesDir.canonicalPath + "/", cacheDir.canonicalPath + "/")
        return f.takeIf { ff -> ff.isFile && roots.any { ff.path.startsWith(it) } }
    }

    private fun startSave(id: String, file: File, result: String) {
        if (isDestroyed) return
        val rc = nextRequest++
        pendingSaves[rc] = PendingSave(id, file, result)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            // Имя — то, что дала логика (с датой и временем выгрузки); без него — своё с датой.
            .putExtra(Intent.EXTRA_TITLE, JSONObject(result).optString("name").ifEmpty {
                getString(R.string.backup_file_name, LocalDate.now().toString())
            })
        try {
            startActivityForResult(intent, rc)
        } catch (e: ActivityNotFoundException) {
            pendingSaves.remove(rc)
            deliver(id, errorReply(Shell.E_IO, "Нет приложения для сохранения файлов"))
        }
    }

    // --- файл WireGuard: «Открыть» -----------------------------------------------------

    /**
     * outputs.pickAwg {name?}: системное окно «Открыть» → текст файла → outputs.importAwg логики
     * (BRIDGE.md). Окно, а не разрешение на хранилище, по той же причине, что у «Сохранить как»:
     * ACTION_OPEN_DOCUMENT даёт доступ ровно к одному выбранному файлу и ничего не просит.
     *
     * Типы — не только текстовые: у расширения .conf в Android нет своего типа, и провайдер
     * документов отдаёт такой файл как application/octet-stream; с одними текстовыми окно показало
     * бы файл WireGuard серым. Ответ странице — результат importAwg с `picked: true` и именем
     * файла (из него экран предлагает имя выхода) или `{"picked": false}`, если окно закрыли.
     */
    private fun startPick(id: String, args: JSONObject) {
        if (isDestroyed) return
        val rc = nextRequest++
        pendingPicks[rc] = PendingPick(id, args)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "application/octet-stream"))
        try {
            startActivityForResult(intent, rc)
        } catch (e: ActivityNotFoundException) {
            pendingPicks.remove(rc)
            deliver(id, errorReply(Shell.E_IO, "Нет приложения для выбора файлов"))
        }
    }

    private fun finishPick(p: PendingPick, resultCode: Int, uri: Uri?) {
        if (resultCode != RESULT_OK || uri == null) {
            deliver(p.id, okReply("{\"picked\":false}"))
            return
        }
        shell.pool.execute {
            val reply = try {
                val bytes = contentResolver.openInputStream(uri)?.use { readCapped(it, AWG_MAX_BYTES + 1) }
                    ?: throw IOException("нет потока")
                if (bytes.size > AWG_MAX_BYTES) {
                    errorReply(Shell.E_BAD_ARGS, "Файл слишком большой для настроек WireGuard")
                } else {
                    val name = displayName(uri)
                    val args = JSONObject(p.args.toString()).put("text", String(bytes, Charsets.UTF_8))
                    val r = JSONObject(shell.reply("outputs.importAwg", args.toString()))
                    if (r.optBoolean("ok")) {
                        val res = r.getJSONObject("result").put("picked", true)
                        if (name != null) res.put("file", name)
                        okReply(res.toString())
                    } else r.toString()
                }
            } catch (e: Exception) {
                Log.w(TAG, "файл WireGuard не прочитан", e)
                errorReply(Shell.E_IO, "Не удалось прочитать файл")
            }
            deliver(p.id, reply)
        }
    }

    private fun readCapped(input: java.io.InputStream, cap: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < cap) {
            val n = input.read(buf, 0, minOf(buf.size, cap - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Имя файла, как его показывает провайдер документов; не сказал — null. */
    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == RC_FILE_CHOOSER) {
            fileChooser?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileChooser = null
            return
        }
        pendingPicks.remove(requestCode)?.let {
            finishPick(it, resultCode, data?.data)
            return
        }
        val p = pendingSaves.remove(requestCode) ?: return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            deliver(p.id, okReply(JSONObject(p.result).put("saved", false).toString()))
            return
        }
        shell.pool.execute {
            val reply = try {
                val out = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("нет потока")
                out.use { o -> FileInputStream(p.file).use { it.copyTo(o) } }
                okReply(JSONObject(p.result).put("saved", true).toString())
            } catch (e: Exception) {
                Log.w(TAG, "резервная копия не записана", e)
                errorReply(Shell.E_IO, "Не удалось сохранить файл")
            }
            deliver(p.id, reply)
        }
    }

    private fun errorReply(code: String, message: String) =
        JSONObject().put("ok", false).put("error", code).put("message", message).toString()

    // --- назад ---------------------------------------------------------------------------

    /**
     * Жест «назад»: пока у страницы есть куда вернуться — назад по её истории, иначе обработчик
     * снят и жест уходит системе (предиктивная анимация к рабочему столу).
     */
    private fun updateBack() {
        val need = web.canGoBack()
        val cb = backCallback
        if (need && cb == null) {
            val c = OnBackInvokedCallback { if (web.canGoBack()) web.goBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, c)
            backCallback = c
        } else if (!need && cb != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
            backCallback = null
        }
    }

    // --- WebView -------------------------------------------------------------------------

    private fun isOurs(url: String?): Boolean =
        url != null && (url == WebAssets.ORIGIN || url.startsWith(WebAssets.ORIGIN + "/"))

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
            assets.handle(request)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (isOurs(url.toString())) return false
            // Наружу — только обычные ссылки и почта, и только по действию человека.
            if (url.scheme in setOf("https", "http", "mailto") && request.hasGesture()) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: ActivityNotFoundException) {
                }
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            ours = isOurs(url)
        }

        override fun onPageCommitVisible(view: WebView, url: String?) = pushInsets()

        override fun onPageFinished(view: WebView, url: String?) = pushInsets()

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = updateBack()

        /**
         * Процесс отрисовки WebView убит (память, сбой). WebView после этого непригоден;
         * пересоздаём Activity — страница загрузится заново, работа в пуле Shell не страдает.
         */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "процесс отрисовки WebView завершился (сбой: ${detail.didCrash()})")
            ours = false
            recreate()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        /** Выбор файла для <input type=file> (восстановление из резервной копии). */
        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            fileChooser?.onReceiveValue(null)
            fileChooser = callback
            return try {
                startActivityForResult(params.createIntent(), RC_FILE_CHOOSER)
                true
            } catch (_: ActivityNotFoundException) {
                fileChooser = null
                false
            }
        }
    }

    private companion object {
        const val TAG = "splify2"
        const val RC_FILE_CHOOSER = 1
        const val RC_SAVE_FIRST = 100
        /** Предел логики и движка: больше законный файл WireGuard не бывает. */
        const val AWG_MAX_BYTES = com.der.splify2.logic.AwgConf.MAX_BYTES
    }
}
