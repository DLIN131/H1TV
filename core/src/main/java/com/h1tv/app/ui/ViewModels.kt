package com.h1tv.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.h1tv.app.net.HomeRow
import com.h1tv.app.net.Site
import com.h1tv.app.net.VideoDetail
import kotlinx.coroutines.CancellationException
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
