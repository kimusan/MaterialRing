package com.kschulz.aro.data.repository

import com.kschulz.aro.data.db.RingDao
import com.kschulz.aro.data.model.Ring
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Bound ring devices, keyed by BLE MAC. Multiple rings may be bound, with at most one
 *  active at a time. Revisions are filled from the DEVICE_INFO service after first connect. */
@Singleton
class DeviceRepository @Inject constructor(
    private val dao: RingDao,
) {
    fun observeAll(): Flow<List<Ring>> = dao.observeAll()

    /** The single currently active ring, falling back to the most recently bound ring if none is flagged. */
    fun observeActive(): Flow<Ring?> = dao.observeAll().map { rings ->
        rings.firstOrNull { it.isActive } ?: rings.firstOrNull()
    }

    fun observe(mac: String): Flow<Ring?> = dao.observeByMac(mac)

    suspend fun getByMac(mac: String): Ring? = dao.getByMac(mac)

    suspend fun bind(mac: String, name: String, now: Long) {
        val existing = dao.getByMac(mac)
        dao.upsert(
            Ring(
                macAddress = mac,
                displayName = name,
                hwRevision = existing?.hwRevision ?: "",
                fwRevision = existing?.fwRevision ?: "",
                boundAtEpochMs = now,
                isActive = true,
            ),
        )
        dao.setActive(mac)
    }

    suspend fun setActive(mac: String) {
        if (dao.getByMac(mac) != null) {
            dao.setActive(mac)
        }
    }

    suspend fun unbind(mac: String) {
        val wasActive = dao.getByMac(mac)?.isActive == true
        dao.delete(mac)
        if (wasActive) {
            val remaining = dao.observeAll().first()
            remaining.firstOrNull()?.let { dao.setActive(it.macAddress) }
        }
    }

    suspend fun updateRevisions(mac: String, hw: String, fw: String) {
        dao.getByMac(mac)?.let { dao.upsert(it.copy(hwRevision = hw, fwRevision = fw)) }
    }
}
