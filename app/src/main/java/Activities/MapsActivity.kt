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

    private var pendingReports: List<SafetyReport>? = null
    private var currentSortOptions = SortOptions.DANGER_LEVEL_DESC

    private val viewModel by lazy {
        (application as MyApplication).safetyViewModel
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            enableMyLocation()
            getCurrentLocation()
        } else {
            showToast("Location permission denied. Some features will be unavailable.")
            binding.fabMyLocation.isEnabled = false
        }
    }

    companion object {
        private const val TAG = "MapsActivity"
        private const val DEFAULT_RADIUS_KM = 100.0
        private const val DEFAULT_ZOOM_LEVEL = 14f
        private const val FOCUSED_ZOOM_LEVEL = 15f
        private const val MIN_ZOOM_FOR_REPORTS = 10f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeComponents()
        setupMap()
        setupUI()
        setupObservers()
    }

    private fun initializeComponents() {
        auth = FirebaseAuth.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        initializePlaces()
        handleAuthentication()
    }

    private fun handleAuthentication() {
        binding.progressBar.visibility = View.VISIBLE

        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnSuccessListener {
                    Log.d(TAG, "Signed in anonymously")
                    hideProgressBar()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Anonymous sign-in failed", e)
                    showToast("Authentication failed: ${e.message}")
                    hideProgressBar()
                }
        } else {
            hideProgressBar()
        }
    }

    private fun initializePlaces() {
        try {
            val apiKey = packageManager.getApplicationInfo(
                packageName,
                PackageManager.GET_META_DATA
            ).metaData.getString("com.google.android.geo.API_KEY")

            if (!Places.isInitialized() && apiKey != null) {
                Places.initializeWithNewPlacesApiEnabled(applicationContext, apiKey)
            }
            placesClient = Places.createClient(this)
            sessionToken = AutocompleteSessionToken.newInstance()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Places: ${e.message}")
        }
    }

    private fun setupMap() {
        (supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment)?.getMapAsync(this)
    }

    private fun setupUI() {
        setupSearchView()
        setupFloatingActionButtons()
    }

    private fun setupSearchView() {
        val adapter = ArrayAdapter<String>(
            this,
            android.R.layout.simple_dropdown_item_1line
        )

        with(binding.autoCompleteSearch) {
            setAdapter(adapter)
            threshold = 1

            addTextChangedListener(createSearchTextWatcher(adapter))

            setOnItemClickListener { _, _, position, _ ->
                predictions.getOrNull(position)?.let { prediction ->
                    getPlaceDetails(prediction)
                }
                clearFocus()
            }

            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    v.text?.toString()?.takeIf { it.isNotEmpty() }?.let { query ->
                        searchLocation(query)
                    }
                    clearFocus()
                    true
                } else false
            }
        }

        binding.clearSearch.setOnClickListener {
            binding.autoCompleteSearch.text.clear()
            it.visibility = View.GONE
        }
    }

    private fun createSearchTextWatcher(adapter: ArrayAdapter<String>): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.clearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE

                s?.toString()?.takeIf { it.isNotEmpty() }?.let { query ->
                    getPlacePredictions(query, adapter)
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        }
    }

    private fun setupFloatingActionButtons() {
        with(binding) {
            fabMyLocation.setOnClickListener { getCurrentLocation() }
            fabMapType.setOnClickListener { showMapTypeDialog() }
            fabClearMarkers.setOnClickListener { clearAllSafetyCircles() }
            fabZoomIn.setOnClickListener { zoomMap(true) }
            fabZoomOut.setOnClickListener { zoomMap(false) }
            fabShowReports.setOnClickListener { showReportsBottomSheet() }
            fabRefresh.setOnClickListener { refreshReports() }
        }
    }

    private fun zoomMap(zoomIn: Boolean) {
        if (!::map.isInitialized) return

        map.animateCamera(
            CameraUpdateFactory.zoomBy(if (zoomIn) 1f else -1f)
        )
    }

    private fun setupObservers() {
        viewModel.reports.observe(this) { reports ->
            if (::map.isInitialized) {
                updateMapWithReportsSmartly(reports)
                pendingReports = null
            } else {
                pendingReports = reports
                Log.d(TAG, "Map not ready, storing ${reports.size} pending reports")
            }
        }

        viewModel.loading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(this) { error ->
            error?.let {
                showToast("Error: $it")
                viewModel.clearError()
            }
        }

        viewModel.submitSuccess.observe(this) { success ->
            if (success) {
                showToast("Report submitted successfully!")
                viewModel.resetSubmitSuccess()
            }
        }

        viewModel.focusedReportId.observe(this) { reportId ->
            focusedReportId = reportId
            if (::map.isInitialized) {
                updateCircleVisibility()
                reportId?.let { focusOnReportMarker(it) }
            }
        }

        viewModel.centerLocation.observe(this) { location ->
            centerLocation = location
            if (::map.isInitialized && location != null) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(location, DEFAULT_ZOOM_LEVEL))
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
        configureMapSettings()
        setupMapListeners()
        checkLocationPermission()
        applyPendingData()
    }

    private fun configureMapSettings() {
        with(map) {
            mapType = GoogleMap.MAP_TYPE_NORMAL
            uiSettings.apply {
                isZoomControlsEnabled = false
                isCompassEnabled = true
                isMyLocationButtonEnabled = false
                isMapToolbarEnabled = true
                isZoomGesturesEnabled = true
                isScrollGesturesEnabled = true
                isTiltGesturesEnabled = true
                isRotateGesturesEnabled = true
            }
        }
    }

    private fun setupMapListeners() {
        map.apply {
            setOnMapLongClickListener { latLng ->
                showSafetyReportDialog(latLng)
                Log.d(TAG, "Map long pressed at $latLng")
            }

            setOnMapClickListener { latLng ->
                handleMapClick(latLng)
            }

            setOnMarkerClickListener { marker ->
                (marker.tag as? String)?.let { reportId ->
                    focusOnReport(reportId)
                    true
                } ?: false
            }

            setOnCameraIdleListener {
                handleCameraIdle()
            }
        }
    }

    private fun handleMapClick(latLng: LatLng) {
        currentMarker?.remove()
        currentMarker = map.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("Selected Location")
                .snippet("Long press to report safety status")
                .draggable(true)
        )
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, FOCUSED_ZOOM_LEVEL))
        getAddressFromLocation(latLng)
    }

    private fun handleCameraIdle() {
        val center = map.cameraPosition.target
        val zoom = map.cameraPosition.zoom

        if (zoom >= MIN_ZOOM_FOR_REPORTS) {
            viewModel.loadNearbyReports(center.latitude, center.longitude, DEFAULT_RADIUS_KM)
        }
    }

    private fun applyPendingData() {
        pendingReports?.let { reports ->
            Log.d(TAG, "Applying ${reports.size} pending reports")
            updateMapWithReportsSmartly(reports)
            pendingReports = null
        }

        viewModel.mapType.value?.let { type ->
            map.mapType = type
        }

        viewModel.centerLocation.value?.let { location ->
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(location, DEFAULT_ZOOM_LEVEL))
        }
    }

    private fun checkLocationPermission() {
        when {
            hasFineLocationPermission() -> {
                enableMyLocation()
                getCurrentLocation()
            }
            else -> {
                requestLocationPermission()
            }
        }
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun enableMyLocation() {
        try {
            if (hasFineLocationPermission() && ::map.isInitialized) {
                map.isMyLocationEnabled = true
            }
        } catch (securityException: SecurityException) {
            Log.e(TAG, "SecurityException when enabling my location: ${securityException.message}")
            handleSecurityException(securityException)
        }
    }

    private fun getCurrentLocation() {
        when {
            hasFineLocationPermission() -> {
                requestCurrentLocation()
            }
            else -> {
                requestLocationPermission()
            }
        }
    }

    private fun requestCurrentLocation() {
        showProgressBar()
        locationCancellationToken?.cancel()
        locationCancellationToken = CancellationTokenSource()

        try {
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                locationCancellationToken!!.token
            ).addOnSuccessListener { location: Location? ->
                hideProgressBar()
                handleLocationResult(location)
            }.addOnFailureListener { exception ->
                hideProgressBar()
                handleLocationError(exception)
            }.addOnCanceledListener {
                hideProgressBar()
                showToast("Location request cancelled")
            }
        } catch (securityException: SecurityException) {
            hideProgressBar()
            handleSecurityException(securityException)
        }
    }

    private fun handleLocationResult(location: Location?) {
        if (location != null) {
            currentLocation = location
            val currentLatLng = LatLng(location.latitude, location.longitude)
            centerLocation = currentLatLng

            if (::map.isInitialized) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, DEFAULT_ZOOM_LEVEL))
            }

            viewModel.loadNearbyReports(location.latitude, location.longitude, DEFAULT_RADIUS_KM)
            viewModel.setCenterLocation(currentLatLng)
            showToast("Location found!")
        } else {
            showToast("Cannot get current location. Please ensure location services are enabled.")
            getLastKnownLocation()
        }
    }

    private fun getLastKnownLocation() {
        try {
            if (hasFineLocationPermission()) {
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { location: Location? ->
                        location?.let {
                            handleLocationResult(it)
                        } ?: showToast("No last known location available")
                    }
                    .addOnFailureListener { exception ->
                        Log.e(TAG, "Last known location error: ${exception.message}")
                        showToast("Unable to retrieve location")
                    }
            }
        } catch (securityException: SecurityException) {
            handleSecurityException(securityException)
        }
    }

    private fun handleLocationError(exception: Exception) {
        Log.e(TAG, "Location request failed: ${exception.message}", exception)

        when (exception) {
            is SecurityException -> handleSecurityException(exception)
            else -> showToast("Location error: ${exception.localizedMessage ?: "Unknown error"}")
        }
    }

    private fun handleSecurityException(securityException: SecurityException) {
        Log.e(TAG, "Location permission revoked or not granted: ${securityException.message}")
        showToast("Location permission denied. Please grant permission in app settings.")
    }

    private fun requestLocationPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            AlertDialog.Builder(this)
                .setTitle("Location Permission Required")
                .setMessage("This app needs location permission to show your current location and nearby safety reports.")
                .setPositiveButton("Grant") { _, _ ->
                    requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                .setNegativeButton("Deny") { dialog, _ ->
                    dialog.dismiss()
                    showToast("Location features will be limited")
                }
                .show()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
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

                val suggestions = response.autocompletePredictions.map {
                    it.getFullText(null).toString()
                }

                adapter.clear()
                adapter.addAll(suggestions)
                adapter.notifyDataSetChanged()
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Place prediction error: ${exception.message}")
            }
    }

    private fun getPlaceDetails(prediction: AutocompletePrediction) {
        try {
            val locationName = prediction.getFullText(null).toString()
            geocodeLocationName(locationName) { latLng ->
                updateMarkerAndCamera(
                    latLng,
                    prediction.getPrimaryText(null).toString(),
                    "Long press to report safety"
                )
                centerLocation = latLng
                viewModel.setCenterLocation(latLng)
                viewModel.loadNearbyReports(latLng.latitude, latLng.longitude, DEFAULT_RADIUS_KM)
            }
            sessionToken = AutocompleteSessionToken.newInstance()
            predictions.clear()
            binding.autoCompleteSearch.text.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Place details error: ${e.message}")
            showToast("Could not get place details")
        }
    }

    private fun searchLocation(query: String) {
        geocodeLocationName(query) { latLng ->
            updateMarkerAndCamera(latLng, query, "Searched location")
            centerLocation = latLng
            viewModel.loadNearbyReports(latLng.latitude, latLng.longitude, DEFAULT_RADIUS_KM)
            viewModel.setCenterLocation(latLng)
        }
    }

    private fun geocodeLocationName(locationName: String, onSuccess: (LatLng) -> Unit) {
        try {
            val geocoder = Geocoder(this, Locale.getDefault())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocationName(locationName, 1) { addresses ->
                    if (addresses.isNotEmpty()) {
                        val address = addresses[0]
                        runOnUiThread {
                            onSuccess(LatLng(address.latitude, address.longitude))
                        }
                    } else {
                        runOnUiThread {
                            showToast("Location not found")
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(locationName, 1)
                if (addresses != null && addresses.isNotEmpty()) {
                    val address = addresses[0]
                    onSuccess(LatLng(address.latitude, address.longitude))
                } else {
                    showToast("Location not found")
                }
            }
        } catch (e: Exception) {
            showToast("Search error: ${e.message}")
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
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, DEFAULT_ZOOM_LEVEL))
    }

    private fun updateMapWithReportsSmartly(reports: List<SafetyReport>) {
        if (!::map.isInitialized) {
            Log.w(TAG, "Map not initialized, cannot update reports")
            return
        }

        val newReportIds = reports.map { it.id }.toSet()
        val currentReportIds = reportMarkers.keys.toSet()

        (currentReportIds - newReportIds).forEach { reportId ->
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
        Log.d(TAG, "Smart update: ${reports.size} reports, added ${reports.size - currentReportIds.size}, removed ${currentReportIds.size - newReportIds.size}")
    }

    private fun addReportToMap(report: SafetyReport) {
        if (!::map.isInitialized) return

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
                .icon(getMarkerIconForSafetyLevel(safetyLevel))
        )

        marker?.tag = report.id
        marker?.let {
            reportMarkers[report.id] = it
            reportCircles[report.id] = circle
        }
    }

    private fun getMarkerIconForSafetyLevel(safetyLevel: SafetyLevel): BitmapDescriptor {
        val hue = when (safetyLevel) {
            SafetyLevel.SAFE -> BitmapDescriptorFactory.HUE_GREEN
            SafetyLevel.BE_CAUTIOUS -> BitmapDescriptorFactory.HUE_YELLOW
            SafetyLevel.UNSAFE -> BitmapDescriptorFactory.HUE_ORANGE
            SafetyLevel.DANGEROUS -> BitmapDescriptorFactory.HUE_RED
            else -> BitmapDescriptorFactory.HUE_VIOLET
        }
        return BitmapDescriptorFactory.defaultMarker(hue)
    }

    private fun focusOnReport(reportId: String) {
        if (!::map.isInitialized) return

        focusedReportId = if (focusedReportId == reportId) null else reportId
        viewModel.setFocusedReport(focusedReportId)
        updateCircleVisibility()
        focusOnReportMarker(reportId)
    }

    private fun focusOnReportMarker(reportId: String) {
        reportMarkers[reportId]?.let { marker ->
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, FOCUSED_ZOOM_LEVEL))
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

        setupSafetyReportButtons(dialogBinding, dialog, latLng, areaName)
        dialog.show()
    }

    private fun setupSafetyReportButtons(
        binding: DialogSafetyReportBinding,
        dialog: AlertDialog,
        latLng: LatLng,
        areaName: String
    ) {
        with(binding) {
            val buttonActions = mapOf(
                btnSafe to SafetyLevel.SAFE,
                btnCautious to SafetyLevel.BE_CAUTIOUS,
                btnUnsafe to SafetyLevel.UNSAFE,
                btnDangerous to SafetyLevel.DANGEROUS
            )

            buttonActions.forEach { (button, safetyLevel) ->
                button.setOnClickListener {
                    submitReport(latLng, areaName, safetyLevel, etComment.text.toString())
                    dialog.dismiss()
                }
            }

            btnCancel.setOnClickListener { dialog.dismiss() }
        }
    }

    private fun submitReport(latLng: LatLng, areaName: String, level: SafetyLevel, comment: String) {
        if (comment.isBlank()) {
            showToast("Please add a comment")
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

        setupReportsBottomSheetContent(sheetBinding, sortedReports, bottomSheetDialog)
        bottomSheetDialog.setContentView(sheetBinding.root)
        bottomSheetDialog.show()
    }

    private fun setupReportsBottomSheetContent(
        binding: BottomSheetReportsBinding,
        reports: List<SafetyReport>,
        dialog: BottomSheetDialog
    ) {
        val adapter = SafetyReportAdapter(
            onUpvoteClick = { report ->
                showToast("Upvoted ${report.areaName}")
            },
            onDownvoteClick = { report ->
                showToast("Downvoted ${report.areaName}")
            },
            onItemClick = { report ->
                dialog.dismiss()
                focusOnReport(report.id)
            }
        )

        with(binding) {
            recyclerViewReports.layoutManager = LinearLayoutManager(this@MapsActivity)
            recyclerViewReports.adapter = adapter

            if (reports.isEmpty()) {
                recyclerViewReports.visibility = View.GONE
                tvNoReports.visibility = View.VISIBLE
            } else {
                recyclerViewReports.visibility = View.VISIBLE
                tvNoReports.visibility = View.GONE
                adapter.updateReports(reports)
            }

            btnSort.setOnClickListener {
                showSortOptionsDialog { sortOption ->
                    currentSortOptions = sortOption
                    val newSorted = sortReports(reports, sortOption)
                    adapter.updateReports(newSorted)
                }
            }

            btnClose.setOnClickListener { dialog.dismiss() }
        }
    }

    private fun sortReports(reports: List<SafetyReport>, sortOption: SortOptions): List<SafetyReport> {
        return when (sortOption) {
            SortOptions.DANGER_LEVEL_DESC -> reports.sortedWith(
                compareByDescending<SafetyReport> { it.getSafetyLevelEnum().ordinal }
                    .thenByDescending { it.timestamp }
            )
            SortOptions.DANGER_LEVEL_ASC -> reports.sortedWith(
                compareBy<SafetyReport> { it.getSafetyLevelEnum().ordinal }
                    .thenByDescending { it.timestamp }
            )
            SortOptions.TIME_NEWEST -> reports.sortedByDescending { it.timestamp }
            SortOptions.TIME_OLDEST -> reports.sortedBy { it.timestamp }
            SortOptions.VOTES_HIGH -> reports.sortedByDescending { it.upvotes - it.downvotes }
            SortOptions.VOTES_LOW -> reports.sortedBy { it.upvotes - it.downvotes }
        }
    }

    private fun showSortOptionsDialog(onSelected: (SortOptions) -> Unit) {
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
        geocodeLatLng(latLng) { addressText ->
            currentMarker?.snippet = addressText
            currentMarker?.showInfoWindow()
            showToast(addressText, Toast.LENGTH_LONG)
        }
    }

    private fun getAddressFromLocationForReport(latLng: LatLng, callback: (String) -> Unit) {
        geocodeLatLng(latLng) { address ->
            callback(address)
        }
    }

    private fun geocodeLatLng(latLng: LatLng, callback: (String) -> Unit) {
        try {
            val geocoder = Geocoder(this, Locale.getDefault())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1) { addresses ->
                    val addressText = addresses.firstOrNull()?.getAddressLine(0) ?: "Unknown Location"
                    runOnUiThread { callback(addressText) }
                }
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                val addressText = addresses?.firstOrNull()?.getAddressLine(0) ?: "Unknown Location"
                callback(addressText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Geocoder error: ${e.message}")
            callback("Unknown Location")
        }
    }

    private fun showMapTypeDialog() {
        if (!::map.isInitialized) return

        val options = arrayOf("Normal", "Satellite", "Terrain", "Hybrid")
        AlertDialog.Builder(this)
            .setTitle("Select Map Type")
            .setItems(options) { _, which ->
                val mapType = when (which) {
                    0 -> GoogleMap.MAP_TYPE_NORMAL
                    1 -> GoogleMap.MAP_TYPE_SATELLITE
                    2 -> GoogleMap.MAP_TYPE_TERRAIN
                    3 -> GoogleMap.MAP_TYPE_HYBRID
                    else -> GoogleMap.MAP_TYPE_NORMAL
                }
                viewModel.setMapType(mapType)
                map.mapType = mapType
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
        showToast("All safety zones cleared")
    }

    private fun refreshReports() {
        if (!::map.isInitialized) {
            showToast("Map not ready")
            return
        }

        val center = centerLocation ?: map.cameraPosition.target
        viewModel.loadNearbyReports(center.latitude, center.longitude, DEFAULT_RADIUS_KM)
        viewModel.forceRefresh(center.latitude, center.longitude)
        showToast("Refreshing reports...")
    }

    private fun showProgressBar() {
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun hideProgressBar() {
        binding.progressBar.visibility = View.GONE
    }

    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(this, message, duration).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCancellationToken?.cancel()
        Log.d(TAG, "MapsActivity destroyed")
    }
}