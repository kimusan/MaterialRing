package com.kschulz.aro.data.repository

import com.kschulz.aro.data.db.SettingsDao
import com.kschulz.aro.data.db.SettingsKeys
import com.kschulz.aro.data.model.AppSetting
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Key/value app settings. The active-profile id and the Health Connect toggle live here
 *  so both are reactive Flows the UI and the health repository can observe. */
@Singleton
class SettingsRepository @Inject constructor(
    private val dao: SettingsDao,
) {
    fun observeActiveProfileId(): Flow<Long> =
        dao.observe(SettingsKeys.ACTIVE_PROFILE_ID)
            .map { it?.value ?: NO_PROFILE }
            .distinctUntilChanged()
            .catch { emitAll(flowOf(NO_PROFILE)) }

    suspend fun setActiveProfileId(id: Long) {
        dao.upsert(AppSetting(SettingsKeys.ACTIVE_PROFILE_ID, id))
    }

    fun observeHealthConnectEnabled(): Flow<Boolean> =
        dao.observe(SettingsKeys.HEALTH_CONNECT_ENABLED)
            .map { it?.value == 1L }
            .distinctUntilChanged()
            .catch { emitAll(flowOf(false)) }

    suspend fun setHealthConnectEnabled(enabled: Boolean) {
        dao.upsert(AppSetting(SettingsKeys.HEALTH_CONNECT_ENABLED, if (enabled) 1L else 0L))
    }

    fun observeUseMetric(): Flow<Boolean> =
        dao.observe(SettingsKeys.USE_METRIC_UNITS)
            .map { (it?.value ?: 1L) == 1L }
            .distinctUntilChanged()
            .catch { emitAll(flowOf(true)) }

    suspend fun setUseMetric(metric: Boolean) {
        dao.upsert(AppSetting(SettingsKeys.USE_METRIC_UNITS, if (metric) 1L else 0L))
    }

    /** Auto heart-rate interval in minutes (0 = off). Default 30 min. */
    fun observeAutoHrInterval(): Flow<Int> =
        dao.observe(SettingsKeys.AUTO_HR_INTERVAL_MIN)
            .map { (it?.value ?: DEFAULT_HR_INTERVAL_MIN.toLong()).toInt() }
            .distinctUntilChanged()
            .catch { emitAll(flowOf(DEFAULT_HR_INTERVAL_MIN)) }

    suspend fun setAutoHrInterval(minutes: Int) {
        dao.upsert(AppSetting(SettingsKeys.AUTO_HR_INTERVAL_MIN, minutes.toLong()))
    }

    /** Auto blood-pressure interval in minutes (0 = off). Default 60 min. */
    fun observeAutoBpInterval(): Flow<Int> =
        dao.observe(SettingsKeys.AUTO_BP_INTERVAL_MIN)
            .map { (it?.value ?: DEFAULT_BP_INTERVAL_MIN.toLong()).toInt() }
            .distinctUntilChanged()
            .catch { emitAll(flowOf(DEFAULT_BP_INTERVAL_MIN)) }

    suspend fun setAutoBpInterval(minutes: Int) {
        dao.upsert(AppSetting(SettingsKeys.AUTO_BP_INTERVAL_MIN, minutes.toLong()))
    }

    /** Auto blood-oxygen (SpO2) interval in minutes (0 = off). Default 60 min. */
    fun observeAutoSpo2Interval(): Flow<Int> =
        dao.observe(SettingsKeys.AUTO_SPO2_INTERVAL_MIN)
            .map { (it?.value ?: DEFAULT_SPO2_INTERVAL_MIN.toLong()).toInt() }
            .distinctUntilChanged()
            .catch { emitAll(flowOf(DEFAULT_SPO2_INTERVAL_MIN)) }

    suspend fun setAutoSpo2Interval(minutes: Int) {
        dao.upsert(AppSetting(SettingsKeys.AUTO_SPO2_INTERVAL_MIN, minutes.toLong()))
    }

    companion object {
        const val NO_PROFILE = -1L
        const val DEFAULT_HR_INTERVAL_MIN = 30
        const val DEFAULT_BP_INTERVAL_MIN = 60
        const val DEFAULT_SPO2_INTERVAL_MIN = 60
    }
}
