package com.m3u8.liteplayer

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
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
    private var rotateButton: Button? = null

    /** 0 = 跟随系统自动旋转，1 = 锁定横屏，2 = 锁定竖屏 */
    private var rotateMode = 0

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 播放时别让屏幕自己熄灭
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        val uriString = intent.getStringExtra(EXTRA_URI)
        if (uriString.isNullOrBlank()) {
            finish()
            return
        }
        val name = intent.getStringExtra(EXTRA_NAME) ?: getString(R.string.app_name)
        findViewById<TextView>(R.id.playerTitle).text = name

        rotateButton = findViewById(R.id.rotateButton)
        rotateButton?.setOnClickListener {
            rotateMode = (rotateMode + 1) % 3
            applyRotation()
        }
        applyRotation()

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

    /**
     * 三种模式轮换：自动（跟随手机方向）→ 横屏 → 竖屏 → 自动。
     * 清单里已配置 configChanges，切换时不会重建 Activity，播放不会中断。
     */
    private fun applyRotation() {
        requestedOrientation = when (rotateMode) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        rotateButton?.text = when (rotateMode) {
            1 -> getString(R.string.rotate_land)
            2 -> getString(R.string.rotate_portrait)
            else -> getString(R.string.rotate_auto)
        }
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
