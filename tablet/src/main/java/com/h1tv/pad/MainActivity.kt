package com.h1tv.pad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.h1tv.app.Prefs
import com.h1tv.app.net.WebFetcher
import com.h1tv.app.util.Updater

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Updater.apkName = "H1TVPad.apk"
        setContent {
            PadTheme { AppRoot() }
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
    History("紀錄", Icons.Filled.DateRange),
}

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    // 頁面堆疊：影片詳情、列表頁，可以一直往下點
    val stack = remember { mutableStateListOf<Route>() }
    val nav = remember {
        Nav(
            openVideo = { stack.add(Route.Detail(it)) },
            openListing = { title, url -> stack.add(Route.Listing(title, url)) },
            openVerify = { stack.add(Route.Verify) },
            back = {
                if (stack.isNotEmpty() && stack.removeAt(stack.lastIndex) == Route.Verify) WebFetcher.reset()
            },
        )
    }

    CompositionLocalProvider(LocalNav provides nav) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Bg)
                .safeDrawingPadding()
        ) {
            val top = stack.lastOrNull()
            if (top == null) {
                HomeScaffold(tab, onTab = { tab = it })
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
    BackHandler(enabled = stack.isNotEmpty()) { nav.back() }
    // 在其他分頁按返回先回首頁，首頁再按才離開
    BackHandler(enabled = stack.isEmpty() && tab != Tab.Home) { tab = Tab.Home }

    // 開啟時檢查 GitHub 有沒有新版，由使用者決定要不要更新
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

/** 寬螢幕（平板）用左側導覽列，窄螢幕（手機直拿）用底部導覽列 */
@Composable
fun HomeScaffold(tab: Tab, onTab: (Tab) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(Modifier.fillMaxHeight()) {
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = t == tab,
                            onClick = { onTab(t) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
                Box(Modifier.weight(1f)) { TabContent(tab) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { TabContent(tab) }
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = t == tab,
                            onClick = { onTab(t) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabContent(tab: Tab) {
    when (tab) {
        Tab.Home -> HomeRowsScreen()
        Tab.Browse -> BrowseScreen()
        Tab.Search -> SearchScreen()
        Tab.Favorites -> LocalListScreen("收藏", favorites = true)
        Tab.History -> LocalListScreen("觀看紀錄", favorites = false)
    }
}
