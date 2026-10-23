package com.h1tv.pad

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.h1tv.app.net.Site
import com.h1tv.app.net.Store
import com.h1tv.app.net.WebFetcher
import com.h1tv.app.ui.FeedViewModel
import com.h1tv.app.ui.HomeViewModel

/** 頁面標題列；子頁面有返回箭頭 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PadTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            }
        },
        actions = actions,
        // 外層已經處理過系統列的留白
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
    )
}

/** 首頁：網站首頁的每一列 */
@Composable
fun HomeRowsScreen() {
    val nav = LocalNav.current
    val vm: HomeViewModel = viewModel()
    LaunchedEffect(vm) { if (vm.rows.isEmpty() && vm.error == null) vm.load() }

    Column(Modifier.fillMaxSize()) {
        PadTopBar("首頁", actions = {
            IconButton(onClick = vm::load) { Icon(Icons.Filled.Refresh, contentDescription = "重新整理") }
        })
        if (vm.rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val err = vm.error
                if (err != null && !vm.loading) ErrorBox(err, onRetry = vm::load)
                else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Loading()
                    Spacer(Modifier.height(16.dp))
                    Text("連線中（第一次開啟要過網站驗證，約 5～10 秒）", color = Gray, fontSize = 14.sp)
                }
            }
            return
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(vm.rows, key = { it.title }) { row ->
                VideoRow(
                    title = row.title,
                    items = row.items,
                    onClick = nav.openVideo,
                    onMore = { nav.openListing(row.title, row.moreUrl) },
                )
            }
        }
    }
}

/** 分類：類型 × 排序 */
@Composable
fun BrowseScreen() {
    val nav = LocalNav.current
    var genre by rememberSaveable { mutableStateOf(Site.genres.first()) }
    var sort by rememberSaveable { mutableStateOf(Site.sorts.first()) }
    val vm: FeedViewModel = viewModel(key = "browse") { FeedViewModel(Site.searchUrl(genre = genre, sort = sort)) }
    fun reload() = vm.refresh(Site.searchUrl(genre = genre, sort = sort))

    Column(Modifier.fillMaxSize()) {
        PadTopBar("分類", actions = {
            IconButton(onClick = ::reload) { Icon(Icons.Filled.Refresh, contentDescription = "重新整理") }
        })
        ChipRow(listOf("全部") + Site.genres, selected = genre.ifEmpty { "全部" }) {
            genre = if (it == "全部") "" else it
            reload()
        }
        ChipRow(Site.sorts, selected = sort) {
            sort = it
            reload()
        }
        VideoGrid(vm, onClick = nav.openVideo)
    }
}

@Composable
fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options) { o ->
            FilterChip(
                selected = o == selected,
                onClick = { if (o != selected) onSelect(o) },
                label = { Text(o) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent),
            )
        }
    }
}

/** 「查看更多」、標籤、作者 */
@Composable
fun ListingScreen(title: String, url: String) {
    val nav = LocalNav.current
    val vm: FeedViewModel = viewModel(key = "list:$url") { FeedViewModel(url) }
    Column(Modifier.fillMaxSize()) {
        PadTopBar(title, onBack = nav.back)
        VideoGrid(vm, onClick = nav.openVideo)
    }
}

@Composable
fun SearchScreen() {
    val nav = LocalNav.current
    val focus = LocalFocusManager.current
    var text by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val vm: FeedViewModel = viewModel(key = "search") { FeedViewModel(Site.searchUrl()) }

    fun search(keyword: String) {
        val k = keyword.trim()
        if (k.isEmpty()) return
        text = k
        query = k
        Store.addSearchWord(k)
        vm.refresh(Site.searchUrl(query = k))
        focus.clearFocus()
    }

    Column(Modifier.fillMaxSize()) {
        PadTopBar("搜尋")
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text("輸入片名、作者、社團", color = Gray) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { text = ""; query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "清除") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search(text) }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        if (query.isEmpty()) {
            if (Store.searchWords.isNotEmpty()) {
                Column(Modifier.padding(16.dp)) {
                    Text("最近搜尋", fontSize = 15.sp, color = Gray)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Store.searchWords.forEach { word ->
                            AssistChip(onClick = { search(word) }, label = { Text(word) })
                        }
                    }
                }
            }
        } else {
            VideoGrid(vm, onClick = nav.openVideo)
        }
    }
}

/** 收藏／觀看紀錄（存在本機） */
@Composable
fun LocalListScreen(title: String, favorites: Boolean) {
    val nav = LocalNav.current
    val list = if (favorites) Store.favorites else Store.history
    Column(Modifier.fillMaxSize()) {
        PadTopBar(title, actions = {
            if (!favorites && list.isNotEmpty()) TextButton(onClick = Store::clearHistory) { Text("清除紀錄") }
        })
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (favorites) "還沒有收藏任何影片" else "還沒有看過任何影片", color = Gray, fontSize = 16.sp)
            }
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(200.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 直式海報和橫式縮圖混在一起，統一用橫式比例顯示
            items(list.toList(), key = { it.id }) { v ->
                VideoCard(v.copy(vertical = false), onClick = { nav.openVideo(v) })
            }
        }
    }
}

/** 可見的 WebView：網站要人工驗證時，讓使用者自己點完 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VerifyScreen() {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize()) {
        PadTopBar("網站驗證", onBack = nav.back, actions = {
            TextButton(onClick = nav.back) { Text("完成") }
        })
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("完成驗證、看到網站首頁後按「完成」，再按「重試」", color = Gray, fontSize = 13.sp)
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = WebFetcher.userAgent
                    webViewClient = WebViewClient()
                    loadUrl(WebFetcher.BASE)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
