package com.h1tv.pad

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.h1tv.app.net.BlockedException
import com.h1tv.app.net.Video
import com.h1tv.app.ui.FeedViewModel

/** 頁面堆疊裡的頁面 */
sealed interface Route {
    data class Detail(val video: Video) : Route
    data class Listing(val title: String, val url: String) : Route
    data object Verify : Route
}

class Nav(
    val openVideo: (Video) -> Unit,
    val openListing: (title: String, url: String) -> Unit,
    val openVerify: () -> Unit,
    val back: () -> Unit,
)

val LocalNav = staticCompositionLocalOf<Nav> { error("Nav 尚未提供") }

private const val VERTICAL_RATIO = 268f / 394f

@Composable
fun VideoCard(item: Video, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (item.vertical) VERTICAL_RATIO else 16f / 9f)
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
                Spacer(Modifier.height(2.dp))
                Text(item.author, fontSize = 12.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier, size: Int = 40) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Accent, modifier = Modifier.size(size.dp))
    }
}

val BLOCKED_MESSAGE: String = BlockedException().message!!

/** 錯誤訊息；被網站驗證擋下的話多一顆「開啟驗證頁面」 */
@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = Gray, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (message == BLOCKED_MESSAGE) Button(onClick = nav.openVerify) { Text("開啟驗證頁面") }
            OutlinedButton(onClick = onRetry) { Text("重試") }
        }
    }
}

/** 一列橫向卡片（首頁、詳情頁的系列／相關影片），手指左右滑 */
@Composable
fun VideoRow(
    title: String,
    items: List<Video>,
    onClick: (Video) -> Unit,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
) {
    val vertical = items.count { it.vertical } * 2 > items.size
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (onMore != null) TextButton(onClick = onMore) { Text("查看更多 ›") }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { v ->
                VideoCard(v, onClick = { onClick(v) }, modifier = Modifier.width(if (vertical) 130.dp else 220.dp))
            }
        }
    }
}

/** 列表頁：自動依螢幕寬度決定欄數，捲到底自動載入下一頁 */
@Composable
fun VideoGrid(vm: FeedViewModel, onClick: (Video) -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(vm) { vm.ensureLoaded() }

    if (vm.items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val err = vm.error
            when {
                vm.loading -> Loading()
                err != null -> ErrorBox(err, onRetry = { vm.loadMore() })
                else -> Text("這裡什麼都沒有", color = Gray, fontSize = 16.sp)
            }
        }
        return
    }

    val vertical = vm.items.count { it.vertical } * 2 > vm.items.size
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (vertical) 130.dp else 210.dp),
        state = vm.gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(vm.items, key = { i, it -> "$i:${it.id}" }) { index, item ->
            if (index >= vm.items.size - 8) {
                LaunchedEffect(vm.items.size) { vm.loadMore() }
            }
            VideoCard(item, onClick = { onClick(item) })
        }
        val err = vm.error
        if (vm.loading || err != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp), contentAlignment = Alignment.Center
                ) {
                    if (err != null) ErrorBox(err, onRetry = { vm.loadMore() }) else Loading(size = 28)
                }
            }
        }
    }
}
