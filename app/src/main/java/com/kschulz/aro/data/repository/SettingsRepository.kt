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

    companion object {
        const val NO_PROFILE = -1L
    }
}
