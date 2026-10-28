package com.kschulz.aro.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kschulz.aro.data.model.BloodPressure
import com.kschulz.aro.data.model.HeartRateSample
import com.kschulz.aro.data.model.SleepSession
import com.kschulz.aro.data.model.StepDay
import com.kschulz.aro.data.repository.HealthRepository
import com.kschulz.aro.data.sync.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/** Per-metric history over a selectable day window, read straight from Room. The window is
 *  a StateFlow so the list reacts when the user changes the range picker. */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val health: HealthRepository,
    private val time: TimeSource,
) : ViewModel() {

    private val _windowDays = MutableStateFlow(30)
    val windowDays: StateFlow<Int> = _windowDays.asStateFlow()

    fun setWindow(days: Int) {
        _windowDays.value = days.coerceIn(7, 90)
    }

    private fun range(days: Int): Pair<Long, Long> {
        val to = time.todayEpochDay()
        val from = to - (days - 1).toLong()
        return from to to
    }

    val steps: StateFlow<List<StepDay>> = _windowDays
        .flatMapLatest { d ->
            val (f, t) = range(d)
            health.observeStepsBetween(f, t)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sleep: StateFlow<List<SleepSession>> = _windowDays
        .flatMapLatest { d ->
            val (f, t) = range(d)
            health.observeSleepSessions(f * 86_400_000L, (t + 1) * 86_400_000L)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val heartRate: StateFlow<List<HeartRateSample>> = _windowDays
        .flatMapLatest { d ->
            val (f, t) = range(d)
            health.observeHeartRateBetween(f * 86_400_000L, (t + 1) * 86_400_000L)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bloodPressure: StateFlow<List<BloodPressure>> = _windowDays
        .flatMapLatest { d ->
            val (f, t) = range(d)
            health.observeBloodPressureBetween(f * 86_400_000L, (t + 1) * 86_400_000L)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
