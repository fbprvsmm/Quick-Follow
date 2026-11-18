package com.quickfollow.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var mainWebView: WebView
    private var multiBrowserDialog: MultiBrowserPopupDialog? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mainWebView = WebView(this)
        setContentView(mainWebView)

        val settings: WebSettings = mainWebView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.userAgentString = settings.userAgentString.replace("; wv", "")

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(mainWebView, true)

        mainWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }
        mainWebView.webChromeClient = WebChromeClient()
        mainWebView.addJavascriptInterface(QuickFollowBridge(), "QuickFollowAndroid")
        mainWebView.loadUrl("https://ais-pre-ggxqiguktgyb2llvt3ihkz-842361416819.asia-east1.run.app")
    }

    inner class QuickFollowBridge {
        @JavascriptInterface
        fun isAndroidApp(): Boolean = true

        @JavascriptInterface
        fun openIgBrowser(slotIndex: Int) {
            runOnUiThread {
                val safeSlot = slotIndex.coerceIn(1, 5)
                if (multiBrowserDialog == null) {
                    multiBrowserDialog = MultiBrowserPopupDialog(
                        this@MainActivity,
                        onSessionCaptured = { slot, username, sessionId, csrfToken, dsUserId ->
                            val safeUser = username.replace("'", "")
                            val safeSid = sessionId.replace("'", "")
                            val safeCsrf = csrfToken.replace("'", "")
                            val safeDs = dsUserId.replace("'", "")
                            val js = "window.onAndroidIgSessionSaved && window.onAndroidIgSessionSaved(" +
                                "$slot, '$safeUser', '$safeSid', '$safeCsrf', '$safeDs');"
                            mainWebView.post { mainWebView.evaluateJavascript(js, null) }
                        }
                    )
                }
                multiBrowserDialog?.showSlot(safeSlot)
            }
        }

        @JavascriptInterface
        fun followNative(
            slotIndex: Int,
            targetId: String,
            targetUsername: String,
            sessionId: String,
            csrfToken: String,
            dsUserId: String,
            callbackId: String
        ) {
            Thread {
                var ok = false
                var msg = "Follow failed"
                var resolvedId = targetId.trim()
                var activeCsrf = if (csrfToken == "verified_csrf" || csrfToken == "csrf_auto_gen") "" else csrfToken.trim()
                try {
                    val savedRaw = getSharedPreferences("qf_ig_slots", Context.MODE_PRIVATE)
                        .getString("slot_$slotIndex", null) ?: ""
                    val cookieBuilder = StringBuilder()
                    if (savedRaw.contains("sessionid=")) {
                        cookieBuilder.append(savedRaw)
                    } else {
                        cookieBuilder.append("sessionid=").append(sessionId)
                        if (dsUserId.isNotBlank()) cookieBuilder.append("; ds_user_id=").append(dsUserId)
                    }
                    if (activeCsrf.isBlank()) {
                        val bootConn = (java.net.URL("https://www.instagram.com/accounts/login/").openConnection() as java.net.HttpURLConnection).apply {
                            requestMethod = "GET"
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                            setRequestProperty("Cookie", "sessionid=$sessionId")
                            connectTimeout = 15000
                            readTimeout = 15000
                        }
                        val setCookies = bootConn.headerFields["Set-Cookie"] ?: emptyList()
                        for (sc in setCookies) {
                            if (sc.startsWith("csrftoken=")) {
                                activeCsrf = sc.substringAfter("csrftoken=").substringBefore(";")
                            }
                        }
                        bootConn.disconnect()
                    }
                    if (activeCsrf.isNotBlank() && !cookieBuilder.toString().contains("csrftoken=")) {
                        cookieBuilder.append("; csrftoken=").append(activeCsrf)
                    }
                    val fullCookie = cookieBuilder.toString()

                    if (resolvedId.isBlank() && targetUsername.isNotBlank()) {
                        val cleanHandle = targetUsername.trim().removePrefix("@").lowercase()
                        val encHandle = java.net.URLEncoder.encode(cleanHandle, "UTF-8")
                        val webUa = "Mozilla/5.0 (Linux; Android 13; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

                        fun openIgGet(urlStr: String, referer: String): Pair<Int, String> {
                            val c = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
                                requestMethod = "GET"
                                instanceFollowRedirects = false
                                setRequestProperty("User-Agent", webUa)
                                setRequestProperty("X-IG-App-ID", "936619743392459")
                                setRequestProperty("X-ASBD-ID", "129477")
                                setRequestProperty("X-CSRFToken", activeCsrf)
                                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                                setRequestProperty("Sec-Fetch-Site", "same-origin")
                                setRequestProperty("Sec-Fetch-Mode", "cors")
                                setRequestProperty("Sec-Fetch-Dest", "empty")
                                setRequestProperty("Referer", referer)
                                setRequestProperty("Cookie", fullCookie)
                                connectTimeout = 15000
                                readTimeout = 15000
                            }
                            val code = c.responseCode
                            val body = try {
                                (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                            } catch (_: Exception) { "" }
                            c.disconnect()
                            return Pair(code, body)
                        }

                        // 1. Direct usernameinfo
                        val (c1, b1) = openIgGet("https://www.instagram.com/api/v1/users/$encHandle/usernameinfo/", "https://www.instagram.com/$encHandle/")
                        if (c1 in 200..299 && b1.isNotBlank()) {
                            val uObj = org.json.JSONObject(b1).optJSONObject("user")
                            if (uObj != null) {
                                resolvedId = uObj.optString("pk").ifBlank { uObj.optString("id") }
                            }
                        }

                        // 2. Web profile info
                        if (resolvedId.isBlank()) {
                            val (c2, b2) = openIgGet("https://www.instagram.com/api/v1/users/web_profile_info/?username=$encHandle", "https://www.instagram.com/$encHandle/")
                            if (c2 in 200..299 && b2.isNotBlank()) {
                                val uObj = org.json.JSONObject(b2).optJSONObject("data")?.optJSONObject("user")
                                if (uObj != null) {
                                    resolvedId = uObj.optString("id").ifBlank { uObj.optString("pk") }
                                }
                            }
                        }

                        // 3. Web users search
                        if (resolvedId.isBlank()) {
                            val (c3, b3) = openIgGet("https://www.instagram.com/api/v1/users/search/?q=$encHandle&count=30", "https://www.instagram.com/")
                            if (c3 in 200..299 && b3.isNotBlank()) {
                                val users = org.json.JSONObject(b3).optJSONArray("users")
                                if (users != null) {
                                    for (i in 0 until users.length()) {
                                        val u = users.getJSONObject(i)
                                        if (u.optString("username").equals(cleanHandle, ignoreCase = true)) {
                                            resolvedId = u.optString("pk").ifBlank { u.optString("id") }
                                            break
                                        }
                                    }
                                }
                            }
                        }

                        // 4. HTML profile page contextual scrape
                        if (resolvedId.isBlank()) {
                            val (c4, b4) = openIgGet("https://www.instagram.com/$encHandle/", "https://www.instagram.com/")
                            if (c4 in 200..299 && b4.isNotBlank() && !b4.contains(""pageID":"httpErrorPage"")) {
                                val m = Regex(""profilePage_(\\d{5,})"").find(b4)
                                    ?: Regex(""target_id":"(\\d{5,})"").find(b4)
                                if (m != null) {
                                    resolvedId = m.groupValues[1]
                                }
                            }
                        }
                    }

                    if (resolvedId.isBlank()) {
                        msg = "@${targetUsername.trim().removePrefix("@")} প্রোফাইলটি এখন লোড করা যায়নি — সেশন বা ইউজারনেম যাচাই করুন"
                    } else {
                        val fConn = (java.net.URL("https://www.instagram.com/api/v1/friendships/create/$resolvedId/").openConnection() as java.net.HttpURLConnection).apply {
                            requestMethod = "POST"
                            doOutput = true
                            instanceFollowRedirects = false
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                            setRequestProperty("X-IG-App-ID", "936619743392459")
                            setRequestProperty("X-ASBD-ID", "129477")
                            setRequestProperty("X-CSRFToken", activeCsrf)
                            setRequestProperty("X-Requested-With", "XMLHttpRequest")
                            setRequestProperty("Sec-Fetch-Site", "same-origin")
                            setRequestProperty("Sec-Fetch-Mode", "cors")
                            setRequestProperty("Sec-Fetch-Dest", "empty")
                            setRequestProperty("Origin", "https://www.instagram.com")
                            setRequestProperty("Referer", "https://www.instagram.com/$targetUsername/")
                            setRequestProperty("Cookie", fullCookie)
                            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                            connectTimeout = 15000
                            readTimeout = 15000
                        }
                        val payload = "container_module=profile&user_id=" + java.net.URLEncoder.encode(resolvedId, "UTF-8")
                        fConn.outputStream.use { it.write(payload.toByteArray()) }
                        val code = fConn.responseCode
                        val respText = try {
                            (if (code in 200..299) fConn.inputStream else fConn.errorStream)?.bufferedReader()?.readText() ?: ""
                        } catch (_: Exception) { "" }
                        fConn.disconnect()
                        if (code in 200..299 && (respText.contains(""status":"ok"") || respText.contains(""following":true") || respText.contains(""result":"following"") || respText.contains(""result":"requested""))) {
                            ok = true
                            msg = "OK"
                        } else {
                            msg = "HTTP $code: " + respText.take(120)
                        }
                    }
                } catch (e: Exception) {
                    msg = e.message ?: "Native follow error"
                }
                val safeMsg = org.json.JSONObject.quote(msg)
                val safeCb = org.json.JSONObject.quote(callbackId)
                val safeRid = org.json.JSONObject.quote(resolvedId)
                val safeCsrfOut = org.json.JSONObject.quote(activeCsrf)
                val js = "window.onAndroidFollowResult && window.onAndroidFollowResult($safeCb, $ok, $safeMsg, $safeRid, $safeCsrfOut);"
                mainWebView.post { mainWebView.evaluateJavascript(js, null) }
            }.start()
        }

        @JavascriptInterface
        fun startBackgroundMining() {
            val intent = Intent(this@MainActivity, MiningForegroundService::class.java)
            intent.action = MiningForegroundService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        @JavascriptInterface
        fun stopBackgroundMining() {
            val intent = Intent(this@MainActivity, MiningForegroundService::class.java)
            intent.action = MiningForegroundService.ACTION_STOP
            startService(intent)
        }

        @JavascriptInterface
        fun saveSlotCookieLocal(slotIndex: Int, cookieStr: String) {
            getSharedPreferences("qf_ig_slots", Context.MODE_PRIVATE)
                .edit()
                .putString("slot_$slotIndex", cookieStr)
                .apply()
        }

        @JavascriptInterface
        fun logoutSlotLocal(slotIndex: Int) {
            getSharedPreferences("qf_ig_slots", Context.MODE_PRIVATE)
                .edit()
                .remove("slot_$slotIndex")
                .apply()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mainWebView.canGoBack()) {
            mainWebView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
