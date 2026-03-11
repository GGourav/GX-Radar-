package com.gxradar.data.model

import java.util.concurrent.ConcurrentHashMap

/**
 * Shared, thread-safe entity map.
 * Written by the VPN capture thread, read by the overlay render thread.
 */
object EntityStore {

    private val entities = ConcurrentHashMap<Int, RadarEntity>()

    fun upsert(entity: RadarEntity) {
        entities[entity.entityId] = entity
    }

    fun updatePosition(entityId: Int, x: Float, z: Float) {
        entities[entityId]?.let { existing ->
            entities[entityId] = existing.copy(
                posX = x,
                posZ = z,
                lastUpdateMs = System.currentTimeMillis()
            )
        }
    }

    fun remove(entityId: Int) {
        entities.remove(entityId)
    }

    /** Returns a snapshot — safe to iterate on the render thread. */
    fun snapshot(): List<RadarEntity> = entities.values.toList()

    fun clear() = entities.clear()

    fun size(): Int = entities.size
}
