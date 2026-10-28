package com.kschulz.aro.data.repository

import com.kschulz.aro.data.db.ProfileDao
import com.kschulz.aro.data.model.Profile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf

/** Local on-device profiles. No cloud account; switching is a query filter on the
 *  active-profile id, never a logout. */
@Singleton
class ProfileRepository @Inject constructor(
    private val dao: ProfileDao,
    private val settings: SettingsRepository,
) {
    fun observeAll(): Flow<List<Profile>> = dao.observeAll()

    fun observeActive(): Flow<Profile?> =
        settings.observeActiveProfileId()
            .flatMapLatest { id ->
                if (id == SettingsRepository.NO_PROFILE) flowOf(null) else dao.observeById(id)
            }
            .catch { emitAll(flowOf(null)) }

    suspend fun add(name: String, now: Long): Long =
        dao.insert(Profile(name = name, createdAtEpochMs = now))

    suspend fun rename(id: Long, name: String) {
        dao.getById(id)?.let { dao.update(it.copy(name = name)) }
    }

    suspend fun switchTo(id: Long) {
        settings.setActiveProfileId(id)
    }

    suspend fun delete(id: Long) {
        dao.getById(id)?.let { dao.delete(it) }
    }
}
