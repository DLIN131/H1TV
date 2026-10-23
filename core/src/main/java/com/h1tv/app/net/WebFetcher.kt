package com.h1tv.app.net

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.h1tv.app.App
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** 被 Cloudflare 擋下，需要使用者在可見的 WebView 裡手動過驗證 */
class BlockedException : Exception("被 Cloudflare 擋下了，請開啟驗證頁面完成驗證")

/**
 * 網站有 Cloudflare，OkHttp 直接抓會被擋（403）。
 * 這裡用一個隱藏的 WebView（真正的 Chromium）先打開首頁過驗證，
 * 之後所有頁面都在它裡面用 fetch() 抓 HTML，透過 JS bridge 傳回來。
 * 影片和縮圖在另一個 CDN，不受影響，直接用 ExoPlayer／Coil 抓。
 */
object WebFetcher {
    val BASE: String = decode("aHR0cHM6Ly9oYW5pbWUxLm1l")

    private var web: WebView? = null
    private val bootLock = Mutex()
    @Volatile private var ready = false
    private val seq = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Pair<Int, String>>>()

    /** WebView 預設 UA 帶有 "; wv"，有些防火牆會針對它，改成一般手機 Chrome 的樣子 */
    val userAgent: String by lazy {
        WebSettings.getDefaultUserAgent(App.instance)
            .replace("; wv", "")
            .replace(Regex("Version/\\S+ "), "")
    }

    private class Bridge {
        @JavascriptInterface
        fun done(id: Int, status: Int, body: String) {
            pending.remove(id)?.complete(status to body)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun webView(): WebView = web ?: WebView(App.instance).also { w ->
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = userAgent
            // 只拿 HTML，不需要載圖，省電視盒子的效能
            loadsImagesAutomatically = false
            blockNetworkImage = true
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = WebViewClient()
        w.addJavascriptInterface(Bridge(), "H1Bridge")
        w.resumeTimers()
        web = w
    }

    /** 使用者在驗證頁過完驗證後呼叫，下次請求重新暖機 */
    fun reset() {
        ready = false
    }

    suspend fun get(path: String): String {
        val url = if (path.startsWith("http")) path else BASE + path
        repeat(2) { attempt ->
            ensureReady()
            val (status, body) = fetch(url)
            if (!isChallenge(status, body)) {
                if (status !in 200..299) throw Exception("載入失敗（HTTP $status）")
                return body
            }
            // cookie 過期，重新跑一次驗證
            ready = false
            if (attempt == 1) throw BlockedException()
        }
        throw BlockedException()
    }

    private fun isChallenge(status: Int, body: String) =
        (status == 403 || status == 503) &&
            (body.contains("cf-") || body.contains("challenge-platform") || body.contains("Cloudflare"))

    private suspend fun ensureReady() {
        if (ready) return
        bootLock.withLock {
            if (ready) return
            withContext(Dispatchers.Main) {
                val w = webView()
                w.loadUrl(BASE)
                // 等驗證頁自己跳轉到正常首頁，最多 30 秒
                val ok = withTimeoutOrNull(30_000) {
                    delay(1500)
                    while (true) {
                        val state = w.eval("document.readyState+'|'+document.title+'|'+!!document.querySelector('a[href*=\"watch?v=\"]')")
                        if (state.startsWith("complete") && state.endsWith("|true")) break
                        if (state.contains("Attention Required")) return@withTimeoutOrNull false
                        delay(700)
                    }
                    true
                } == true
                if (!ok) {
                    w.stopLoading()
                    throw BlockedException()
                }
                CookieManager.getInstance().flush()
            }
            ready = true
        }
    }

    private suspend fun fetch(url: String): Pair<Int, String> {
        val id = seq.incrementAndGet()
        val result = CompletableDeferred<Pair<Int, String>>()
        pending[id] = result
        val js = """
            fetch(${JSONObject.quote(url)}, {credentials: 'include'})
              .then(function (r) { return r.text().then(function (t) { H1Bridge.done($id, r.status, t); }); })
              .catch(function (e) { H1Bridge.done($id, -1, String(e)); });
        """.trimIndent()
        withContext(Dispatchers.Main) { webView().evaluateJavascript(js, null) }
        return try {
            val r = withTimeout(30_000) { result.await() }
            if (r.first == -1) throw Exception("網路錯誤：${r.second}")
            r
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun WebView.eval(js: String): String = suspendCancellableCoroutine { cont ->
        evaluateJavascript(js) { raw ->
            // 回傳值是 JSON 字串
            val s = runCatching { JSONObject("{\"v\":$raw}").optString("v") }.getOrDefault("")
            if (cont.isActive) cont.resume(s)
        }
    }
}

/** 網址等設定以 Base64 存放，原始碼裡不出現明文 */
internal fun decode(s: String): String = String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)
