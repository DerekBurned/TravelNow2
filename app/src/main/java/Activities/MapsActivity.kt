package com.example.travelnow

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.travelnow.databinding.ActivityMapsBinding
import com.example.travelnow.databinding.DialogSafetyReportBinding
import com.example.travelnow.databinding.BottomSheetReportsBinding
import models.SafetyLevel
import models.SafetyReport
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import java.util.Locale

class MapsActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var binding: ActivityMapsBinding
    private lateinit var map: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var placesClient: PlacesClient
    private lateinit var auth: FirebaseAuth

    private var currentMarker: Marker? = null
    private var currentLocation: Location? = null
    private val safetyCircles = mutableListOf<Circle>()
    private var sessionToken: AutocompleteSessionToken? = null
    private val predictions = mutableListOf<AutocompletePrediction>()
    private var locationCancellationToken: CancellationTokenSource? = null

    private val viewModel by lazy {
        (application as MyApplication).safetyViewModel
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        binding.progressBar.visibility = View.VISIBLE

        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnSuccessListener {
                    Log.d(TAG, "Signed in anonymously")
                    binding.progressBar.visibility = View.GONE
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Anonymous sign-in failed", e)
                    Toast.makeText(this, "Authentication failed: ${e.message}", Toast.LENGTH_LONG).show()
                    binding.progressBar.visibility = View.GONE
                }
        } else {
            binding.progressBar.visibility = View.GONE
        }

        // Initializing Location Services
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Initializing Places API
        initializePlaces()

        // Setup Map
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        // Setup UI
        setupUI()
        setupObservers()
    }

    private fun initializePlaces() {
        try {
            val appInfo = packageManager.getApplicationInfo(
                packageName,
                PackageManager.GET_META_DATA
            )
            val apiKey = appInfo.metaData.getString("com.google.android.geo.API_KEY")

            if (!Places.isInitialized() && apiKey != null) {
                Places.initializeWithNewPlacesApiEnabled(applicationContext, apiKey)
            }
            placesClient = Places.createClient(this)
            sessionToken = AutocompleteSessionToken.newInstance()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Places: ${e.message}")
        }
    }

    private fun setupUI() {
        with(binding) {
            // Setup AutoComplete Search
            val adapter = ArrayAdapter<String>(
                this@MapsActivity,
                android.R.layout.simple_dropdown_item_1line
            )
            autoCompleteSearch.setAdapter(adapter)
            autoCompleteSearch.threshold = 1

            // Text change listener for autocomplete
            autoCompleteSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!s.isNullOrEmpty()) {
                        clearSearch.visibility = View.VISIBLE
                        getPlacePredictions(s.toString(), adapter)
                    } else {
                        clearSearch.visibility = View.GONE
                    }
                }

                override fun afterTextChanged(s: Editable?) {}
            })

            // Item click listener
            autoCompleteSearch.setOnItemClickListener { _, _, position, _ ->
                predictions.getOrNull(position)?.let { prediction ->
                    getPlaceDetails(prediction)
                }
                autoCompleteSearch.clearFocus()
            }

            // Search action button
            autoCompleteSearch.setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    val query = v.text.toString()
                    if (query.isNotEmpty()) {
                        searchLocation(query)
                    }
                    autoCompleteSearch.clearFocus()
                    true
                } else false
            }

            // Clear search button
            clearSearch.setOnClickListener {
                autoCompleteSearch.text.clear()
                clearSearch.visibility = View.GONE
            }

            // FAB buttons
            fabMyLocation.setOnClickListener { getCurrentLocation() }
            fabMapType.setOnClickListener { showMapTypeDialog() }
            fabClearMarkers.setOnClickListener { clearAllSafetyCircles() }
            fabZoomIn.setOnClickListener {
                map.animateCamera(CameraUpdateFactory.zoomIn())
            }
            fabZoomOut.setOnClickListener {
                map.animateCamera(CameraUpdateFactory.zoomOut())
            }
            fabShowReports.setOnClickListener { showReportsBottomSheet() }
            fabRefresh.setOnClickListener { refreshReports() }
        }
    }

    private fun setupObservers() {
        // Observe reports
        viewModel.reports.observe(this) { reports ->
            updateMapWithReports(reports)
        }

        // Observe loading state
        viewModel.loading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        // Observe errors
        viewModel.error.observe(this) { error ->
            error?.let {
                Toast.makeText(this, "Error: $it", Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }

        // Observe submit success
        viewModel.submitSuccess.observe(this) { success ->
            if (success) {
                Toast.makeText(this, "Report submitted successfully!", Toast.LENGTH_SHORT).show()
                viewModel.resetSubmitSuccess()
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap

        // Configure map settings
        map.mapType = GoogleMap.MAP_TYPE_NORMAL
        map.uiSettings.apply {
            isZoomControlsEnabled = false
            isCompassEnabled = true
            isMyLocationButtonEnabled = false
            isMapToolbarEnabled = true
            isZoomGesturesEnabled = true
            isScrollGesturesEnabled = true
            isTiltGesturesEnabled = true
            isRotateGesturesEnabled = true
        }

        checkLocationPermission()

        // Long press to create safety report
        map.setOnMapLongClickListener { latLng ->
            showSafetyReportDialog(latLng)
            Log.d("Long Press", "Map was long pressed")
        }

        // Click on map to select location
        map.setOnMapClickListener { latLng ->
            currentMarker?.remove()
            currentMarker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("Selected Location")
                    .snippet("Long press to report safety status")
                    .draggable(true)
            )
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
            getAddressFromLocation(latLng)
        }

        // Camera idle listener - load reports when camera stops
        map.setOnCameraIdleListener {
            val center = map.cameraPosition.target
            val zoom = map.cameraPosition.zoom

            // Only load reports if zoomed in enough
            if (zoom >= 10f) {
                val radiusKm = when {
                    zoom >= 15f -> 5.0
                    zoom >= 12f -> 20.0
                    else -> 50.0
                }
                viewModel.loadNearbyReports(center.latitude, center.longitude, radiusKm)
            }
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

        // Cancel previous request
        locationCancellationToken?.cancel()
        locationCancellationToken = CancellationTokenSource()

        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            locationCancellationToken!!.token
        ).addOnSuccessListener { location: Location? ->
            binding.progressBar.visibility = View.GONE

            if (location != null) {
                currentLocation = location
                val currentLatLng = LatLng(location.latitude, location.longitude)
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 14f))

                // Load nearby reports
                viewModel.loadNearbyReports(location.latitude, location.longitude)
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
                Log.e(TAG, "Place prediction error: ${exception.message}")
            }
    }

    private fun getPlaceDetails(prediction: AutocompletePrediction) {
        try {
            val geocoder = Geocoder(this, Locale.getDefault())
            val locationName = prediction.getFullText(null).toString()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocationName(locationName, 1) { addresses ->
                    if (addresses.isNotEmpty()) {
                        val address = addresses[0]
                        val latLng = LatLng(address.latitude, address.longitude)

                        runOnUiThread {
                            updateMarkerAndCamera(
                                latLng,
                                prediction.getPrimaryText(null).toString(),
                                "Long press to report safety"
                            )
                            viewModel.loadNearbyReports(latLng.latitude, latLng.longitude)
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(locationName, 1)
                if (addresses != null && addresses.isNotEmpty()) {
                    val address = addresses[0]
                    val latLng = LatLng(address.latitude, address.longitude)

                    updateMarkerAndCamera(
                        latLng,
                        prediction.getPrimaryText(null).toString(),
                        "Long press to report safety"
                    )
                    viewModel.loadNearbyReports(latLng.latitude, latLng.longitude)
                }
            }

            // Regenerate session token
            sessionToken = AutocompleteSessionToken.newInstance()
            predictions.clear()
            binding.autoCompleteSearch.text.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Place details error: ${e.message}")
            Toast.makeText(this, "Could not get place details", Toast.LENGTH_SHORT).show()
        }
    }

    private fun searchLocation(query: String) {
        try {
            val geocoder = Geocoder(this, Locale.getDefault())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocationName(query, 1) { addresses ->
                    if (addresses.isNotEmpty()) {
                        val address = addresses[0]
                        val latLng = LatLng(address.latitude, address.longitude)

                        runOnUiThread {
                            updateMarkerAndCamera(
                                latLng,
                                query,
                                address.getAddressLine(0)
                            )
                            viewModel.loadNearbyReports(latLng.latitude, latLng.longitude)
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(query, 1)
                if (addresses != null && addresses.isNotEmpty()) {
                    val address = addresses[0]
                    val latLng = LatLng(address.latitude, address.longitude)

                    updateMarkerAndCamera(
                        latLng,
                        query,
                        address.getAddressLine(0)
                    )
                    viewModel.loadNearbyReports(latLng.latitude, latLng.longitude)
                } else {
                    Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Search error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMarkerAndCamera(latLng: LatLng, title: String, snippet: String) {
        currentMarker?.remove()
        currentMarker = map.addMarker(
            MarkerOptions()
                .position(latLng)
                .title(title)
                .snippet(snippet)
        )
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 14f))
    }

    private fun showSafetyReportDialog(latLng: LatLng) {
        val dialogBinding = DialogSafetyReportBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setTitle("Report Safety Status")
            .create()

        // Get area name
        var areaName = "Unknown Location"
        getAddressFromLocationForReport(latLng) { address ->
            areaName = address
        }

        with(dialogBinding) {
            btnSafe.setOnClickListener {
                submitReport(latLng, areaName, SafetyLevel.SAFE, etComment.text.toString())
                dialog.dismiss()
            }

            btnCautious.setOnClickListener {
                submitReport(latLng, areaName, SafetyLevel.BE_CAUTIOUS, etComment.text.toString())
                dialog.dismiss()
            }

            btnUnsafe.setOnClickListener {
                submitReport(latLng, areaName, SafetyLevel.UNSAFE, etComment.text.toString())
                dialog.dismiss()
            }

            btnDangerous.setOnClickListener {
                submitReport(latLng, areaName, SafetyLevel.DANGEROUS, etComment.text.toString())
                dialog.dismiss()
            }

            btnCancel.setOnClickListener {
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun submitReport(latLng: LatLng, areaName: String, level: SafetyLevel, comment: String) {
        if (comment.isBlank()) {
            Toast.makeText(this, "Please add a comment", Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.submitReport(
            latLng.latitude,
            latLng.longitude,
            areaName,
            level.name,
            comment
        )
    }

    private fun updateMapWithReports(reports: List<SafetyReport>) {
        // Clear existing circles
        safetyCircles.forEach { it.remove() }
        safetyCircles.clear()

        // Add new circles
        reports.forEach { report ->
            val latLng = LatLng(report.latitude, report.longitude)
            val safetyLevel = report.getSafetyLevelEnum()

            val circle = map.addCircle(
                CircleOptions()
                    .center(latLng)
                    .radius(500.0) // 500 meters
                    .strokeWidth(3f)
                    .strokeColor(safetyLevel.color)
                    .fillColor(safetyLevel.colorWithAlpha)
                    .clickable(true)
            )

            safetyCircles.add(circle)

            // Add a small marker for the report
            map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title(safetyLevel.displayName)
                    .snippet(report.comment)
                    .icon(BitmapDescriptorFactory.defaultMarker(
                        when (safetyLevel) {
                            SafetyLevel.SAFE -> BitmapDescriptorFactory.HUE_GREEN
                            SafetyLevel.BE_CAUTIOUS -> BitmapDescriptorFactory.HUE_YELLOW
                            SafetyLevel.UNSAFE -> BitmapDescriptorFactory.HUE_ORANGE
                            SafetyLevel.DANGEROUS -> BitmapDescriptorFactory.HUE_RED
                            else -> BitmapDescriptorFactory.HUE_VIOLET
                        }
                    ))
            )
        }

        Log.d(TAG, "Updated map with ${reports.size} reports")
    }

    private fun showReportsBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val sheetBinding = BottomSheetReportsBinding.inflate(layoutInflater)

        val reports = viewModel.reports.value ?: emptyList()

        with(sheetBinding) {
            if (reports.isEmpty()) {
                recyclerViewReports.visibility = View.GONE
                tvNoReports.visibility = View.VISIBLE
            } else {
                recyclerViewReports.visibility = View.VISIBLE
                tvNoReports.visibility = View.GONE

                // Setup RecyclerView adapter here
                // You'll need to create SafetyReportAdapter
            }

            btnClose.setOnClickListener {
                bottomSheetDialog.dismiss()
            }
        }

        bottomSheetDialog.setContentView(sheetBinding.root)
        bottomSheetDialog.show()
    }

    private fun getAddressFromLocation(latLng: LatLng) {
        try {
            val geocoder = Geocoder(this, Locale.getDefault())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1) { addresses ->
                    if (addresses.isNotEmpty()) {
                        val address = addresses[0]
                        val addressText = address.getAddressLine(0)
                        runOnUiThread {
                            currentMarker?.snippet = addressText
                            currentMarker?.showInfoWindow()
                            Toast.makeText(this, addressText, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                if (addresses != null && addresses.isNotEmpty()) {
                    val address = addresses[0]
                    val addressText = address.getAddressLine(0)
                    currentMarker?.snippet = addressText
                    currentMarker?.showInfoWindow()
                    Toast.makeText(this, addressText, Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Geocoder error: ${e.message}")
        }
    }

    private fun getAddressFromLocationForReport(latLng: LatLng, callback: (String) -> Unit) {
        try {
            val geocoder = Geocoder(this, Locale.getDefault())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1) { addresses ->
                    if (addresses.isNotEmpty()) {
                        callback(addresses[0].getAddressLine(0) ?: "Unknown Location")
                    } else {
                        callback("Unknown Location")
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                if (addresses != null && addresses.isNotEmpty()) {
                    callback(addresses[0].getAddressLine(0) ?: "Unknown Location")
                } else {
                    callback("Unknown Location")
                }
            }
        } catch (e: Exception) {
            callback("Unknown Location")
        }
    }

    private fun showMapTypeDialog() {
        val options = arrayOf("Normal", "Satellite", "Terrain", "Hybrid")
        AlertDialog.Builder(this)
            .setTitle("Select Map Type")
            .setItems(options) { _, which ->
                map.mapType = when (which) {
                    0 -> GoogleMap.MAP_TYPE_NORMAL
                    1 -> GoogleMap.MAP_TYPE_SATELLITE
                    2 -> GoogleMap.MAP_TYPE_TERRAIN
                    3 -> GoogleMap.MAP_TYPE_HYBRID
                    else -> GoogleMap.MAP_TYPE_NORMAL
                }
            }
            .show()
    }

    private fun clearAllSafetyCircles() {
        safetyCircles.forEach { it.remove() }
        safetyCircles.clear()
        currentMarker?.remove()
        currentMarker = null
        Toast.makeText(this, "All safety zones cleared", Toast.LENGTH_SHORT).show()
    }

    private fun refreshReports() {
        val center = map.cameraPosition.target
        viewModel.loadNearbyReports(center.latitude, center.longitude)
        Toast.makeText(this, "Refreshing reports...", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCancellationToken?.cancel()
        Log.d(TAG, "MapsActivity destroyed")
    }

    companion object {
        private const val TAG = "MapsActivity"
    }
}