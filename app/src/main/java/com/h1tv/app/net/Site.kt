package com.h1tv.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

data class Video(
    val id: String,
    val title: String,
    val cover: String,
    /** 有些類型是直式海報，其他是 16:9 縮圖 */
    val vertical: Boolean,
    val duration: String = "",
    val views: String = "",
    val likes: String = "",
    val author: String = "",
)

data class HomeRow(val title: String, val moreUrl: String, val items: List<Video>)

data class Source(val quality: Int, val url: String)

data class Tag(val name: String, val url: String)

data class VideoDetail(
    val id: String,
    val title: String,
    val artist: String,
    val description: String,
    val info: String,
    val poster: String,
    val tags: List<Tag>,
    val sources: List<Source>,
    val playlist: List<Video>,
    val related: List<Video>,
)

data class Page(val items: List<Video>, val hasMore: Boolean)

/** 網站沒有 API，全部從 HTML 解析；網站改版時要改的就是這個檔案 */
object Site {
    // 類型名稱要跟網站的查詢參數完全一致
    val genres: List<String> = decode("6KOP55WqLOazoem6teeVqixNb3Rpb24gQW5pbWUsM0RDRywyLjVELDJE5YuV55WrLEFJ55Sf5oiQLE1NRCxDb3NwbGF5").split(",")
    val sorts = listOf("最新上市", "最新上傳", "本日排行", "本週排行", "本月排行", "觀看次數", "讚好比例")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun searchUrl(query: String = "", genre: String = "", sort: String = ""): String {
        val params = buildList {
            if (query.isNotBlank()) add("query=${enc(query.trim())}")
            if (genre.isNotEmpty()) add("genre=${enc(genre)}")
            if (sort.isNotEmpty()) add("sort=${enc(sort)}")
        }
        return "${WebFetcher.BASE}/search" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }

    private suspend fun doc(url: String): Document {
        val html = WebFetcher.get(url)
        return withContext(Dispatchers.Default) { Jsoup.parse(html, WebFetcher.BASE) }
    }

    suspend fun home(): List<HomeRow> {
        val d = doc("/")
        return d.select("a.horizontal-row-title").mapNotNull { header ->
            val title = header.selectFirst("h3")?.ownText()?.trim().orEmpty()
            // 標題後面到下一個標題之間的影片都屬於這一列
            val links = mutableListOf<Element>()
            var n = header.nextElementSibling()
            while (n != null && !(n.tagName() == "a" && n.hasClass("horizontal-row-title"))) {
                links += n.select("a[href*=watch?v=]")
                n = n.nextElementSibling()
            }
            val items = parseCards(links)
            if (title.isEmpty() || items.isEmpty()) null else HomeRow(title, header.absUrl("href"), items)
        }
    }

    /** 搜尋／分類／標籤頁，url 是不含 page 的網址 */
    suspend fun list(url: String, page: Int): Page {
        val sep = if (url.contains('?')) "&" else "?"
        val d = doc("$url${sep}page=$page")
        val items = parseCards(d.select("a[href*=watch?v=]"))
        val hasMore = items.isNotEmpty() && d.select("a[href*=page=${page + 1}]").isNotEmpty()
        return Page(items, hasMore)
    }

    suspend fun detail(id: String): VideoDetail {
        val d = doc("/watch?v=$id")
        val sources = d.select("video source[src]").mapNotNull { s ->
            val q = s.attr("size").toIntOrNull() ?: Regex("(\\d+)p").find(s.attr("src"))?.groupValues?.get(1)?.toIntOrNull()
            q?.let { Source(it, s.absUrl("src")) }
        }.distinctBy { it.quality }.sortedByDescending { it.quality }
        val tags = d.select(".single-video-tag a[href]").mapNotNull { a ->
            val name = a.ownText().replace(' ', ' ').trim().removePrefix("#").trim()
            if (name.isEmpty() || name == "add" || name == "remove") null else Tag(name, a.absUrl("href"))
        }
        val info = d.select(".video-description-panel .hidden-xs").firstOrNull { it.text().contains("觀看次數") }
            ?.text()?.trim().orEmpty()
        return VideoDetail(
            id = id,
            title = d.selectFirst("#shareBtn-title")?.text()?.trim()
                ?: d.title().substringBefore(" - ").trim(),
            artist = d.selectFirst("#video-artist-name")?.text()?.trim().orEmpty(),
            description = d.selectFirst(".video-caption-text")?.wholeText()?.trim().orEmpty(),
            info = info,
            poster = d.selectFirst("video[poster]")?.absUrl("poster").orEmpty(),
            tags = tags,
            sources = sources,
            playlist = parseCards(d.select("#playlist-scroll a[href*=watch?v=]")),
            related = parseCards(d.select("#related-tabcontent a[href*=watch?v=]")),
        )
    }

    /** 把一堆指向 watch?v= 的連結整理成影片卡片（同一部的縮圖連結和標題連結會合併） */
    private fun parseCards(links: List<Element>): List<Video> {
        val out = LinkedHashMap<String, Video>()
        for (a in links) {
            val id = a.attr("href").substringAfter("watch?v=", "").takeWhile { it.isLetterOrDigit() }
            if (id.isEmpty()) continue
            val prev = out[id]
            // 往上找包住這張卡片的容器（裡面有標題），最多 4 層
            val card = a.parents().take(4).firstOrNull {
                it.hasClass("video-item-container") ||
                    it.selectFirst(".video-title, .title, .home-rows-videos-title") != null
            } ?: a
            val img = a.selectFirst("img") ?: card.selectFirst("img")
            val cover = img?.absUrl("src").orEmpty()
            val title = a.selectFirst(".title, .home-rows-videos-title")?.text()?.trim()
                ?: card.selectFirst(".video-title, .title, .home-rows-videos-title")?.text()?.trim()
                ?: card.attr("title").trim().ifEmpty { a.attr("title").trim() }
            val stats = (a.select(".stat-item") + card.select(".stat-item")).map { it.text().trim() }.distinct()
            val v = Video(
                id = id,
                title = title.ifEmpty { prev?.title.orEmpty() },
                cover = cover.ifEmpty { prev?.cover.orEmpty() },
                vertical = cover.contains("/cover/") || prev?.vertical == true,
                duration = (a.selectFirst(".duration") ?: card.selectFirst(".duration"))?.text()?.trim()
                    ?: prev?.duration.orEmpty(),
                likes = stats.firstOrNull { it.contains('%') }?.replace("thumb_up", "")?.trim() ?: prev?.likes.orEmpty(),
                views = stats.firstOrNull { it.contains('次') } ?: prev?.views.orEmpty(),
                author = card.selectFirst(".subtitle a")?.text()?.trim() ?: prev?.author.orEmpty(),
            )
            out[id] = v
        }
        // 廣告或沒有封面的雜訊連結就不要了
        return out.values.filter { it.cover.isNotEmpty() || it.title.isNotEmpty() }
    }
}
