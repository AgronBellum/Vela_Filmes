package com.velafilmes.megaflixtv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                injectAutoNext()
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(MegaFlixBridge(this), "MegaFlix")
        webView.loadUrl("file:///android_asset/main.html?version=1.2")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            webView.evaluateJavascript("if (typeof pressBackAction === 'function') pressBackAction();", null)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun injectAutoNext() {
        webView.evaluateJavascript(AUTO_NEXT_SCRIPT, null)
    }

    class MegaFlixBridge(private val context: Context) {
        @JavascriptInterface
        fun updateHeaders(json: String?) {
            HeaderStore.update(json)
        }

        @JavascriptInterface
        fun getDeviceID(): String {
            return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        }

        @JavascriptInterface
        fun getSource(url: String, data: String?) {
            val intent = Intent(context, SourceActivity::class.java)
                .putExtra(SourceActivity.EXTRA_URL, url)
                .putExtra(SourceActivity.EXTRA_DATA, data)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        @JavascriptInterface
        fun fetchUrl(url: String, headersJson: String?): String {
            return runCatching { HttpBridge.fetch(url, headersJson) }.getOrElse { "" }
        }

        @JavascriptInterface
        fun showToast(message: String?) {
            Toast.makeText(context, message.orEmpty(), Toast.LENGTH_SHORT).show()
        }

        @JavascriptInterface
        fun closeApp() {
            (context as? Activity)?.finish()
        }
    }

    companion object {
        private val AUTO_NEXT_SCRIPT = """
            (function installVelaMegaAutoNext(){
              if (window.__velaMegaAutoNextInstallStarted) return;
              window.__velaMegaAutoNextInstallStarted = true;

              function install(){
                if (!window.jQuery || typeof window.startPlayer !== 'function') {
                  setTimeout(install, 500);
                  return;
                }
                if (window.VelaMegaAutoNext) return;

                var originalStartPlayer = window.startPlayer;
                window.startPlayer = function(url) {
                  try {
                    var selected = window.jQuery('#players .option.selected, #players .option.on').first();
                    if (!selected.length) selected = window.jQuery('#players .option').filter(function(){ return this.getAttribute('onclick') && this.getAttribute('onclick').indexOf(url) >= 0; }).first();
                    var options = window.jQuery('#players .option');
                    var mode = selected.attr('dub') !== undefined ? 'dub' : (selected.attr('leg') !== undefined ? 'leg' : '');
                    var index = selected.length ? options.index(selected) : 0;
                    localStorage.setItem('velaLastPlayerMode', mode);
                    localStorage.setItem('velaLastPlayerIndex', String(index < 0 ? 0 : index));
                  } catch(e) {}
                  return originalStartPlayer.apply(this, arguments);
                };

                window.VelaMegaAutoNext = {
                  onEnded: function(){
                    try {
                      if (typeof closeSelectPlayer === 'function' && window.jQuery('#players.on').length) closeSelectPlayer();
                      var episodes = window.jQuery('.season .episodes .episode');
                      if (!episodes.length) return false;
                      var current = episodes.filter('.selected').last();
                      if (!current.length) current = episodes.filter('.old').last();
                      var index = episodes.index(current);
                      if (index < 0) index = 0;
                      var next = episodes.eq(index + 1);
                      if (!next.length) return false;

                      next.trigger('click');

                      var tries = 0;
                      var timer = setInterval(function(){
                        tries++;
                        var panelOpen = window.jQuery('#players.on').length || window.jQuery('#players').css('display') !== 'none';
                        var options = window.jQuery('#players .option');
                        if (panelOpen && options.length) {
                          clearInterval(timer);
                          var mode = localStorage.getItem('velaLastPlayerMode') || 'dub';
                          var indexStored = parseInt(localStorage.getItem('velaLastPlayerIndex') || '0', 10);
                          var preferred = mode === 'leg' ? options.filter('[leg]') : options.filter('[dub]');
                          var chosen = preferred.eq(indexStored);
                          if (!chosen.length) chosen = preferred.first();
                          if (!chosen.length) chosen = options.eq(Math.max(0, Math.min(indexStored, options.length - 1)));
                          if (!chosen.length) chosen = options.first();
                          chosen.trigger('click');
                        }
                        if (tries > 80) clearInterval(timer);
                      }, 250);

                      return true;
                    } catch(e) {
                      return false;
                    }
                  }
                };
              }
              install();
            })();
        """.trimIndent()
    }
}