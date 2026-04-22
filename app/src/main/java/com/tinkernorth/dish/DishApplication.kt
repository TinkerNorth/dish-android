package com.tinkernorth.dish

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.tinkernorth.dish.data.network.ConnectionForegroundObserver
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class DishApplication : Application() {
    @Inject lateinit var connectionForegroundObserver: ConnectionForegroundObserver

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(connectionForegroundObserver)
    }
}
