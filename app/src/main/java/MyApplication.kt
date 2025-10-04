package com.example.travelnow

import ViewModel.SafetyViewModel
import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.google.firebase.Firebase
import com.google.firebase.initialize

class MyApplication : Application() {
    private val viewModelStore = ViewModelStore()

    val safetyViewModel: SafetyViewModel by lazy {
        ViewModelProvider(
            viewModelStore,
            ViewModelProvider.NewInstanceFactory()
        )[SafetyViewModel::class.java]
    }

    override fun onCreate() {
        super.onCreate()
        Firebase.initialize(applicationContext)
    }

    override fun onTerminate() {
        super.onTerminate()
        viewModelStore.clear()
    }
}

