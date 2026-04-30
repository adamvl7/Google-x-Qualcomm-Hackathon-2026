package com.fitform.app

import android.app.Application
import com.fitform.app.storage.SessionRepository

class FitFormApp : Application() {
    val sessionRepository: SessionRepository by lazy { SessionRepository(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @Volatile lateinit var instance: FitFormApp
            private set
    }
}
