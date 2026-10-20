package com.remoteesp32midi.glassmidi

import android.app.Application

class GlassMidiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
