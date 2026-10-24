package com.h1tv.app

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.h1tv.app.net.Store
import kotlin.concurrent.thread

class App : Application(), ImageLoaderFactory {
    companion object {
        lateinit var instance: App
            private set

        private const val MB = 1024L * 1024
    }

    /** 電視盒子記憶體和儲存空間都小，快取上限要壓低 */
    val isTv: Boolean by lazy {
        (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        thread(name = "cache-cleanup", priority = Thread.MIN_PRIORITY) { cleanUp() }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this).maxSizePercent(if (isTv) 0.10 else 0.20).build()
        }
        .diskCache {
            // 縮圖網址每幾週就會換，快取留太多也用不到
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(if (isTv) 30 * MB else 80 * MB)
                .build()
        }
        // 電視上縮圖用 RGB_565，每張圖記憶體少一半，看不太出差別
        .allowRgb565(isTv)
        .crossfade(false)
        .respectCacheHeaders(false)
        .build()

    /** 每次開 App 在背景清一次：舊的更新安裝檔、舊版留下的圖片快取、過期的播放進度 */
    private fun cleanUp() = runCatching {
        cacheDir.resolve("updates").deleteRecursively()
        // 舊版用 Coil 預設的快取資料夾（上限 250 MB），改成上面的小快取後就用不到了
        cacheDir.resolve("image_cache").deleteRecursively()
        Store.prunePositions()
    }
}

/** 偏好設定 */
object Prefs {
    private val sp: SharedPreferences by lazy {
        App.instance.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    }

    /** 預設畫質（找不到就選最接近且較低的） */
    var quality: Int
        get() = sp.getInt("quality", 1080)
        set(value) = sp.edit().putInt("quality", value).apply()

    /** 使用者選「略過這個版本」的 versionCode，自動檢查時不再提示這一版 */
    var skippedVersion: Int
        get() = sp.getInt("skipped_version", 0)
        set(value) = sp.edit().putInt("skipped_version", value).apply()
}
