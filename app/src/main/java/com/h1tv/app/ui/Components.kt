package com.h1tv.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.h1tv.app.net.BlockedException
import com.h1tv.app.net.Video
import kotlinx.coroutines.delay

@Composable
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Accent else Color.White.copy(alpha = 0.1f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.8f),
            pressedContentColor = Color.Black,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        content = content,
    )
}

@Composable
fun VideoCard(item: Video, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape)),
        colors = CardDefaults.colors(containerColor = CardBg, focusedContainerColor = CardFocusBg),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (item.vertical) 268f / 394f else 16f / 9f)
                .background(Color(0xFF26262D))
        ) {
            AsyncImage(
                model = item.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.views.isNotEmpty() || item.duration.isNotEmpty() || item.likes.isNotEmpty()) {
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.likes.isNotEmpty()) {
                        Text("👍 ${item.likes}", fontSize = 11.sp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                    }
                    if (item.views.isNotEmpty()) Text(item.views, fontSize = 11.sp, color = Color.White)
                    Spacer(Modifier.weight(1f))
                    if (item.duration.isNotEmpty()) Text(item.duration, fontSize = 11.sp, color = Color.White)
                }
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                item.title,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.author.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(item.author, fontSize = 12.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier, size: Int = 48) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Accent, modifier = Modifier.size(size.dp))
    }
}

val BLOCKED_MESSAGE: String = BlockedException().message!!

/** 錯誤訊息；被 Cloudflare 擋的話多一顆「開啟驗證頁面」 */
@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = Gray, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (message == BLOCKED_MESSAGE) TvButton(onClick = nav.openVerify) { Text("開啟驗證頁面") }
            TvButton(onClick = onRetry) { Text("重試") }
        }
    }
}

@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

/** 一列橫向卡片（首頁、詳情頁的系列／相關影片） */
@Composable
fun VideoRow(
    title: String,
    items: List<Video>,
    onClick: (Video) -> Unit,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
) {
    val vertical = items.count { it.vertical } * 2 > items.size
    val cardWidth = if (vertical) 150.dp else 250.dp
    Column(modifier) {
        Text(
            title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 28.dp, bottom = 4.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(items, key = { it.id }) { v ->
                VideoCard(v, onClick = { onClick(v) }, modifier = Modifier.width(cardWidth))
            }
            if (onMore != null) {
                item(key = "#more") {
                    val shape = RoundedCornerShape(10.dp)
                    Card(
                        onClick = onMore,
                        modifier = Modifier
                            .width(cardWidth)
                            .aspectRatio(if (vertical) 268f / 394f else 16f / 9f),
                        shape = CardDefaults.shape(shape),
                        scale = CardDefaults.scale(focusedScale = 1.06f),
                        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape)),
                        colors = CardDefaults.colors(containerColor = CardBg, focusedContainerColor = CardFocusBg),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("查看更多 ›", fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VideoGrid(vm: FeedViewModel, onClick: (Video) -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(vm) { vm.ensureLoaded() }

    if (vm.items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val err = vm.error
            when {
                vm.loading -> Loading()
                err != null -> ErrorBox(err, onRetry = { vm.loadMore() })
                else -> Text("這裡什麼都沒有", color = Gray, fontSize = 18.sp)
            }
        }
        return
    }

    val restore = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (vm.restoreFocus) {
            vm.restoreFocus = false
            delay(80)
            runCatching { restore.requestFocus() }
        }
    }

    val vertical = vm.items.count { it.vertical } * 2 > vm.items.size
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (vertical) 6 else 4),
        state = vm.gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        itemsIndexed(vm.items, key = { i, it -> "$i:${it.id}" }) { index, item ->
            if (index >= vm.items.size - 8) {
                LaunchedEffect(vm.items.size) { vm.loadMore() }
            }
            VideoCard(
                item = item,
                onClick = {
                    vm.focusIndex = index
                    vm.restoreFocus = true
                    onClick(item)
                },
                modifier = if (index == vm.focusIndex) Modifier.focusRequester(restore) else Modifier,
            )
        }
        val err = vm.error
        if (vm.loading || err != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp), contentAlignment = Alignment.Center
                ) {
                    if (err != null) ErrorBox(err, onRetry = { vm.loadMore() }) else Loading(size = 32)
                }
            }
        }
    }
}
