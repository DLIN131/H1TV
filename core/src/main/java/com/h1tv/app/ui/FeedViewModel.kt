package com.h1tv.app.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.h1tv.app.net.Site
import com.h1tv.app.net.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 網站列表頁的分頁載入（分類、排序、搜尋、標籤都是 /search?...&page=N） */
class FeedViewModel(private var url: String) : ViewModel() {
    val items = mutableStateListOf<Video>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var end by mutableStateOf(false)
        private set

    val gridState = LazyGridState()

    /** 點進詳情頁前聚焦的位置，返回時還原。 */
    var focusIndex = -1
    var restoreFocus = false

    private var page = 0
    private var generation = 0

    fun ensureLoaded() {
        if (items.isEmpty() && !loading && error == null && !end) loadMore()
    }

    fun loadMore() {
        if (loading || end) return
        val gen = ++generation
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val r = Site.list(url, page + 1)
                if (gen != generation) return@launch
                page++
                // 換頁時偶爾會重複出現上一頁的影片
                val seen = items.mapTo(HashSet()) { it.id }
                items.addAll(r.items.filter { it.id !in seen })
                if (!r.hasMore) end = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) error = e.message ?: "載入失敗"
            } finally {
                if (gen == generation) loading = false
            }
        }
    }

    fun refresh(newUrl: String = url) {
        url = newUrl
        generation++
        page = 0
        items.clear()
        loading = false
        error = null
        end = false
        focusIndex = -1
        restoreFocus = false
        viewModelScope.launch { gridState.scrollToItem(0) }
        loadMore()
    }
}
