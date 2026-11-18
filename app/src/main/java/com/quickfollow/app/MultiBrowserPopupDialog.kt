package com.quickfollow.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MultiBrowserPopupDialog(
    private val hostContext: Context,
    private val onSessionCaptured: (Int, String, String, String, String) -> Unit
) : Dialog(hostContext) {

    private var currentSlot = 1
    private lateinit var igWebView: WebView
    private lateinit var titleText: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(hostContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = LinearLayout(hostContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 18, 24, 18)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        titleText = TextView(hostContext).apply {
            text = "Instagram Browser #" + currentSlot
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val refreshBtn = Button(hostContext).apply {
            text = "Reload"
            setOnClickListener { igWebView.reload() }
        }

        val saveBtn = Button(hostContext).apply {
            text = "Save"
            setOnClickListener { captureCurrentCookies() }
        }

        val closeBtn = Button(hostContext).apply {
            text = "Close"
            setOnClickListener { dismiss() }
        }

        topBar.addView(titleText)
        topBar.addView(refreshBtn)
        topBar.addView(saveBtn)
        topBar.addView(closeBtn)

        igWebView = WebView(hostContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    captureCurrentCookies()
                }
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(igWebView, true)

        root.addView(topBar)
        root.addView(igWebView)
        setContentView(root)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (hostContext.resources.displayMetrics.heightPixels * 0.88).toInt())
    }

    fun showSlot(slotIndex: Int) {
        currentSlot = slotIndex
        show()
        titleText.text = "Instagram Browser #" + slotIndex
        val prefs = hostContext.getSharedPreferences("qf_ig_slots", Context.MODE_PRIVATE)
        val savedCookie = prefs.getString("slot_$slotIndex", null)
        val cm = CookieManager.getInstance()
        cm.removeAllCookies {
            if (!savedCookie.isNullOrBlank()) {
                savedCookie.split(";").forEach { pair ->
                    cm.setCookie("https://www.instagram.com", pair.trim())
                }
            }
            cm.flush()
            igWebView.post {
                igWebView.loadUrl("https://www.instagram.com/accounts/login/")
            }
        }
    }

    private fun captureCurrentCookies() {
        val raw = CookieManager.getInstance().getCookie("https://www.instagram.com") ?: return
        if (!raw.contains("sessionid=")) return
        hostContext.getSharedPreferences("qf_ig_slots", Context.MODE_PRIVATE)
            .edit()
            .putString("slot_$currentSlot", raw)
            .apply()

        val sid = Regex("sessionid=([^;]+)").find(raw)?.groupValues?.getOrNull(1) ?: ""
        val csrf = Regex("csrftoken=([^;]+)").find(raw)?.groupValues?.getOrNull(1) ?: ""
        val ds = Regex("ds_user_id=([^;]+)").find(raw)?.groupValues?.getOrNull(1) ?: ""
        if (sid.isNotBlank()) {
            val capturedSlot = currentSlot
            Thread {
                var finalUser = if (ds.isNotBlank()) "ig_" + ds.takeLast(6) else "ig_slot_$capturedSlot"
                try {
                    val conn = (java.net.URL("https://www.instagram.com/api/v1/accounts/current_user/?edit=true").openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                        setRequestProperty("X-IG-App-ID", "936619743392459")
                        setRequestProperty("X-ASBD-ID", "129477")
                        setRequestProperty("X-CSRFToken", csrf)
                        setRequestProperty("X-Requested-With", "XMLHttpRequest")
                        setRequestProperty("Sec-Fetch-Site", "same-origin")
                        setRequestProperty("Sec-Fetch-Mode", "cors")
                        setRequestProperty("Sec-Fetch-Dest", "empty")
                        setRequestProperty("Cookie", raw)
                        connectTimeout = 10000
                        readTimeout = 10000
                    }
                    if (conn.responseCode in 200..299) {
                        val txt = conn.inputStream.bufferedReader().readText()
                        val uname = org.json.JSONObject(txt).optJSONObject("user")?.optString("username") ?: ""
                        if (uname.isNotBlank()) finalUser = uname
                    }
                    conn.disconnect()
                } catch (_: Exception) {}
                onSessionCaptured(capturedSlot, finalUser, sid, csrf, ds)
            }.start()
        }
    }
}
