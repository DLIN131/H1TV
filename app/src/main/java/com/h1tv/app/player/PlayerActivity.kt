package com.h1tv.app.player

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.h1tv.app.Prefs
import com.h1tv.app.net.Source
import com.h1tv.app.net.Store
import com.h1tv.app.net.WebFetcher
import com.h1tv.app.ui.fmtTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 全螢幕播放器。影片是 CDN 上的 MP4 直連，用 Media3 的 PlayerView 自帶控制列（遙控器方向鍵可操作）。
 * 控制列隱藏時：左右 = 快退／快進 10 秒，OK = 暫停／播放，上鍵或選單鍵 = 換畫質。
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_QUALITIES = "qualities"
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_START = "start"
        private const val SEEK_STEP_MS = 10_000L

        fun start(context: Context, id: String, title: String, sources: List<Source>, startMs: Long) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_QUALITIES, sources.map { it.quality }.toIntArray())
                    .putExtra(EXTRA_URLS, sources.map { it.url }.toTypedArray())
                    .putExtra(EXTRA_START, startMs)
            )
        }
    }

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var hint: TextView
    private var hintJob: Job? = null

    private var videoId = ""
    private var sources: List<Source> = emptyList()
    private var current: Source? = null
    private var ended = false

    private val dataSourceFactory by lazy {
        DefaultHttpDataSource.Factory()
            .setUserAgent(WebFetcher.userAgent)
            .setDefaultRequestProperties(mapOf("Referer" to WebFetcher.BASE + "/"))
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                ended = true
                Store.savePosition(videoId, 0L)
                playerView.showController()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // 這個畫質的檔案壞了或連不上，自動降一級
            val lower = sources.firstOrNull { it.quality < (current?.quality ?: 0) }
            if (lower != null) {
                showHint("${current?.quality}p 無法播放，改用 ${lower.quality}p")
                play(lower, player.currentPosition)
            } else {
                showHint("播放失敗：${error.errorCodeName}", long = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        videoId = intent.getStringExtra(EXTRA_ID).orEmpty()
        val qualities = intent.getIntArrayExtra(EXTRA_QUALITIES) ?: IntArray(0)
        val urls = intent.getStringArrayExtra(EXTRA_URLS) ?: emptyArray()
        sources = qualities.zip(urls).map { (q, u) -> Source(q, u) }.sortedByDescending { it.quality }
        if (videoId.isEmpty() || sources.isEmpty()) {
            finish()
            return
        }

        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()
            .also { it.addListener(listener) }

        playerView = PlayerView(this).apply {
            player = this@PlayerActivity.player
            setBackgroundColor(Color.BLACK)
            controllerShowTimeoutMs = 4_000
            setShowNextButton(false)
            setShowPreviousButton(false)
            keepScreenOn = true
        }
        hint = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setBackgroundColor(0xAA000000.toInt())
            setPadding(28, 14, 28, 14)
            visibility = TextView.GONE
        }
        val title = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            setTextColor(Color.WHITE)
            textSize = 18f
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setPadding(40, 28, 40, 0)
        }
        // 標題跟著控制列一起出現
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            title.visibility = v
        })
        setContentView(FrameLayout(this).apply {
            addView(playerView)
            addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = 40
                rightMargin = 40
            })
        })

        onBackPressedDispatcher.addCallback(this) {
            if (playerView.isControllerFullyVisible) playerView.hideController() else finish()
        }

        val want = Prefs.quality
        val start = sources.firstOrNull { it.quality <= want } ?: sources.last()
        val startMs = intent.getLongExtra(EXTRA_START, 0L)
        play(start, startMs)
        if (startMs > 0) showHint("從 ${fmtTime(startMs)} 繼續播放")
    }

    private fun play(source: Source, positionMs: Long) {
        current = source
        ended = false
        val item = MediaItem.fromUri(source.url)
        player.setMediaSource(ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item))
        player.prepare()
        if (positionMs > 0) player.seekTo(positionMs)
        player.playWhenReady = true
    }

    private fun chooseQuality() {
        val labels = sources.map { "${it.quality}p" + if (it == current) "（目前）" else "" }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("選擇畫質")
            .setItems(labels) { _, i ->
                val s = sources[i]
                Prefs.quality = s.quality
                if (s != current) {
                    play(s, player.currentPosition)
                    showHint("已切換到 ${s.quality}p")
                }
            }
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 控制列打開時交給 PlayerView 自己處理（按鈕之間移動焦點）
        if (!playerView.isControllerFullyVisible && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    player.seekBack()
                    showHint("◀◀ ${fmtTime(player.currentPosition)}")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    player.seekForward()
                    showHint("▶▶ ${fmtTime(player.currentPosition)}")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.repeatCount == 0) {
                        if (ended) {
                            player.seekTo(0)
                            ended = false
                        }
                        player.playWhenReady = !player.playWhenReady
                        if (!player.playWhenReady) playerView.showController()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU -> {
                    if (event.repeatCount == 0) chooseQuality()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    playerView.showController()
                    return true
                }
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN) {
            chooseQuality()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showHint(text: String, long: Boolean = false) {
        hint.text = text
        hint.visibility = TextView.VISIBLE
        hintJob?.cancel()
        hintJob = lifecycleScope.launch {
            delay(if (long) 6_000 else 1_800)
            hint.visibility = TextView.GONE
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::player.isInitialized) return
        // 看到最後 30 秒內就當作看完，下次從頭開始
        val pos = player.currentPosition
        val dur = player.duration
        val done = ended || (dur > 0 && dur - pos < 30_000)
        Store.savePosition(videoId, if (done) 0L else pos)
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) player.release()
    }
}
