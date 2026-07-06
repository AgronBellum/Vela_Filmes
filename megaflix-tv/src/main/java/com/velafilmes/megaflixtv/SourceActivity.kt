package com.velafilmes.megaflixtv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import org.json.JSONObject

class SourceActivity : Activity() {
    private lateinit var webView: WebView
    private var injected = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        val progress = ProgressBar(this)
        webView = WebView(this)
        val root = FrameLayout(this)
        root.setBackgroundColor(android.graphics.Color.BLACK)
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        root.addView(progress, FrameLayout.LayoutParams(96, 96, android.view.Gravity.CENTER))
        setContentView(root)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = userAgentString.replace("; wv", "")
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!injected) {
                    injected = true
                    view.evaluateJavascript(
                        """
                        (function(){
                          var script2Mega = document.createElement('script');
                          script2Mega.type = 'text/javascript';
                          script2Mega.src = 'https://js.megafrixapi.com/?v=extract_tv_2&' + Date.now();
                          document.head.appendChild(script2Mega);
                        })();
                        """.trimIndent(),
                        null
                    )
                }
            }
        }
        webView.addJavascriptInterface(SourceBridge(this), "MegaFlix")

        val sourceUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val data = intent.getStringExtra(EXTRA_DATA)
        HeaderStore.update(data.asJson().optJSONObject("headers")?.toString())
        webView.loadUrl(sourceUrl, HeaderStore.headers)
    }

    private fun String?.asJson(): JSONObject {
        return runCatching { JSONObject(this ?: "{}") }.getOrDefault(JSONObject())
    }

    class SourceBridge(private val context: Context) {
        @JavascriptInterface
        fun updateHeaders(json: String?) {
            HeaderStore.update(json)
        }

        @JavascriptInterface
        fun getDeviceID(): String = android.provider.Settings.Secure
            .getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            .orEmpty()

        @JavascriptInterface
        fun fetchUrl(url: String, headersJson: String?): String {
            return runCatching { HttpBridge.fetch(url, headersJson) }.getOrElse { "" }
        }

        @JavascriptInterface
        fun openPlayerMovie(src: String, data: String?) {
            val intent = Intent(context, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URL, src)
                .putExtra(PlayerActivity.EXTRA_DATA, data)
            context.startActivity(intent)
            (context as? Activity)?.finish()
        }

        @JavascriptInterface
        fun openLive(src: String, data: String?) {
            openPlayerMovie(src, data)
        }

        @JavascriptInterface
        fun showToast(message: String?) {
            Toast.makeText(context, message.orEmpty(), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_DATA = "data"
    }
}
