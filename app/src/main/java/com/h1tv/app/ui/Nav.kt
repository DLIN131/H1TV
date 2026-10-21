package com.h1tv.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.h1tv.app.net.Video

/** 頁面堆疊裡的頁面 */
sealed interface Route {
    data class Detail(val video: Video) : Route
    /** 列表頁：首頁「查看更多」、標籤、作者 */
    data class Listing(val title: String, val url: String) : Route
    /** 可見的 WebView，讓使用者手動過 Cloudflare 驗證 */
    data object Verify : Route
}

/** 全 App 共用的導覽動作，用 CompositionLocal 傳下去，不用一層層傳參數 */
class Nav(
    val openVideo: (Video) -> Unit,
    val openListing: (title: String, url: String) -> Unit,
    val openVerify: () -> Unit,
)

val LocalNav = staticCompositionLocalOf<Nav> { error("Nav 尚未提供") }
