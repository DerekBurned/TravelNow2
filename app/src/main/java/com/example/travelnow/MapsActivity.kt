package com.example.travelnow

import android.Manifest
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.travelnow.databinding.ActivityMapsBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import java.util.Locale

class MapsActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var binding: ActivityMapsBinding
    private lateinit var map: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var placesClient: PlacesClient
    private var currentMarker: Marker? = null
    private var currentLocation: Location? = null
    private val markers = mutableListOf<Marker>()
    private var sessionToken: AutocompleteSessionToken? = null
    private val predictions = mutableListOf<AutocompletePrediction>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val appInfo = packageManager.getApplicationInfo(
            packageName,
            PackageManager.GET_META_DATA
        )
        val apiKey = appInfo.metaData.getString("com.google.android.geo.API_KEY")
        // Initialize Places API
        if (!Places.isInitialized()) {
            Places.initializeWithNewPlacesApiEnabled(applicationContext, apiKey) // Replace with your API key
        }
        placesClient = Places.createClient(this)
        sessionToken = AutocompleteSessionToken.newInstance()

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        setupUI()
    }

    private fun setupUI() {
        // Setup AutoComplete Search
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line)
        binding.autoCompleteSearch.setAdapter(adapter)
        binding.autoCompleteSearch.threshold = 1 // Start suggesting after 1 character

        // Text change listener for autocomplete
        binding.autoCompleteSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!s.isNullOrEmpty()) {
                    binding.clearSearch.visibility = View.VISIBLE
                    getPlacePredictions(s.toString(), adapter)
                } else {
                    binding.clearSearch.visibility = View.GONE
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // Item click listener
        binding.autoCompleteSearch.setOnItemClickListener { parent, _, position, _ ->
            val selectedPlace = parent.getItemAtPosition(position) as String
            val prediction = predictions.getOrNull(position)

            if (prediction != null) {
                getPlaceDetails(prediction.placeId)
            }

            binding.autoCompleteSearch.clearFocus()
        }

        // Action button (search/enter key)
        binding.autoCompleteSearch.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = v.text.toString()
                if (query.isNotEmpty()) {
                    searchLocation(query)
                }
                binding.autoCompleteSearch.clearFocus()
                true
            } else {
                false
            }
        }

        // Clear search button
        binding.clearSearch.setOnClickListener {
            binding.autoCompleteSearch.text.clear()
            binding.clearSearch.visibility = View.GONE
        }

        // FAB buttons
        binding.fabMyLocation.setOnClickListener {
            getCurrentLocation()
        }

        binding.fabMapType.setOnClickListener {
            showMapTypeDialog()
        }

        binding.fabClearMarkers.setOnClickListener {
            clearAllMarkers()
        }

        binding.fabZoomIn.setOnClickListener {
            map.animateCamera(CameraUpdateFactory.zoomIn())
        }

        binding.fabZoomOut.setOnClickListener {
            map.animateCamera(CameraUpdateFactory.zoomOut())
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap

        // Configure map settings
        map.mapType = GoogleMap.MAP_TYPE_NORMAL
        map.uiSettings.apply {
            isZoomControlsEnabled = false // We have custom zoom buttons
            isCompassEnabled = true
            isMyLocationButtonEnabled = false // We have custom location button
            isMapToolbarEnabled = true
            isZoomGesturesEnabled = true
            isScrollGesturesEnabled = true
            isTiltGesturesEnabled = true
            isRotateGesturesEnabled = true
        }

        checkLocationPermission()

        // Long press to add marker
        map.setOnMapLongClickListener { latLng ->
            addMarker(latLng, "Custom Pin", "Long press location")
        }

        // Click on map to add temporary marker
        map.setOnMapClickListener { latLng ->
            currentMarker?.remove()
            currentMarker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("Selected Location")
                    .snippet("Tap again to confirm")
                    .draggable(true)
            )
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
            getAddressFromLocation(latLng)
        }

        // Marker click listener
        map.setOnMarkerClickListener { marker ->
            marker.showInfoWindow()
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, 17f))
            true
        }

        // Info window click listener
        map.setOnInfoWindowClickListener { marker ->
            showMarkerOptions(marker)
        }

        // Marker drag listener
        map.setOnMarkerDragListener(object : GoogleMap.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker) {
                Toast.makeText(this@MapsActivity, "Dragging...", Toast.LENGTH_SHORT).show()
            }

            override fun onMarkerDrag(marker: Marker) {}

            override fun onMarkerDragEnd(marker: Marker) {
                Toast.makeText(
                    this@MapsActivity,
                    "New position: ${marker.position.latitude}, ${marker.position.longitude}",
                    Toast.LENGTH_SHORT
                ).show()
                getAddressFromLocation(marker.position)
            }
        })

        // Camera move listener
        map.setOnCameraMoveListener {
            // Update UI based on camera position if needed
        }

        // Camera idle listener
        map.setOnCameraIdleListener {
            val center = map.cameraPosition.target
            Log.d("MapActivity", "Camera idle at: ${center.latitude}, ${center.longitude}")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            enableMyLocation()
            getCurrentLocation()
        } else {
            Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                enableMyLocation()
                getCurrentLocation()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun enableMyLocation() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            map.isMyLocationEnabled = true
        }
    }

    private fun getCurrentLocation() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        binding.progressBar.visibility = View.VISIBLE

        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            CancellationTokenSource().token
        ).addOnSuccessListener { location: Location? ->
            binding.progressBar.visibility = View.GONE

            if (location != null) {
                currentLocation = location
                val currentLatLng = LatLng(location.latitude, location.longitude)

                map.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
                Toast.makeText(this, "Location found!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Cannot get location. Enable GPS.", Toast.LENGTH_SHORT).show()
            }
        }.addOnFailureListener { exception ->
            binding.progressBar.visibility = View.GONE
            Toast.makeText(this, "Failed: ${exception.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getPlacePredictions(query: String, adapter: ArrayAdapter<String>) {
        val request = FindAutocompletePredictionsRequest.builder()
            .setSessionToken(sessionToken)
            .setQuery(query)
            .build()

        placesClient.findAutocompletePredictions(request)
            .addOnSuccessListener { response ->
                predictions.clear()
                predictions.addAll(response.autocompletePredictions)

                val suggestionsList = response.autocompletePredictions.map {
                    it.getFullText(null).toString()
                }

                adapter.clear()
                adapter.addAll(suggestionsList)
                adapter.notifyDataSetChanged()
            }
            .addOnFailureListener { exception ->
                Log.e("MapActivity", "Place prediction error: ${exception.message}")
            }
    }

    private fun getPlaceDetails(placeId: String) {
        // Use Geocoder to get coordinates from place ID
        try {
            val geocoder = Geocoder(this, Locale.getDefault())

            // For simplicity, we'll search the first prediction text
            val prediction = predictions.find { it.placeId == placeId }
            prediction?.let {
                val addresses = geocoder.getFromLocationName(
                    it.getFullText(null).toString(),
                    1
                )

                if (addresses != null && addresses.isNotEmpty()) {
                    val address = addresses[0]
                    val latLng = LatLng(address.latitude, address.longitude)

                    addMarker(
                        latLng,
                        it.getPrimaryText(null).toString(),
                        it.getSecondaryText(null).toString()
                    )
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 14f))

                    // Regenerate session token
                    sessionToken = AutocompleteSessionToken.newInstance()
                }
            }
        } catch (e: Exception) {
            Log.e("MapActivity", "Place details error: ${e.message}")
            Toast.makeText(this, "Could not get place details", Toast.LENGTH_SHORT).show()
        }
    }

    private fun searchLocation(query: String) {
        try {
            val geocoder = Geocoder(this, Locale.getDefault())
            val addresses = geocoder.getFromLocationName(query, 1)

            if (addresses != null && addresses.isNotEmpty()) {
                val address = addresses[0]
                val latLng = LatLng(address.latitude, address.longitude)

                addMarker(latLng, query, address.getAddressLine(0))
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 14f))
            } else {
                Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Search error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addMarker(latLng: LatLng, title: String, snippet: String) {
        val marker = map.addMarker(
            MarkerOptions()
                .position(latLng)
                .title(title)
                .snippet(snippet)
                .draggable(true)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )
        marker?.let { markers.add(it) }
        marker?.showInfoWindow()
    }

    private fun getAddressFromLocation(latLng: LatLng) {
        try {
            val geocoder = Geocoder(this, Locale.getDefault())
            val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)

            if (addresses != null && addresses.isNotEmpty()) {
                val address = addresses[0]
                val addressText = address.getAddressLine(0)
                currentMarker?.snippet = addressText
                currentMarker?.showInfoWindow()
                Toast.makeText(this, addressText, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("MapActivity", "Geocoder error: ${e.message}")
        }
    }

    private fun showMapTypeDialog() {
        val options = arrayOf("Normal", "Satellite", "Terrain", "Hybrid")
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Select Map Type")
        builder.setItems(options) { _, which ->
            when (which) {
                0 -> map.mapType = GoogleMap.MAP_TYPE_NORMAL
                1 -> map.mapType = GoogleMap.MAP_TYPE_SATELLITE
                2 -> map.mapType = GoogleMap.MAP_TYPE_TERRAIN
                3 -> map.mapType = GoogleMap.MAP_TYPE_HYBRID
            }
        }
        builder.show()
    }

    private fun showMarkerOptions(marker: Marker) {
        val options = arrayOf("Delete Marker", "Get Directions", "Share Location")
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle(marker.title ?: "Marker Options")
        builder.setItems(options) { _, which ->
            when (which) {
                0 -> {
                    marker.remove()
                    markers.remove(marker)
                    Toast.makeText(this, "Marker deleted", Toast.LENGTH_SHORT).show()
                }
                1 -> {
                    Toast.makeText(this, "Directions feature - integrate with Google Maps", Toast.LENGTH_SHORT).show()
                }
                2 -> {
                    val shareText = "Location: ${marker.position.latitude}, ${marker.position.longitude}"
                    Toast.makeText(this, shareText, Toast.LENGTH_LONG).show()
                }
            }
        }
        builder.show()
    }

    private fun clearAllMarkers() {
        markers.forEach { it.remove() }
        markers.clear()
        currentMarker?.remove()
        currentMarker = null
        Toast.makeText(this, "All markers cleared", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MapActivity", "MapActivity destroyed")
    }
}