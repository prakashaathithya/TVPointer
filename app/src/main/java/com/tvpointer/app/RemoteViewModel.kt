package com.tvpointer.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class RemoteViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("remote", Context.MODE_PRIVATE)

    val remote = HidRemote(app)

    private val _speed = MutableStateFlow(prefs.getFloat("speed", 1f))
    val speed: StateFlow<Float> = _speed

    fun setSpeed(value: Float) {
        _speed.value = value
        prefs.edit().putFloat("speed", value).apply()
    }

    override fun onCleared() = remote.stop()
}
