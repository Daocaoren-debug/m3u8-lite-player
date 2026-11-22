package com.m3u8.liteplayer

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView

class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val uriString = intent.getStringExtra(EXTRA_URI)
        if (uriString.isNullOrBlank()) {
            finish()
            return
        }
        val name = intent.getStringExtra(EXTRA_NAME) ?: getString(R.string.app_name)
        findViewById<TextView>(R.id.playerTitle).text = name

        playerView = findViewById<PlayerView>(R.id.playerView).apply {
            setShowFastForwardButton(true)   // 快进 10 秒
            setShowRewindButton(true)        // 快退 10 秒
            setShowPreviousButton(false)
            setShowNextButton(false)
            controllerShowTimeoutMs = 4000
        }

        val uri = Uri.parse(uriString)
        val dataSourceFactory = DefaultDataSource.Factory(this)
        val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(uri))

        player = ExoPlayer.Builder(this)
            .build()
            .apply {
                setWakeMode(C.WAKE_MODE_LOCAL)
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Toast.makeText(
                            this@PlayerActivity,
                            "播放失败：${error.errorCodeName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                })
            }

        playerView?.player = player
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        playerView?.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
