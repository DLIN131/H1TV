package com.h1tv.app.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import com.h1tv.app.Prefs
import com.h1tv.app.net.Site
import com.h1tv.app.util.Updater
import com.h1tv.app.net.WebFetcher
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            H1Theme { AppRoot() }
        }
    }

    // 播放影片或退到背景時讓隱藏的 WebView 休息
    override fun onStart() {
        super.onStart()
        WebFetcher.resume()
    }

    override fun onStop() {
        super.onStop()
        WebFetcher.pause()
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    Home("首頁", Icons.Filled.Home),
    Browse("分類", Icons.AutoMirrored.Filled.List),
    Search("搜尋", Icons.Filled.Search),
    Favorites("收藏", Icons.Filled.Favorite),
    History("觀看紀錄", Icons.Filled.DateRange),
}

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var firstLaunch by remember { mutableStateOf(true) }
    // 頁面堆疊：影片詳情、列表頁，可以一直往下點
    val stack = remember { mutableStateListOf<Route>() }
    val nav = remember {
        Nav(
            openVideo = { stack.add(Route.Detail(it)) },
            openListing = { title, url -> stack.add(Route.Listing(title, url)) },
            openVerify = { stack.add(Route.Verify) },
        )
    }

    CompositionLocalProvider(LocalNav provides nav) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Bg)
        ) {
            val top = stack.lastOrNull()
            if (top == null) {
                HomeScreen(
                    tab = tab,
                    onTab = { tab = it },
                    focusDrawer = firstLaunch,
                    onFocused = { firstLaunch = false },
                )
            } else {
                key(stack.size, top) {
                    when (top) {
                        is Route.Detail -> DetailScreen(top.video)
                        is Route.Listing -> ListingScreen(top.title, top.url)
                        Route.Verify -> VerifyScreen()
                    }
                }
            }
        }
    }
    BackHandler(enabled = stack.isNotEmpty()) {
        if (stack.removeAt(stack.lastIndex) == Route.Verify) WebFetcher.reset()
    }

    // 開啟時檢查 GitHub 有沒有新版，由使用者決定要不要更新（debug 版的 versionCode 固定是 1，不自動檢查）
    LaunchedEffect(Unit) {
        if (!Updater.isDebugBuild) {
            runCatching { Updater.check() }.getOrNull()
                ?.takeIf { it.versionCode != Prefs.skippedVersion }
                ?.let { Updater.pending = it }
        }
    }
    Updater.pending?.let { release ->
        UpdateDialog(
            release,
            onDismiss = { Updater.pending = null },
            onSkip = {
                Prefs.skippedVersion = release.versionCode
                Updater.pending = null
            },
        )
    }
}

@Composable
fun HomeScreen(
    tab: Tab,
    onTab: (Tab) -> Unit,
    focusDrawer: Boolean,
    onFocused: () -> Unit,
) {
    val activity = LocalContext.current as Activity
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val requesters = remember { Tab.entries.associateWith { FocusRequester() } }
    var drawerFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (focusDrawer) {
            delay(100)
            runCatching { requesters.getValue(tab).requestFocus() }
            onFocused()
        }
    }

    // 內容區按返回鍵先回到側邊選單，在選單上再按返回才離開
    BackHandler {
        if (drawerFocused) activity.finish()
        else runCatching { requesters.getValue(tab).requestFocus() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { value ->
            Column(
                Modifier
                    .fillMaxHeight()
                    .background(if (value == DrawerValue.Open) Color(0xFF17171C) else Color.Transparent)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
                    .onFocusChanged { drawerFocused = it.hasFocus }
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Spacer(Modifier.height(16.dp))
                Text(
                    if (value == DrawerValue.Open) "H1TV" else "H",
                    color = Accent,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, bottom = 16.dp),
                )
                Tab.entries.forEach { t ->
                    NavigationDrawerItem(
                        selected = t == tab,
                        onClick = { onTab(t) },
                        leadingContent = { Icon(t.icon, contentDescription = null) },
                        modifier = Modifier.focusRequester(requesters.getValue(t)),
                    ) {
                        Text(t.label)
                    }
                }
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = 92.dp)
        ) {
            when (tab) {
                Tab.Home -> HomeRowsScreen()
                Tab.Browse -> BrowseScreen()
                Tab.Search -> SearchScreen()
                Tab.Favorites -> LocalListScreen("收藏", favorites = true)
                Tab.History -> LocalListScreen("觀看紀錄", favorites = false)
            }
        }
    }
}

/** 分類：類型 × 排序 */
@Composable
fun BrowseScreen() {
    val nav = LocalNav.current
    var genre by rememberSaveable { mutableStateOf(Site.genres.first()) }
    var sort by rememberSaveable { mutableStateOf("最新上市") }
    val vm: FeedViewModel = viewModel(key = "browse") { FeedViewModel(Site.searchUrl(genre = genre, sort = sort)) }
    fun reload() = vm.refresh(Site.searchUrl(genre = genre, sort = sort))

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("分類") {
            TvButton(onClick = ::reload) { Text("重新整理") }
        }
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
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(options) { o ->
            TvButton(onClick = { if (o != selected) onSelect(o) }, selected = o == selected) { Text(o) }
        }
    }
}

/** 「查看更多」、標籤、作者 */
@Composable
fun ListingScreen(title: String, url: String) {
    val nav = LocalNav.current
    val vm: FeedViewModel = viewModel(key = "list:$url") { FeedViewModel(url) }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(title)
        VideoGrid(vm, onClick = nav.openVideo)
    }
}
