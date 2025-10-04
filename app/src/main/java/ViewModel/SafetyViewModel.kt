package ViewModel



import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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


    fun loadNearbyReports(latitude: Double, longitude: Double, radiusKm: Double = 50.0) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null

            repository.getNearbyReports(latitude, longitude, radiusKm)
                .onSuccess { reports ->
                    _reports.value = reports
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
        comment: String
    ) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null

            repository.submitReport(latitude, longitude, areaName, safetyLevel, comment)
                .onSuccess {
                    _submitSuccess.value = true
                    _loading.value = false
                    // Reload reports after submission
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
                    // Refresh the list
                    _reports.value?.let { currentReports ->
                        _reports.value = currentReports
                    }
                }
        }
    }


    fun deleteReport(reportId: String) {
        viewModelScope.launch {
            repository.deleteReport(reportId)
                .onSuccess {
                    // Remove from list
                    _reports.value = _reports.value?.filter { it.id != reportId }
                }
        }
    }


    fun clearError() {
        _error.value = null
    }


    fun resetSubmitSuccess() {
        _submitSuccess.value = false
    }
}

