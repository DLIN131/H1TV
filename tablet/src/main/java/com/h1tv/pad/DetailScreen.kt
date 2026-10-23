package com.h1tv.pad

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.h1tv.app.net.Site
import com.h1tv.app.net.Store
import com.h1tv.app.net.Video
import com.h1tv.app.net.VideoDetail
import com.h1tv.app.ui.DetailViewModel
import com.h1tv.app.util.fmtTime

@Composable
fun DetailScreen(video: Video) {
    val nav = LocalNav.current
    val vm: DetailViewModel = viewModel(key = "detail:${video.id}") { DetailViewModel(video.id) }
    LaunchedEffect(vm) { if (vm.detail == null) vm.load() }

    Column(Modifier.fillMaxSize()) {
        PadTopBar(vm.detail?.title ?: video.title, onBack = nav.back)
        val d = vm.detail
        if (d == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val err = vm.error
                if (err != null) ErrorBox(err, onRetry = vm::load) else Loading()
            }
        } else {
            DetailContent(video, d)
        }
    }
}

@Composable
private fun DetailContent(video: Video, d: VideoDetail) {
    val nav = LocalNav.current
    val context = LocalContext.current
    // 收藏／紀錄用的卡片：標題以詳情頁為準
    val entry = video.copy(title = video.title.ifEmpty { d.title })
    var favorite by remember { mutableStateOf(Store.isFavorite(d.id)) }
    // 從播放器回來時重新讀進度
    var resumeMs by remember { mutableLongStateOf(Store.position(d.id)) }
    LifecycleResumeEffect(d.id) {
        resumeMs = Store.position(d.id)
        onPauseOrDispose { }
    }

    fun play(fromStart: Boolean) {
        if (d.sources.isEmpty()) return
        Store.addHistory(entry)
        PlayerActivity.start(context, d.id, d.title, d.sources, if (fromStart) 0L else resumeMs)
    }

    val poster: @Composable (Modifier) -> Unit = { m ->
        AsyncImage(
            model = d.poster.ifEmpty { video.cover },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = m
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(CardBg),
        )
    }
    val info: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Text(d.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (d.info.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(d.info, fontSize = 13.sp, color = Gray)
            }
            Spacer(Modifier.height(14.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    d.sources.isEmpty() -> Text("找不到播放來源", color = Accent, fontSize = 15.sp)
                    resumeMs > 5_000 -> {
                        Button(onClick = { play(false) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Text("繼續播放（${fmtTime(resumeMs)}）")
                        }
                        OutlinedButton(onClick = { play(true) }) { Text("從頭播放") }
                    }
                    else -> Button(onClick = { play(true) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text("播放")
                    }
                }
                OutlinedButton(onClick = {
                    Store.toggleFavorite(entry)
                    favorite = Store.isFavorite(d.id)
                }) {
                    Icon(
                        if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                        tint = if (favorite) Accent else Color.White,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (favorite) "已收藏" else "收藏")
                }
                if (d.artist.isNotEmpty()) {
                    OutlinedButton(onClick = { nav.openListing(d.artist, Site.searchUrl(query = d.artist)) }) {
                        Text(d.artist, maxLines = 1)
                    }
                }
            }
            if (d.sources.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("畫質：" + d.sources.joinToString(" / ") { "${it.quality}p" }, fontSize = 12.sp, color = Gray)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                if (wide) {
                    Row(Modifier.padding(horizontal = 16.dp)) {
                        poster(Modifier.width(420.dp))
                        Spacer(Modifier.width(20.dp))
                        info(Modifier.weight(1f))
                    }
                } else {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        poster(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(14.dp))
                        info(Modifier.fillMaxWidth())
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
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            if (d.tags.isNotEmpty()) {
                item {
                    FlowRow(
                        Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        d.tags.forEach { tag ->
                            AssistChip(onClick = { nav.openListing("#${tag.name}", tag.url) }, label = { Text(tag.name) })
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
}
