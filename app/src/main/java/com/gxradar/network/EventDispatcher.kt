package com.gxradar.network

import android.util.Log
import com.gxradar.data.model.EntityStore
import com.gxradar.data.model.EntityType
import com.gxradar.data.model.RadarEntity
import com.gxradar.data.model.ResourceType
import com.gxradar.network.photon.PhotonMessage
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EventDispatcher(private val discoveryLogger: DiscoveryLogger) {

    companion object {
        private const val TAG = "EventDispatcher"

        private const val EV_LEAVE       = 1
        private const val EV_HEARTBEAT   = 2
        private const val EV_MOVE        = 3
        private const val EV_NEW_ITEM    = 18
        private const val EV_NEW_HARVEST = 19
        private const val EV_NEW_MOB     = 20
        private const val EV_NEW_CHAR    = 24
        private const val EV_CHAT        = 60

        // Plan C — uniqueName string parsers
        private val TIER_REGEX    = Regex("T(\\d)_")
        private val ENCHANT_REGEX = Regex("_LEVEL(\\d)")
    }

    fun dispatch(message: PhotonMessage) {
        when (message) {
            is PhotonMessage.Event             -> handleEvent(message)
            is PhotonMessage.OperationResponse -> { /* Step 3: zone detection */ }
            else                               -> Unit
        }
    }

    // ── Event routing ──────────────────────────────────────────────────

    private fun handleEvent(event: PhotonMessage.Event) {
        when (event.eventCode) {
            EV_HEARTBEAT, EV_CHAT -> return
            EV_NEW_ITEM,
            EV_NEW_HARVEST        -> handleResourceSpawn(event)
            EV_NEW_MOB            -> handleMobSpawn(event)
            EV_NEW_CHAR           -> handlePlayerSpawn(event)
            EV_MOVE               -> handleMove(event)
            EV_LEAVE              -> handleLeave(event)
            else -> Log.v(TAG,
                "Unhandled event ${event.eventCode}, keys=${event.parameters.keys}")
        }
    }

    // ── Resource spawn (events 18 / 19) ──────────────────────────────────

    private fun handleResourceSpawn(event: PhotonMessage.Event) {
        val p        = event.parameters
        val entityId = p.int(0)   ?: return
        val typeId   = p.short(1) ?: -1
        val posBytes = p.bytes(2)
        val posX     = posBytes?.leFloat(8)  ?: p.float(4) ?: return
        val posZ     = posBytes?.leFloat(12) ?: p.float(5) ?: return
        val tier     = p.byte(7)  ?: 0
        val enchant  = p.byte(11) ?: 0
        val name     = p.str(17)

        val entity = if (name != null) {
            buildResourceFromName(entityId, name, posX, posZ)
        } else {
            discoveryLogger.logUnknownEntity(event.eventCode, typeId, null, posX, posZ)
            RadarEntity(
                entityId     = entityId,
                entityType   = EntityType.RESOURCE,
                posX         = posX,
                posZ         = posZ,
                typeId       = typeId,
                tier         = tier,
                enchantLevel = enchant
            )
        }
        EntityStore.upsert(entity)
    }

    // ── Mob spawn (event 20) ──────────────────────────────────────────

    private fun handleMobSpawn(event: PhotonMessage.Event) {
        val p        = event.parameters
        val entityId = p.int(0)   ?: return
        val typeId   = p.short(1) ?: -1
        val posBytes = p.bytes(2)
        val posX     = posBytes?.leFloat(8)  ?: p.float(4) ?: return
        val posZ     = posBytes?.leFloat(12) ?: p.float(5) ?: return
        val tier     = p.byte(7)  ?: 0
        val name     = p.str(17)

        val (entityType, category) = if (name != null) {
            classifyMob(name)
        } else {
            discoveryLogger.logUnknownEntity(event.eventCode, typeId, null, posX, posZ)
            Pair(EntityType.MOB_NORMAL, "standard")
        }

        EntityStore.upsert(
            RadarEntity(
                entityId    = entityId,
                entityType  = entityType,
                posX        = posX,
                posZ        = posZ,
                typeId      = typeId,
                uniqueName  = name,
                tier        = tier,
                mobCategory = category,
                displayName = name
            )
        )
    }

    // ── Player spawn (event 24) ──────────────────────────────────────

    private fun handlePlayerSpawn(event: PhotonMessage.Event) {
        val p           = event.parameters
        val entityId    = p.int(0) ?: return
        val playerName  = p.str(1) ?: "Unknown"
        val posBytes    = p.bytes(2)
        val posX        = posBytes?.leFloat(8)  ?: p.float(4) ?: return
        val posZ        = posBytes?.leFloat(12) ?: p.float(5) ?: return
        val factionFlag = p.byte(11) ?: 0

        val entityType = when (factionFlag) {
            0    -> EntityType.PLAYER_PASSIVE
            1    -> EntityType.PLAYER_FACTION
            255  -> EntityType.PLAYER_HOSTILE
            else -> EntityType.PLAYER_PASSIVE
        }

        EntityStore.upsert(
            RadarEntity(
                entityId    = entityId,
                entityType  = entityType,
                posX        = posX,
                posZ        = posZ,
                displayName = playerName
            )
        )
    }

    // ── Move (event 3) ──────────────────────────────────────────────────

    private fun handleMove(event: PhotonMessage.Event) {
        val p        = event.parameters
        val entityId = p.int(0)  ?: return
        val posBytes = p.bytes(1) ?: return
        val posX     = posBytes.leFloat(8)  ?: return
        val posZ     = posBytes.leFloat(12) ?: return
        EntityStore.updatePosition(entityId, posX, posZ)
    }

    // ── Leave (event 1) — prevents entity map OOM leak ─────────────────────

    private fun handleLeave(event: PhotonMessage.Event) {
        val entityId = event.parameters.int(0) ?: return
        EntityStore.remove(entityId)
    }

    // ── Plan C — parse uniqueName string ───────────────────────────────────

    private fun buildResourceFromName(
        entityId: Int,
        name: String,
        posX: Float,
        posZ: Float
    ): RadarEntity {
        val u = name.uppercase()

        if (u.contains("MIST") || u.contains("WISP")) {
            return RadarEntity(
                entityId   = entityId,
                entityType = EntityType.MIST_WISP,
                posX       = posX,
                posZ       = posZ,
                uniqueName = name
            )
        }

        val tier    = TIER_REGEX.find(u)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val enchant = ENCHANT_REGEX.find(u)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val resType = when {
            u.contains("_WOOD")  -> ResourceType.WOOD
            u.contains("_ROCK")  -> ResourceType.ROCK
            u.contains("_FIBER") -> ResourceType.FIBER
            u.contains("_HIDE")  -> ResourceType.HIDE
            u.contains("_ORE")   -> ResourceType.ORE
            else                 -> ResourceType.UNKNOWN
        }

        return RadarEntity(
            entityId     = entityId,
            entityType   = EntityType.RESOURCE,
            posX         = posX,
            posZ         = posZ,
            uniqueName   = name,
            tier         = tier,
            enchantLevel = enchant,
            resourceType = resType
        )
    }

    private fun classifyMob(name: String): Pair<EntityType, String> {
        val u = name.uppercase()
        return when {
            u.contains("WISP")     -> Pair(EntityType.MIST_WISP,     "wisp")
            u.contains("BOSS")     -> Pair(EntityType.MOB_BOSS,      "boss")
            u.contains("MINIBOSS") -> Pair(EntityType.MOB_BOSS,      "miniboss")
            u.contains("CHAMPION") -> Pair(EntityType.MOB_ENCHANTED, "champion")
            else                   -> Pair(EntityType.MOB_NORMAL,    "standard")
        }
    }

    // ── Parameter map extension helpers ──────────────────────────────────

    private fun Map<Int, Any?>.int(key: Int): Int? = when (val v = this[key]) {
        is Int   -> v
        is Short -> v.toInt()
        is Byte  -> v.toInt() and 0xFF
        else     -> null
    }

    /** Returns Int? (not Short?) so elvis with Int literals works without widening. */
    private fun Map<Int, Any?>.short(key: Int): Int? = when (val v = this[key]) {
        is Short -> v.toInt()
        is Int   -> v
        is Byte  -> v.toInt() and 0xFF
        else     -> null
    }

    private fun Map<Int, Any?>.byte(key: Int): Int? = when (val v = this[key]) {
        is Byte  -> v.toInt() and 0xFF
        is Short -> v.toInt() and 0xFF
        is Int   -> v and 0xFF
        else     -> null
    }

    private fun Map<Int, Any?>.float(key: Int): Float? = when (val v = this[key]) {
        is Float  -> v
        is Double -> v.toFloat()
        else      -> null
    }

    private fun Map<Int, Any?>.str(key: Int): String?      = this[key] as? String
    private fun Map<Int, Any?>.bytes(key: Int): ByteArray? = this[key] as? ByteArray

    /** Little-Endian float32 at byte offset within a ByteArray */
    private fun ByteArray.leFloat(offset: Int): Float? {
        if (size < offset + 4) return null
        return ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float
    }
}
