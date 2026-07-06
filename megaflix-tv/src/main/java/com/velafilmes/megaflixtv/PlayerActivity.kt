package com.velafilmes.megaflixtv

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import org.json.JSONObject

@UnstableApi
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        val playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 4000
        }
        setContentView(playerView)

        val data = intent.getStringExtra(EXTRA_DATA)
        val json = runCatching { JSONObject(data ?: "{}") }.getOrDefault(JSONObject())
        val src = json.optString("src", intent.getStringExtra(EXTRA_URL).orEmpty())
        val headers = json.optJSONObject("headers")?.toString()
            ?.let(HeaderStore::parse)
            ?.ifEmpty { HeaderStore.headers }
            ?: HeaderStore.headers

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)

        val mediaItem = MediaItem.fromUri(Uri.parse(src))
        val source = if (src.contains(".m3u8", ignoreCase = true) || json.optBoolean("hls")) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }

        player = ExoPlayer.Builder(this).build().also {
            playerView.player = it
            it.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        finish()
                    }
                }
            })
            it.setMediaSource(source)
            it.prepare()
            it.playWhenReady = true
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_DATA = "data"
    }
}
