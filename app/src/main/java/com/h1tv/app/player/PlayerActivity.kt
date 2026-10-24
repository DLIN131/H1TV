package com.h1tv.app.player

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 全螢幕播放器。影片是 CDN 上的 MP4 直連。
 * 不用 PlayerView 自帶的控制列（它會搶方向鍵去移動按鈕焦點），按鍵全部自己處理，只畫一條進度列：
 * 左右 = 快退／快進 5 秒（長按加速），OK = 暫停／播放，長按 OK = 按住期間 2 倍速，
 * 上鍵或選單鍵 = 換畫質，下鍵 = 顯示進度列，返回 = 離開。
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_QUALITIES = "qualities"
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_START = "start"
        private const val SEEK_STEP_MS = 5_000L
        private const val BOOST_SPEED = 2f
        /** 兩次 OK 間隔比這短就視為長按的自動重複（人手連按不會這麼快） */
        private const val AUTO_REPEAT_GAP_MS = 200L
        private const val OVERLAY_TIMEOUT_MS = 3_500L

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
    private lateinit var controls: View
    private lateinit var status: TextView
    private lateinit var timeText: TextView
    private lateinit var progress: ProgressBar
    private var hintJob: Job? = null
    private var overlayJob: Job? = null

    private var videoId = ""
    private var sources: List<Source> = emptyList()
    private var current: Source? = null
    private var ended = false

    /** 按住左右鍵期間累積的目標位置，放開才真的 seek，避免連續 seek 卡頓 */
    private var seekTarget: Long? = null
    private var okDown = false
    private var boosting = false
    private var lastOkUp = 0L
    private var boostRelease: Job? = null

    private val dataSourceFactory by lazy {
        DefaultHttpDataSource.Factory()
            .setUserAgent(WebFetcher.userAgent)
            .setDefaultRequestProperties(mapOf("Referer" to WebFetcher.BASE + "/"))
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) showOverlay() else updateOverlay()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                ended = true
                Store.savePosition(videoId, 0L)
                showOverlay()
            }
            updateOverlay()
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

        // 預設緩衝最多會吃到上百 MB 記憶體，電視盒子壓到 30 秒／20 MB 就夠順了
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
            .setTargetBufferBytes(20 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        player = ExoPlayer.Builder(this).setLoadControl(loadControl).build().also { it.addListener(listener) }

        playerView = PlayerView(this).apply {
            player = this@PlayerActivity.player
            useController = false
            isFocusable = false
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        hint = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setBackgroundColor(0xAA000000.toInt())
            setPadding(28, 14, 28, 14)
            visibility = View.GONE
        }
        setContentView(FrameLayout(this).apply {
            addView(playerView)
            this@PlayerActivity.controls = buildOverlay(intent.getStringExtra(EXTRA_TITLE).orEmpty())
            addView(this@PlayerActivity.controls, FrameLayout.LayoutParams(-1, -1))
            addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = 40
                rightMargin = 40
            })
        })

        // targetSdk 36 預設啟用預測返回，返回鍵不會經過 dispatchKeyEvent
        onBackPressedDispatcher.addCallback(this) { finish() }

        val want = Prefs.quality
        val start = sources.firstOrNull { it.quality <= want } ?: sources.last()
        val startMs = intent.getLongExtra(EXTRA_START, 0L)
        play(start, startMs)
        if (startMs > 0) showHint("從 ${fmtTime(startMs)} 繼續播放")
        showOverlay()
        startTicker()
    }

    /** 上方標題＋下方狀態、時間、進度條 */
    private fun buildOverlay(title: String): View {
        val titleView = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 18f
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setPadding(48, 32, 48, 0)
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, 24, 0)
        }
        timeText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(24, 0, 0, 0)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(0xFFE53950.toInt())
            secondaryProgressTintList = ColorStateList.valueOf(0x66FFFFFF)
            progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48, 28, 48, 36)
            setBackgroundColor(0x99000000.toInt())
            addView(status)
            addView(progress, LinearLayout.LayoutParams(0, -2, 1f))
            addView(timeText)
        }
        return FrameLayout(this).apply {
            addView(titleView, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            visibility = View.GONE
        }
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

    // ---------- 進度列 ----------

    private fun showOverlay() {
        controls.visibility = View.VISIBLE
        updateOverlay()
        overlayJob?.cancel()
        overlayJob = lifecycleScope.launch {
            delay(OVERLAY_TIMEOUT_MS)
            // 暫停、拖動進度、長按加速時不自動隱藏
            while (isActive && (!player.isPlaying || seekTarget != null || boosting)) delay(500)
            controls.visibility = View.GONE
        }
    }

    private fun updateOverlay() {
        if (controls.visibility != View.VISIBLE) return
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val pos = seekTarget ?: player.currentPosition
        status.text = when {
            boosting -> "▶▶ 2x"
            seekTarget != null -> if (seekTarget!! >= player.currentPosition) "▶▶" else "◀◀"
            ended -> "■"
            player.playWhenReady -> "▶"
            else -> "❚❚"
        }
        timeText.text = "${fmtTime(pos)} / ${if (dur > 0) fmtTime(dur) else "--:--"}"
        if (dur > 0) {
            progress.progress = (pos * 1000 / dur).toInt()
            progress.secondaryProgress = (player.bufferedPosition * 1000 / dur).toInt()
        }
    }

    private fun startTicker() {
        lifecycleScope.launch {
            while (isActive) {
                updateOverlay()
                delay(500)
            }
        }
    }

    // ---------- 遙控器 ----------

    private fun seekBy(forward: Boolean, repeat: Int) {
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val base = seekTarget ?: player.currentPosition
        // 第一下 5 秒；長按時依影片長度加速，大約 7 秒可以從頭掃到尾
        val step = if (repeat == 0) SEEK_STEP_MS else (dur / 150).coerceIn(2_000L, 30_000L)
        seekTarget = (base + if (forward) step else -step).coerceIn(0L, dur)
        showOverlay()
    }

    private fun commitSeek() {
        val target = seekTarget ?: return
        seekTarget = null
        player.seekTo(target)
        if (ended) {
            ended = false
            player.play()
        }
        showOverlay()
    }

    private fun startBoost() {
        boosting = true
        seekTarget = null
        if (!player.isPlaying) player.play()
        player.setPlaybackSpeed(BOOST_SPEED)
        showOverlay()
    }

    private fun stopBoost() {
        boosting = false
        player.setPlaybackSpeed(1f)
        showOverlay()
    }

    private fun togglePlay() {
        if (ended) {
            ended = false
            player.seekTo(0)
            player.play()
        } else {
            player.playWhenReady = !player.playWhenReady
        }
        showOverlay()
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
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (down) seekBy(false, event.repeatCount) else commitSeek()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (down) seekBy(true, event.repeatCount) else commitSeek()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE -> {
                // 短按：暫停／播放（放開時才判斷）；長按：放開前以 2 倍速播放。
                // 長按有兩種送法：一般遙控器送 repeatCount > 0 的 DOWN；
                // 部分紅外線遙控器則連續送很密的「按下＋放開」。
                val now = SystemClock.uptimeMillis()
                if (down) {
                    if (event.repeatCount == 0) {
                        okDown = true
                        if (now - lastOkUp < AUTO_REPEAT_GAP_MS) {
                            boostRelease?.cancel()
                            if (!boosting) startBoost()
                        }
                    } else if (okDown && !boosting) {
                        startBoost()
                    }
                } else {
                    lastOkUp = now
                    if (boosting) {
                        // 等一下確認是真的放開，而不是下一次自動重複
                        boostRelease?.cancel()
                        boostRelease = lifecycleScope.launch {
                            delay(AUTO_REPEAT_GAP_MS)
                            stopBoost()
                        }
                    } else if (okDown) {
                        togglePlay()
                    }
                    okDown = false
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (first) togglePlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (first) { player.play(); showOverlay() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (first) { player.pause(); showOverlay() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                if (first) chooseQuality()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (first) {
                    if (controls.visibility == View.VISIBLE && player.isPlaying) controls.visibility = View.GONE
                    else showOverlay()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showHint(text: String, long: Boolean = false) {
        hint.text = text
        hint.visibility = View.VISIBLE
        hintJob?.cancel()
        hintJob = lifecycleScope.launch {
            delay(if (long) 6_000 else 1_800)
            hint.visibility = View.GONE
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::player.isInitialized) return
        boostRelease?.cancel()
        if (boosting) stopBoost()
        okDown = false
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
