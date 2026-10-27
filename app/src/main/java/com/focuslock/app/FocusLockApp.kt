package com.focuslock.app

import android.app.Application

class FocusLockApp : Application() {

    lateinit var prefs: BlockPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = BlockPreferences(this)
    }

    companion object {
        lateinit var instance: FocusLockApp
            private set
    }
}
