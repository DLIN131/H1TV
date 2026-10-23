package com.h1tv.pad

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
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
import com.h1tv.app.util.fmtTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 平板用的全螢幕播放器（觸控）。進度條、暫停、快退快進用 Media3 內建的控制列；另外加上手勢：
 * 控制列隱藏時，點一下 = 顯示控制列，左半邊／右半邊點兩下 = 快退／快進 10 秒，
 * 長按畫面 = 按住期間 2 倍速。上方有返回鍵、標題和畫質按鈕。
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
        private const val BOOST_SPEED = 2f

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
    private lateinit var topBar: View
    private lateinit var qualityButton: TextView
    private var hintJob: Job? = null

    private var videoId = ""
    private var sources: List<Source> = emptyList()
    private var current: Source? = null
    private var ended = false
    private var boosting = false

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

    /** 控制列隱藏時的手勢；控制列顯示時交給控制列自己（按鈕、進度條） */
    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                playerView.showController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val forward = e.x > playerView.width / 2f
                val target = (player.currentPosition + if (forward) SEEK_STEP_MS else -SEEK_STEP_MS)
                    .coerceIn(0L, player.duration.coerceAtLeast(0L))
                player.seekTo(target)
                showHint((if (forward) "▶▶ " else "◀◀ ") + fmtTime(target))
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                boosting = true
                if (!player.isPlaying) player.play()
                player.setPlaybackSpeed(BOOST_SPEED)
                showHint("▶▶ 2 倍速播放中", sticky = true)
            }
        })
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
            controllerShowTimeoutMs = 3_500
            setShowNextButton(false)
            setShowPreviousButton(false)
            keepScreenOn = true
        }
        hint = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(0xAA000000.toInt())
            setPadding(28, 14, 28, 14)
            visibility = View.GONE
        }
        topBar = buildTopBar(intent.getStringExtra(EXTRA_TITLE).orEmpty())
        // 上方列跟著控制列一起出現／消失
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            topBar.visibility = v
        })
        setContentView(FrameLayout(this).apply {
            addView(playerView)
            addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
                topMargin = 140
            })
        })

        val want = Prefs.quality
        val start = sources.firstOrNull { it.quality <= want } ?: sources.last()
        val startMs = intent.getLongExtra(EXTRA_START, 0L)
        play(start, startMs)
        if (startMs > 0) showHint("從 ${fmtTime(startMs)} 繼續播放")
    }

    private fun buildTopBar(title: String): View {
        fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 17f
            setPadding(36, 24, 36, 24)
            setOnClickListener { onClick() }
        }
        qualityButton = button("畫質") { chooseQuality() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x88000000.toInt())
            setPadding(8, 8, 8, 8)
            addView(button("‹ 返回") { finish() })
            addView(TextView(this@PlayerActivity).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(qualityButton)
        }
    }

    private fun play(source: Source, positionMs: Long) {
        current = source
        ended = false
        qualityButton.text = "${source.quality}p"
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!::player.isInitialized) return super.dispatchTouchEvent(ev)
        // 長按放開：恢復原速
        if (boosting && (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL)) {
            boosting = false
            player.setPlaybackSpeed(1f)
            hint.visibility = View.GONE
            return true
        }
        if (playerView.isControllerFullyVisible) return super.dispatchTouchEvent(ev)
        gestures.onTouchEvent(ev)
        return true
    }

    private fun showHint(text: String, long: Boolean = false, sticky: Boolean = false) {
        hint.text = text
        hint.visibility = View.VISIBLE
        hintJob?.cancel()
        if (sticky) return
        hintJob = lifecycleScope.launch {
            delay(if (long) 6_000 else 1_500)
            hint.visibility = View.GONE
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::player.isInitialized) return
        if (boosting) {
            boosting = false
            player.setPlaybackSpeed(1f)
        }
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
