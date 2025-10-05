package ViewModel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import models.SafetyReport
import kotlinx.coroutines.launch
import repository.SafetyRepository

class SafetyViewModel : ViewModel() {
    private val repository = SafetyRepository()

    private val _reports = MutableLiveData<List<SafetyReport>>()
    val reports: LiveData<List<SafetyReport>> = _reports

    private val _loading = MutableLiveData<Boolean>()
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _submitSuccess = MutableLiveData<Boolean>()
    val submitSuccess: LiveData<Boolean> = _submitSuccess

    private val _focusedReportId = MutableLiveData<String?>()
    val focusedReportId: LiveData<String?> = _focusedReportId

    private val _centerLocation = MutableLiveData<LatLng?>()
    val centerLocation: LiveData<LatLng?> = _centerLocation

    private val _mapType = MutableLiveData<Int>()
    val mapType: LiveData<Int> = _mapType

    private var currentReportIds = emptySet<String>()

    fun loadNearbyReports(latitude: Double, longitude: Double, radiusKm: Double = 50.0) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null

            repository.getNearbyReports(latitude, longitude, radiusKm)
                .onSuccess { newReports ->
                    _reports.value = newReports
                    currentReportIds = newReports.map { it.id }.toSet()
                    _loading.value = false
                }
                .onFailure { exception ->
                    _error.value = exception.message
                    _loading.value = false
                }
        }
    }

    fun loadRecentReports() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null

            repository.getRecentReports()
                .onSuccess { reports ->
                    _reports.value = reports
                    currentReportIds = reports.map { it.id }.toSet()
                    _loading.value = false
                }
                .onFailure { exception ->
                    _error.value = exception.message
                    _loading.value = false
                }
        }
    }

    fun submitReport(
        latitude: Double,
        longitude: Double,
        areaName: String,
        safetyLevel: String,
        comment: String,
        radiusMeters: Int = 500
    ) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null

            repository.submitReport(latitude, longitude, areaName, safetyLevel, comment, radiusMeters)
                .onSuccess {
                    _submitSuccess.value = true
                    _loading.value = false
                    loadNearbyReports(latitude, longitude)
                }
                .onFailure { exception ->
                    _error.value = exception.message
                    _submitSuccess.value = false
                    _loading.value = false
                }
        }
    }

    fun voteOnReport(reportId: String, isUpvote: Boolean) {
        viewModelScope.launch {
            repository.voteOnReport(reportId, isUpvote)
                .onSuccess {
                    val currentReports = _reports.value ?: return@onSuccess
                    val updatedReports = currentReports.map { report ->
                        if (report.id == reportId) {
                            if (isUpvote) {
                                report.copy(upvotes = report.upvotes + 1)
                            } else {
                                report.copy(downvotes = report.downvotes + 1)
                            }
                        } else {
                            report
                        }
                    }
                    _reports.value = updatedReports
                }
        }
    }

    fun deleteReport(reportId: String) {
        viewModelScope.launch {
            repository.deleteReport(reportId)
                .onSuccess {
                    _reports.value = _reports.value?.filter { it.id != reportId }
                    currentReportIds = _reports.value?.map { it.id }?.toSet() ?: emptySet()
                }
        }
    }

    fun setFocusedReport(reportId: String?) {
        _focusedReportId.value = reportId
    }

    fun setCenterLocation(latLng: LatLng?) {
        _centerLocation.value = latLng
    }

    fun setMapType(type: Int) {
        _mapType.value = type
    }

    fun clearError() {
        _error.value = null
    }

    fun resetSubmitSuccess() {
        _submitSuccess.value = false
    }

    fun forceRefresh(latitude: Double, longitude: Double, radiusKm: Double = 50.0) {
        currentReportIds = emptySet()
        loadNearbyReports(latitude, longitude, radiusKm)
    }
}