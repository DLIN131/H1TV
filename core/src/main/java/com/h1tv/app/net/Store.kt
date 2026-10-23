package com.h1tv.app.net

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.h1tv.app.App
import org.json.JSONArray
import org.json.JSONObject

/** 本機的觀看紀錄與收藏（網站帳號不登入，全部存在電視上） */
object Store {
    private const val MAX_HISTORY = 300
    private val sp by lazy { App.instance.getSharedPreferences("store", Context.MODE_PRIVATE) }

    val history = mutableStateListOf<Video>().apply { addAll(load("history")) }
    val favorites = mutableStateListOf<Video>().apply { addAll(load("favorites")) }

    val searchWords = mutableStateListOf<String>().apply {
        addAll(sp.getString("search_words", "").orEmpty().split('\n').filter { it.isNotBlank() })
    }

    fun addSearchWord(word: String) {
        searchWords.remove(word)
        searchWords.add(0, word)
        while (searchWords.size > 20) searchWords.removeAt(searchWords.lastIndex)
        sp.edit().putString("search_words", searchWords.joinToString("\n")).apply()
    }

    fun addHistory(v: Video) {
        history.removeAll { it.id == v.id }
        history.add(0, v)
        while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
        save("history", history)
    }

    fun clearHistory() {
        history.clear()
        save("history", history)
        sp.edit().apply { sp.all.keys.filter { it.startsWith("pos_") }.forEach(::remove) }.apply()
    }

    fun isFavorite(id: String) = favorites.any { it.id == id }

    fun toggleFavorite(v: Video) {
        if (!favorites.removeAll { it.id == v.id }) favorites.add(0, v)
        save("favorites", favorites)
    }

    /** 播放進度（毫秒），看完會清掉 */
    fun position(id: String) = sp.getLong("pos_$id", 0L)

    fun savePosition(id: String, ms: Long) {
        sp.edit().apply { if (ms > 0) putLong("pos_$id", ms) else remove("pos_$id") }.apply()
    }

    private fun load(key: String): List<Video> = runCatching {
        val arr = JSONArray(sp.getString(key, "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Video(
                id = o.getString("id"),
                title = o.optString("title"),
                cover = o.optString("cover"),
                vertical = o.optBoolean("vertical"),
                duration = o.optString("duration"),
                author = o.optString("author"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(key: String, list: List<Video>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("cover", it.cover)
                    .put("vertical", it.vertical)
                    .put("duration", it.duration)
                    .put("author", it.author)
            )
        }
        sp.edit().putString(key, arr.toString()).apply()
    }
}
