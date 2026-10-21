package com.h1tv.app.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.h1tv.app.net.Site
import com.h1tv.app.net.HomeRow
import com.h1tv.app.net.Store
import com.h1tv.app.net.WebFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
    val rows = mutableStateListOf<HomeRow>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun load() {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val r = Site.home()
                rows.clear()
                rows.addAll(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "載入失敗"
            } finally {
                loading = false
            }
        }
    }
}

/** 首頁：網站首頁的每一列（最新上市、最新上傳、各類型…） */
@Composable
fun HomeRowsScreen() {
    val nav = LocalNav.current
    val vm: HomeViewModel = viewModel()
    LaunchedEffect(vm) { if (vm.rows.isEmpty() && vm.error == null) vm.load() }

    if (vm.rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val err = vm.error
            if (err != null && !vm.loading) ErrorBox(err, onRetry = vm::load)
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Loading()
                Spacer(Modifier.height(16.dp))
                Text("連線中（第一次開啟要過網站驗證，約 5～10 秒）", color = Gray, fontSize = 15.sp)
            }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
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

@Composable
fun SearchScreen() {
    val nav = LocalNav.current
    var text by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val vm: FeedViewModel = viewModel(key = "search") { FeedViewModel(Site.searchUrl()) }
    val searchButton = remember { FocusRequester() }
    var focusTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(focusTick) {
        if (focusTick > 0) {
            delay(100)
            runCatching { searchButton.requestFocus() }
        }
    }

    fun search(keyword: String) {
        val k = keyword.trim()
        if (k.isEmpty()) return
        text = k
        query = k
        Store.addSearchWord(k)
        vm.refresh(Site.searchUrl(query = k))
        focusTick++
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("搜尋")
        Row(
            Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("輸入片名、作者、社團", color = Gray) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search(text) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Accent,
                ),
                modifier = Modifier.width(520.dp),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(onClick = { search(text) }, modifier = Modifier.focusRequester(searchButton)) { Text("搜尋") }
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                TvButton(onClick = { query = ""; text = "" }) { Text("清除") }
            }
        }

        if (query.isEmpty()) {
            if (Store.searchWords.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 28.dp, vertical = 16.dp)) {
                    Text("最近搜尋", fontSize = 18.sp, color = Gray)
                    Spacer(Modifier.height(14.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Store.searchWords.forEach { word ->
                            TvButton(onClick = { search(word) }) { Text(word, fontSize = 15.sp) }
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
        ScreenTitle(title) {
            if (!favorites && list.isNotEmpty()) TvButton(onClick = Store::clearHistory) { Text("清除紀錄") }
        }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (favorites) "還沒有收藏任何影片" else "還沒有看過任何影片", color = Gray, fontSize = 18.sp)
            }
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            // 直式海報和橫式縮圖混在一起，統一用橫式比例顯示
            items(list.toList(), key = { it.id }) { v ->
                VideoCard(v.copy(vertical = false), onClick = { nav.openVideo(v) })
            }
        }
    }
}

/** 可見的 WebView：Cloudflare 要人工驗證時，讓使用者用遙控器點完 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VerifyScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        Text(
            "完成網站驗證，看到首頁後按「返回」鍵回到 App，再按「重試」",
            color = Gray,
            fontSize = 15.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 10.dp),
        )
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
                    requestFocus()
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
