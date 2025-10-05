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
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.travelnow.databinding.ActivityMapsBinding
import com.example.travelnow.databinding.DialogSafetyReportBinding
import com.example.travelnow.databinding.BottomSheetReportsBinding
import com.example.travelnow.models.SortOptions
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
    private var centerLocation: LatLng? = null

    private val reportMarkers = mutableMapOf<String, Marker>()
    private val reportCircles = mutableMapOf<String, Circle>()
    private var focusedReportId: String? = null

    private var sessionToken: AutocompleteSessionToken? = null
    private val predictions = mutableListOf<AutocompletePrediction>()
    private var locationCancellationToken: CancellationTokenSource? = null

    // Store pending reports until map is ready
    private var pendingReports: List<SafetyReport>? = null

    private val viewModel by lazy {
        (application as MyApplication).safetyViewModel
    }

    private var currentSortOptions = SortOptions.DANGER_LEVEL_DESC

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

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        initializePlaces()

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

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
            val adapter = ArrayAdapter<String>(
                this@MapsActivity,
                android.R.layout.simple_dropdown_item_1line
            )
            autoCompleteSearch.setAdapter(adapter)
            autoCompleteSearch.threshold = 1

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

            autoCompleteSearch.setOnItemClickListener { _, _, position, _ ->
                predictions.getOrNull(position)?.let { prediction ->
                    getPlaceDetails(prediction)
                }
                autoCompleteSearch.clearFocus()
            }

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

            clearSearch.setOnClickListener {
                autoCompleteSearch.text.clear()
                clearSearch.visibility = View.GONE
            }

            fabMyLocation.setOnClickListener { getCurrentLocation() }
            fabMapType.setOnClickListener { showMapTypeDialog() }
            fabClearMarkers.setOnClickListener { clearAllSafetyCircles() }
            fabZoomIn.setOnClickListener {
                if (::map.isInitialized) {
                    map.animateCamera(CameraUpdateFactory.zoomIn())
                }
            }
            fabZoomOut.setOnClickListener {
                if (::map.isInitialized) {
                    map.animateCamera(CameraUpdateFactory.zoomOut())
                }
            }
            fabShowReports.setOnClickListener { showReportsBottomSheet() }
            fabRefresh.setOnClickListener { refreshReports() }
        }
    }

    private fun setupObservers() {
        viewModel.reports.observe(this) { reports ->
            if (::map.isInitialized) {
                updateMapWithReportsSmartly(reports)
                pendingReports = null
            } else {
                // Store reports to apply when map is ready
                pendingReports = reports
                Log.d(TAG, "Map not ready, storing ${reports.size} pending reports")
            }
        }

        viewModel.loading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(this) { error ->
            error?.let {
                Toast.makeText(this, "Error: $it", Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }

        viewModel.submitSuccess.observe(this) { success ->
            if (success) {
                Toast.makeText(this, "Report submitted successfully!", Toast.LENGTH_SHORT).show()
                viewModel.resetSubmitSuccess()
            }
        }

        viewModel.focusedReportId.observe(this) { reportId ->
            focusedReportId = reportId
            if (::map.isInitialized) {
                updateCircleVisibility()

                // Move camera to focused report
                reportId?.let { id ->
                    reportMarkers[id]?.let { marker ->
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, 15f))
                    }
                }
            }
        }

        viewModel.centerLocation.observe(this) { location ->
            centerLocation = location
            if (::map.isInitialized && location != null) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 14f))
            }
        }

        viewModel.mapType.observe(this) { type ->
            if (::map.isInitialized && type != null) {
                map.mapType = type
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap

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

        map.setOnMapLongClickListener { latLng ->
            showSafetyReportDialog(latLng)
            Log.d("Long Press", "Map was long pressed")
        }

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

        map.setOnMarkerClickListener { marker ->
            val reportId = marker.tag as? String
            if (reportId != null) {
                focusOnReport(reportId)
                true
            } else {
                false
            }
        }

        map.setOnCameraIdleListener {
            val center = map.cameraPosition.target
            val zoom = map.cameraPosition.zoom

            if (zoom >= 10f) {
                val radiusKm = 100.0
                viewModel.loadNearbyReports(center.latitude, center.longitude, radiusKm)
            }
        }

        // Apply pending reports if any
        pendingReports?.let { reports ->
            Log.d(TAG, "Map ready, applying ${reports.size} pending reports")
            updateMapWithReportsSmartly(reports)
            pendingReports = null
        }

        // Apply pending map type if any
        viewModel.mapType.value?.let { type ->
            map.mapType = type
        }

        // Apply pending center location if any
        viewModel.centerLocation.value?.let { location ->
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 14f))
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
            if (::map.isInitialized) {
                map.isMyLocationEnabled = true
            }
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
                centerLocation = currentLatLng

                if (::map.isInitialized) {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 14f))
                }

                viewModel.loadNearbyReports(location.latitude, location.longitude, 100.0)
                viewModel.setCenterLocation(currentLatLng)
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
                            centerLocation = latLng
                            viewModel.setCenterLocation(latLng)
                            viewModel.loadNearbyReports(latLng.latitude, latLng.longitude, 100.0)
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
                    centerLocation = latLng
                    viewModel.setCenterLocation(latLng)
                    viewModel.loadNearbyReports(latLng.latitude, latLng.longitude, 100.0)
                }
            }

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
                            updateMarkerAndCamera(latLng, query, address.getAddressLine(0))
                            centerLocation = latLng
                            viewModel.loadNearbyReports(latLng.latitude, latLng.longitude, 100.0)
                            viewModel.setCenterLocation(latLng)
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

                    updateMarkerAndCamera(latLng, query, address.getAddressLine(0))
                    centerLocation = latLng
                    viewModel.setCenterLocation(centerLocation)
                    viewModel.loadNearbyReports(latLng.latitude, latLng.longitude, 100.0)
                } else {
                    Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Search error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMarkerAndCamera(latLng: LatLng, title: String, snippet: String) {
        if (!::map.isInitialized) return

        currentMarker?.remove()
        currentMarker = map.addMarker(
            MarkerOptions()
                .position(latLng)
                .title(title)
                .snippet(snippet)
        )
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 14f))
    }

    private fun updateMapWithReportsSmartly(reports: List<SafetyReport>) {
        if (!::map.isInitialized) {
            Log.w(TAG, "Map not initialized, cannot update reports")
            return
        }

        val newReportIds = reports.map { it.id }.toSet()
        val currentReportIds = reportMarkers.keys.toSet()

        val toRemove = currentReportIds - newReportIds
        toRemove.forEach { reportId ->
            reportMarkers[reportId]?.remove()
            reportCircles[reportId]?.remove()
            reportMarkers.remove(reportId)
            reportCircles.remove(reportId)
        }

        reports.forEach { report ->
            if (report.id !in currentReportIds) {
                addReportToMap(report)
            }
        }

        updateCircleVisibility()

        Log.d(TAG, "Smart update: ${reports.size} reports, added ${reports.size - currentReportIds.size}, removed ${toRemove.size}")
    }

    private fun addReportToMap(report: SafetyReport) {
        if (!::map.isInitialized) {
            Log.w(TAG, "Attempted to add report to map before map was initialized")
            return
        }

        val latLng = LatLng(report.latitude, report.longitude)
        val safetyLevel = report.getSafetyLevelEnum()

        val circle = map.addCircle(
            CircleOptions()
                .center(latLng)
                .radius(500.0)
                .strokeWidth(3f)
                .strokeColor(safetyLevel.color)
                .fillColor(safetyLevel.colorWithAlpha)
                .clickable(true)
                .visible(focusedReportId == null || focusedReportId == report.id)
        )

        val marker = map.addMarker(
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

        marker?.tag = report.id
        reportMarkers[report.id] = marker!!
        reportCircles[report.id] = circle
    }

    private fun focusOnReport(reportId: String) {
        if (!::map.isInitialized) return

        if (focusedReportId == reportId) {
            focusedReportId = null
        } else {
            focusedReportId = reportId
        }
        viewModel.setFocusedReport(focusedReportId)
        updateCircleVisibility()

        reportMarkers[reportId]?.let { marker ->
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, 15f))
        }
    }

    private fun updateCircleVisibility() {
        reportCircles.forEach { (reportId, circle) ->
            circle.isVisible = focusedReportId == null || focusedReportId == reportId
        }
    }

    private fun showSafetyReportDialog(latLng: LatLng) {
        val dialogBinding = DialogSafetyReportBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setTitle("Report Safety Status")
            .create()

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

    private fun showReportsBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val sheetBinding = BottomSheetReportsBinding.inflate(layoutInflater)

        val allReports = viewModel.reports.value ?: emptyList()
        val sortedReports = sortReports(allReports, currentSortOptions)

        val adapter = SafetyReportAdapter(
            onUpvoteClick = { report ->
                Toast.makeText(this, "Upvoted ${report.areaName}", Toast.LENGTH_SHORT).show()
                viewModel.voteOnReport(report.id,true )
            },
            onDownvoteClick = { report ->
                viewModel.voteOnReport(report.id,false )

                Toast.makeText(this, "Downvoted ${report.areaName}", Toast.LENGTH_SHORT).show()
            },
            onItemClick = { report ->
                bottomSheetDialog.dismiss()
                focusOnReport(report.id)
            }
        )

        with(sheetBinding) {
            recyclerViewReports.layoutManager = LinearLayoutManager(this@MapsActivity)
            recyclerViewReports.adapter = adapter

            if (sortedReports.isEmpty()) {
                recyclerViewReports.visibility = View.GONE
                tvNoReports.visibility = View.VISIBLE
            } else {
                recyclerViewReports.visibility = View.VISIBLE
                tvNoReports.visibility = View.GONE
                adapter.updateReports(sortedReports)
            }

            btnSort.setOnClickListener {
                showSortOptionssDialog { sortOption ->
                    currentSortOptions = sortOption
                    val newSorted = sortReports(allReports, sortOption)
                    adapter.updateReports(newSorted)
                }
            }

            btnClose.setOnClickListener {
                bottomSheetDialog.dismiss()
            }
        }

        bottomSheetDialog.setContentView(sheetBinding.root)
        bottomSheetDialog.show()
    }

    private fun sortReports(reports: List<SafetyReport>, sortOption: SortOptions): List<SafetyReport> {
        return when (sortOption) {
            SortOptions.DANGER_LEVEL_DESC -> reports.sortedByDescending {
                it.getSafetyLevelEnum().ordinal
            }.sortedByDescending { it.timestamp }

            SortOptions.DANGER_LEVEL_ASC -> reports.sortedBy {
                it.getSafetyLevelEnum().ordinal
            }.sortedByDescending { it.timestamp }

            SortOptions.TIME_NEWEST -> reports.sortedByDescending { it.timestamp }
            SortOptions.TIME_OLDEST -> reports.sortedBy { it.timestamp }

            SortOptions.VOTES_HIGH -> reports.sortedByDescending {
                it.upvotes - it.downvotes
            }

            SortOptions.VOTES_LOW -> reports.sortedBy {
                it.upvotes - it.downvotes
            }
        }
    }

    private fun showSortOptionssDialog(onSelected: (SortOptions) -> Unit) {
        val options = arrayOf(
            "Most Dangerous First",
            "Safest First",
            "Newest First",
            "Oldest First",
            "Highest Votes",
            "Lowest Votes"
        )

        AlertDialog.Builder(this)
            .setTitle("Sort Reports By")
            .setItems(options) { _, which ->
                val sortOption = when (which) {
                    0 -> SortOptions.DANGER_LEVEL_DESC
                    1 -> SortOptions.DANGER_LEVEL_ASC
                    2 -> SortOptions.TIME_NEWEST
                    3 -> SortOptions.TIME_OLDEST
                    4 -> SortOptions.VOTES_HIGH
                    5 -> SortOptions.VOTES_LOW
                    else -> SortOptions.DANGER_LEVEL_DESC
                }
                onSelected(sortOption)
            }
            .show()
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
        if (!::map.isInitialized) return

        val options = arrayOf("Normal", "Satellite", "Terrain", "Hybrid")
        AlertDialog.Builder(this)
            .setTitle("Select Map Type")
            .setItems(options) { _, which ->
                viewModel.setMapType(which)
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
        reportMarkers.values.forEach { it.remove() }
        reportCircles.values.forEach { it.remove() }
        reportMarkers.clear()
        reportCircles.clear()
        focusedReportId = null
        currentMarker?.remove()
        currentMarker = null
        Toast.makeText(this, "All safety zones cleared", Toast.LENGTH_SHORT).show()
    }

    private fun refreshReports() {
        if (!::map.isInitialized) {
            Toast.makeText(this, "Map not ready", Toast.LENGTH_SHORT).show()
            return
        }

        val center = centerLocation ?: map.cameraPosition.target
        viewModel.loadNearbyReports(center.latitude, center.longitude, 100.0)
        viewModel.forceRefresh(center.latitude, center.longitude)
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