package com.kschulz.aro.data.repository

import com.kschulz.aro.data.db.RingDao
import com.kschulz.aro.data.model.Ring
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Bound ring devices, keyed by BLE MAC. Revisions are filled from the DEVICE_INFO
 *  service after the first successful connect. */
@Singleton
class DeviceRepository @Inject constructor(
    private val dao: RingDao,
) {
    fun observeAll(): Flow<List<Ring>> = dao.observeAll()

    fun observe(mac: String): Flow<Ring?> = dao.observeByMac(mac)

    suspend fun getByMac(mac: String): Ring? = dao.getByMac(mac)

    suspend fun bind(mac: String, name: String, now: Long) {
        dao.upsert(Ring(macAddress = mac, displayName = name, boundAtEpochMs = now))
    }

    suspend fun unbind(mac: String) {
        dao.delete(mac)
    }

    suspend fun updateRevisions(mac: String, hw: String, fw: String) {
        dao.getByMac(mac)?.let { dao.upsert(it.copy(hwRevision = hw, fwRevision = fw)) }
    }
}
