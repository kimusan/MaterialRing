package com.kschulz.aro.data.repository

import com.kschulz.aro.data.db.ProfileDao
import com.kschulz.aro.data.model.Profile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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

    suspend fun getById(id: Long): Profile? = dao.getById(id)

    suspend fun add(name: String, now: Long): Long =
        dao.insert(Profile(name = name.trim(), createdAtEpochMs = now))

    suspend fun save(profile: Profile): Long =
        if (profile.id == 0L) {
            dao.insert(profile.copy(name = profile.name.trim(), isConfigured = true))
        } else {
            dao.update(profile.copy(name = profile.name.trim(), isConfigured = true))
            profile.id
        }

    suspend fun rename(id: Long, name: String) {
        dao.getById(id)?.let { dao.update(it.copy(name = name.trim())) }
    }

    suspend fun switchTo(id: Long) {
        settings.setActiveProfileId(id)
    }

    suspend fun delete(id: Long) {
        dao.getById(id)?.let { dao.delete(it) }
        val activeId = settings.observeActiveProfileId().first()
        if (activeId == id) {
            val remaining = dao.observeAll().first()
            if (remaining.isNotEmpty()) {
                settings.setActiveProfileId(remaining.first().id)
            } else {
                settings.setActiveProfileId(SettingsRepository.NO_PROFILE)
            }
        }
    }
}
