package com.h1tv.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

class App : Application() {
    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
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
}
