package com.gxradar.network

import android.util.Log
import com.gxradar.data.model.EntityStore
import com.gxradar.data.model.EntityType
import com.gxradar.data.model.RadarEntity
import com.gxradar.data.model.ResourceType
import com.gxradar.network.photon.PhotonMessage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Translates parsed Photon messages into EntityStore mutations.
 *
 * Entity resolution priority:
 *   Plan A — Step 3 SQLite DB lookup by typeId  (not wired yet, placeholder below)
 *   Plan C — Parse uniqueName string directly    (T4_WOOD_LEVEL2 → tier/type/enchant)
 *   Plan B — Discovery Logger for anything left  (seeds the Step 3 DB)
 *
 * Albion event codes (params[252]):
 *   1  Leave            2  Heartbeat (ignore)    3  Move
 *   5  ChangeEquipment  18 NewSimpleItem          19 NewSimpleHarvestable
 *   20 NewMob           24 NewCharacter           60 Chat (ignore)
 */
class EventDispatcher(private val discoveryLogger: DiscoveryLogger) {

    companion object {
        private const val TAG = "EventDispatcher"

        private const val EV_LEAVE        = 1
        private const val EV_HEARTBEAT    = 2
        private const val EV_MOVE         = 3
        private const val EV_NEW_ITEM     = 18
        private const val EV_NEW_HARVEST  = 19
        private const val EV_NEW_MOB      = 20
        private const val EV_NEW_CHAR     = 24
        private const val EV_CHAT         = 60
    }

    fun dispatch(message: PhotonMessage) {
        when (message) {
            is PhotonMessage.Event             -> handleEvent(message)
            is PhotonMessage.OperationResponse -> { /* Step 3: zone detection via JoinFinished */ }
            else                               -> Unit
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event routing
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleEvent(event: PhotonMessage.Event) {
        when (event.eventCode) {
            EV_HEARTBEAT, EV_CHAT -> return          // discard silently
            EV_NEW_ITEM,
            EV_NEW_HARVEST        -> handleResourceSpawn(event)
            EV_NEW_MOB            -> handleMobSpawn(event)
            EV_NEW_CHAR           -> handlePlayerSpawn(event)
            EV_MOVE               -> handleMove(event)
            EV_LEAVE              -> handleLeave(event)
            else -> Log.v(TAG,
                "Unhandled event ${event.eventCode}, params=${event.parameters.keys}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resource spawn  (events 18 / 19)
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleResourceSpawn(event: PhotonMessage.Event) {
        val p        = event.parameters
        val entityId = p.int(0)   ?: return
        val typeId   = p.short(1) ?: -1
        val posBytes = p.bytes(2)
        val posX     = posBytes?.leFloat(8)  ?: p.float(4) ?: return
        val posZ     = posBytes?.leFloat(12) ?: p.float(5) ?: return
        val tier     = (p.byte(7)  ?: 0).ub()
        val enchant  = (p.byte(11) ?: 0).ub()
        val name     = p.str(17)   // Plan C string — e.g. "T4_WOOD_LEVEL2"

        val entity = when {
            name != null -> buildResourceFromName(entityId, name, posX, posZ)
            else -> {
                // Plan A placeholder — will be replaced in Step 3 with DB lookup
                // Plan B: log it for DB seeding
                discoveryLogger.logUnknownEntity(event.eventCode, typeId, null, posX, posZ)
                RadarEntity(
                    entityId    = entityId,
                    entityType  = EntityType.RESOURCE,
                    posX        = posX,
                    posZ        = posZ,
                    typeId      = typeId,
                    tier        = tier,
                    enchantLevel = enchant
                )
            }
        }
        EntityStore.upsert(entity)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mob spawn  (event 20)
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleMobSpawn(event: PhotonMessage.Event) {
        val p        = event.parameters
        val entityId = p.int(0)   ?: return
        val typeId   = p.short(1) ?: -1
        val posBytes = p.bytes(2)
        val posX     = posBytes?.leFloat(8)  ?: p.float(4) ?: return
        val posZ     = posBytes?.leFloat(12) ?: p.float(5) ?: return
        val tier     = (p.byte(7) ?: 0).ub()
        val name     = p.str(17)

        val (entityType, category) = when {
            name != null -> classifyMob(name)
            else -> {
                discoveryLogger.logUnknownEntity(event.eventCode, typeId, null, posX, posZ)
                Pair(EntityType.MOB_NORMAL, "standard")
            }
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

    // ─────────────────────────────────────────────────────────────────────────
    // Player spawn  (event 24)
    // ─────────────────────────────────────────────────────────────────────────

    private fun handlePlayerSpawn(event: PhotonMessage.Event) {
        val p           = event.parameters
        val entityId    = p.int(0)   ?: return
        val playerName  = p.str(1)   ?: "Unknown"
        val posBytes    = p.bytes(2)
        val posX        = posBytes?.leFloat(8)  ?: p.float(4) ?: return
        val posZ        = posBytes?.leFloat(12) ?: p.float(5) ?: return
        val factionFlag = (p.byte(11) ?: 0).ub()

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

    // ─────────────────────────────────────────────────────────────────────────
    // Move  (event 3)
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleMove(event: PhotonMessage.Event) {
        val p        = event.parameters
        val entityId = p.int(0)   ?: return
        val posBytes = p.bytes(1)
        val posX     = posBytes?.leFloat(8)  ?: return
        val posZ     = posBytes?.leFloat(12) ?: return
        EntityStore.updatePosition(entityId, posX, posZ)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Leave  (event 1) — MUST run to prevent entity map OOM leak
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleLeave(event: PhotonMessage.Event) {
        val entityId = event.parameters.int(0) ?: return
        EntityStore.remove(entityId)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Plan C — parse uniqueName string
    // ─────────────────────────────────────────────────────────────────────────

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
            u.contains("WISP")            -> Pair(EntityType.MIST_WISP,    "wisp")
            u.contains("BOSS")            -> Pair(EntityType.MOB_BOSS,     "boss")
            u.contains("MINIBOSS")        -> Pair(EntityType.MOB_BOSS,     "miniboss")
            u.contains("CHAMPION")        -> Pair(EntityType.MOB_ENCHANTED,"champion")
            else                          -> Pair(EntityType.MOB_NORMAL,   "standard")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parameter map extension helpers (type-coercing)
    // ─────────────────────────────────────────────────────────────────────────

    private fun Map<Int, Any?>.int(key: Int): Int? = when (val v = this[key]) {
        is Int   -> v
        is Short -> v.toInt()
        is Byte  -> v.toInt() and 0xFF
        else     -> null
    }

    private fun Map<Int, Any?>.short(key: Int): Short? = when (val v = this[key]) {
        is Short -> v
        is Int   -> v.toShort()
        is Byte  -> (v.toInt() and 0xFF).toShort()
        else     -> null
    }

    private fun Map<Int, Any?>.byte(key: Int): Byte? = when (val v = this[key]) {
        is Byte  -> v
        is Short -> v.toByte()
        is Int   -> v.toByte()
        else     -> null
    }

    private fun Map<Int, Any?>.float(key: Int): Float? = when (val v = this[key]) {
        is Float  -> v
        is Double -> v.toFloat()
        else      -> null
    }

    private fun Map<Int, Any?>.str(key: Int): String? = this[key] as? String

    private fun Map<Int, Any?>.bytes(key: Int): ByteArray? = this[key] as? ByteArray

    /** Unsigned byte value as Int */
    private fun Byte.ub(): Int = this.toInt() and 0xFF

    /** Little-Endian float32 at byte offset within a ByteArray */
    private fun ByteArray.leFloat(offset: Int): Float? {
        if (size < offset + 4) return null
        return ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float
    }

    companion object {
        private val TIER_REGEX    = Regex("T(\\d)_")
        private val ENCHANT_REGEX = Regex("_LEVEL(\\d)")
    }
}
