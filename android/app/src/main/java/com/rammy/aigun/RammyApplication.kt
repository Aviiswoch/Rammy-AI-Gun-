package com.rammy.aigun

import android.app.Application
import com.serenegiant.utils.UVCUtils

class RammyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UVCUtils.init(this)
    }
}

