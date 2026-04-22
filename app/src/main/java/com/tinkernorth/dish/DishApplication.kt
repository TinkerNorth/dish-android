package com.tinkernorth.dish

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.tinkernorth.dish.ui.bluetooth.BluetoothForegroundObserver
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class DishApplication : Application() {
    @Inject lateinit var btForegroundObserver: BluetoothForegroundObserver

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(btForegroundObserver)
    }
}
