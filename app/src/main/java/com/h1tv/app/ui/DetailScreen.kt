package com.h1tv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.h1tv.app.net.Site
import com.h1tv.app.net.Store
import com.h1tv.app.net.Video
import com.h1tv.app.net.VideoDetail
import com.h1tv.app.player.PlayerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DetailViewModel(private val id: String) : ViewModel() {
    var detail by mutableStateOf<VideoDetail?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun load() {
        error = null
        viewModelScope.launch {
            try {
                detail = Site.detail(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "載入失敗"
            }
        }
    }
}

@Composable
fun DetailScreen(video: Video) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val vm: DetailViewModel = viewModel(key = "detail:${video.id}") { DetailViewModel(video.id) }
    LaunchedEffect(vm) { if (vm.detail == null) vm.load() }

    val d = vm.detail
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val err = vm.error
            if (err != null) ErrorBox(err, onRetry = vm::load) else Loading()
        }
        return
    }

    // 收藏／紀錄用的卡片：標題以詳情頁為準
    val entry = video.copy(title = video.title.ifEmpty { d.title })
    var favorite by remember { mutableStateOf(Store.isFavorite(d.id)) }
    // 從播放器回來時重新讀進度
    var resumeMs by remember { mutableLongStateOf(Store.position(d.id)) }
    LifecycleResumeEffect(d.id) {
        resumeMs = Store.position(d.id)
        onPauseOrDispose { }
    }
    val playButton = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { playButton.requestFocus() }
    }

    fun play(fromStart: Boolean) {
        if (d.sources.isEmpty()) return
        Store.addHistory(entry)
        PlayerActivity.start(context, d.id, d.title, d.sources, if (fromStart) 0L else resumeMs)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(Modifier.padding(horizontal = 28.dp)) {
                AsyncImage(
                    model = d.poster.ifEmpty { video.cover },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(440.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CardBg),
                )
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    if (d.info.isNotEmpty()) Text(d.info, fontSize = 14.sp, color = Gray)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (d.sources.isEmpty()) {
                            Text("找不到播放來源", color = Accent, fontSize = 16.sp)
                        } else if (resumeMs > 5_000) {
                            TvButton(onClick = { play(false) }, modifier = Modifier.focusRequester(playButton)) {
                                Text("▶ 繼續播放（${fmtTime(resumeMs)}）")
                            }
                            TvButton(onClick = { play(true) }) { Text("從頭播放") }
                        } else {
                            TvButton(onClick = { play(true) }, modifier = Modifier.focusRequester(playButton)) {
                                Text("▶ 播放")
                            }
                        }
                        TvButton(
                            onClick = {
                                Store.toggleFavorite(entry)
                                favorite = Store.isFavorite(d.id)
                            },
                            selected = favorite,
                        ) { Text(if (favorite) "♥ 已收藏" else "♡ 收藏") }
                        if (d.artist.isNotEmpty()) {
                            TvButton(onClick = { nav.openListing(d.artist, Site.searchUrl(query = d.artist)) }) {
                                Text(d.artist, maxLines = 1)
                            }
                        }
                    }
                    if (d.sources.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "畫質：" + d.sources.joinToString(" / ") { "${it.quality}p" },
                            fontSize = 13.sp,
                            color = Gray,
                        )
                    }
                }
            }
        }
        if (d.description.isNotEmpty()) {
            item {
                Text(
                    d.description,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 28.dp),
                )
            }
        }
        if (d.tags.isNotEmpty()) {
            item {
                FlowRow(
                    Modifier.padding(horizontal = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    d.tags.forEach { tag ->
                        TvButton(onClick = { nav.openListing("#${tag.name}", tag.url) }) {
                            Text(tag.name, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        if (d.playlist.size > 1) {
            item { VideoRow("系列影片", d.playlist, onClick = nav.openVideo) }
        }
        if (d.related.isNotEmpty()) {
            item { VideoRow("相關影片", d.related, onClick = nav.openVideo) }
        }
    }
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
