package com.example.travelnow

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.LOCATION_SERVICE
import android.location.LocationManager

class LocationUpdates(context: Context) {

    private val mGpsLocationClient: LocationManager =
        context.getSystemService(LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    fun start(){
        mGpsLocationClient.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            0L ,
            0f ,
            locationListener

        )
    }

    private val locationListener =
        android.location.LocationListener { location -> //handle location change

        }



}