package com.example.travelnow
import android.health.connect.datatypes.ExerciseRoute
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
class LocationViewModel(application: MyApplication) : AndroidViewModel(application) {
    private val _locationInformation = MutableLiveData<Location>()
    val locationInformation : LiveData<Location> = _locationInformation
    fun updateBtState(information: Location) {
        _locationInformation.value = information
    }
}