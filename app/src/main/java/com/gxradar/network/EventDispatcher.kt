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
 * EventDispatcher — translates Photon messages → EntityStore mutations.
 *
 * Resolution priority per event:
 *   Plan C → parse uniqueName string  "T4_WOOD_LEVEL2"
 *   Plan A → SQLite DB lookup by typeId  (wired in Step 3)
 *   Plan B → Discovery Logger (unknown entities → log file)
 *
 * Albion event codes live in params[252], NOT the Photon envelope code.
 *
 *   1  Leave            2  Heartbeat (drop)   3  Move
 *   18 NewSimpleItem    19 NewSimpleHarvestable
 *   20 NewMob           24 NewCharacter        60 Chat (drop)
 */
class EventDispatcher(private val logger: DiscoveryLogger) {

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

        private val RES_KEYWORDS = mapOf(
            "WOOD"  to ResourceType.WOOD,
            "ROCK"  to ResourceType.ROCK,
            "FIBER" to ResourceType.FIBER,
            "HIDE"  to ResourceType.HIDE,
            "ORE"   to ResourceType.ORE
        )
        private val BOSS_WORDS     = listOf("BOSS","MINIBOSS","ASPECT","KEEPER","GUARDIAN","SHRINE")
        private val CHAMPION_WORDS = listOf("CHAMPION","ELITE","GROUP","VETERAN")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    fun dispatch(msg: PhotonMessage) {
        if (msg is PhotonMessage.Event) handleEvent(msg)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event routing
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleEvent(ev: PhotonMessage.Event) {
        when (ev.eventCode) {
            EV_HEARTBEAT, EV_CHAT -> Unit
            EV_NEW_ITEM,
            EV_NEW_HARVEST        -> handleResource(ev)
            EV_NEW_MOB            -> handleMob(ev)
            EV_NEW_CHAR           -> handlePlayer(ev)
            EV_MOVE               -> handleMove(ev)
            EV_LEAVE              -> handleLeave(ev)
            else -> Log.v(TAG, "ev ${ev.eventCode} keys=${ev.parameters.keys}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resource spawn  — events 18 / 19
    // params: 0=entityId(int)  1=typeId(short)  2=posBytes
    //         7=tier(byte)  11=enchant(byte)  17=uniqueName(string, optional)
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleResource(ev: PhotonMessage.Event) {
        val p        = ev.parameters
        val entityId = p.pInt(0)   ?: return
        val typeId   = p.pShort(1) ?: -1
        val posBytes = p.pBytes(2)
        val posX     = posBytes?.leFloat(8)  ?: p.pFloat(4) ?: return
        val posZ     = posBytes?.leFloat(12) ?: p.pFloat(5) ?: return
        val tier     = p.pByte(7)?.toUByte()?.toInt()  ?: 0
        val enchant  = p.pByte(11)?.toUByte()?.toInt() ?: 0
        val name     = p.pStr(17)

        val entity: RadarEntity? = when {
            name != null -> resourceFromName(entityId, name, posX, posZ)
            typeId > 0   -> {
                logger.logUnknownEntity(ev.eventCode, typeId, null, posX, posZ)
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
            else -> null
        }
        entity?.let { EntityStore.upsert(it) }
    }

    private fun resourceFromName(id: Int, name: String, x: Float, z: Float): RadarEntity? {
        val u = name.uppercase()
        val tier = Regex("T(\\d)_").find(u)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val enchant = Regex("LEVEL(\\d)").find(u)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val resType = RES_KEYWORDS.entries.firstOrNull { u.contains(it.key) }?.value
            ?: return null
        return RadarEntity(
            entityId     = id,
            entityType   = EntityType.RESOURCE,
            posX         = x,
            posZ         = z,
            uniqueName   = name,
            tier         = tier,
            enchantLevel = enchant,
            resourceType = resType
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mob spawn  — event 20
    // params: 0=entityId  1=typeId  2=posBytes  7=tier  17=uniqueName
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleMob(ev: PhotonMessage.Event) {
        val p        = ev.parameters
        val entityId = p.pInt(0)   ?: return
        val typeId   = p.pShort(1) ?: -1
        val posBytes = p.pBytes(2)
        val posX     = posBytes?.leFloat(8)  ?: p.pFloat(4) ?: return
        val posZ     = posBytes?.leFloat(12) ?: p.pFloat(5) ?: return
        val tier     = p.pByte(7)?.toUByte()?.toInt() ?: 0
        val name     = p.pStr(17)

        if (name == null) logger.logUnknownEntity(ev.eventCode, typeId, null, posX, posZ)

        val entityType = when {
            name != null -> {
                val u = name.uppercase()
                when {
                    BOSS_WORDS.any     { u.contains(it) } -> EntityType.MOB_BOSS
                    CHAMPION_WORDS.any { u.contains(it) } -> EntityType.MOB_ENCHANTED
                    else                                   -> EntityType.MOB_NORMAL
                }
            }
            else -> EntityType.MOB_NORMAL
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
                displayName = name
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Player spawn  — event 24
    // params: 0=entityId  1=name(String)  2=posBytes  11=factionFlags
    // ─────────────────────────────────────────────────────────────────────────

    private fun handlePlayer(ev: PhotonMessage.Event) {
        val p        = ev.parameters
        val entityId = p.pInt(0) ?: return
        val name     = p.pStr(1) ?: "?"
        val posBytes = p.pBytes(2)
        val posX     = posBytes?.leFloat(8)  ?: p.pFloat(4) ?: return
        val posZ     = posBytes?.leFloat(12) ?: p.pFloat(5) ?: return
        val faction  = p.pByte(11)?.toUByte()?.toInt() ?: 0

        val type = when (faction) {
            255  -> EntityType.PLAYER_HOSTILE
            1    -> EntityType.PLAYER_FACTION
            else -> EntityType.PLAYER_PASSIVE
        }

        EntityStore.upsert(
            RadarEntity(
                entityId    = entityId,
                entityType  = type,
                posX        = posX,
                posZ        = posZ,
                displayName = name
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Move  — event 3
    // params: 0=entityId  1=posBytes (LE float at [0] and [4])
    //   OR    0=entityId  2=posX(float)  3=posZ(float)
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleMove(ev: PhotonMessage.Event) {
        val p        = ev.parameters
        val entityId = p.pInt(0) ?: return
        val posBytes = p.pBytes(1)
        val posX     = posBytes?.leFloat(0) ?: p.pFloat(2) ?: return
        val posZ     = posBytes?.leFloat(4) ?: p.pFloat(3) ?: return
        EntityStore.updatePosition(entityId, posX, posZ)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Leave / despawn  — event 1
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleLeave(ev: PhotonMessage.Event) {
        val id = ev.parameters.pInt(0) ?: return
        EntityStore.remove(id)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Extension helpers — type-safe param extraction
    // ─────────────────────────────────────────────────────────────────────────

    private fun Map<Int, Any?>.pInt(key: Int): Int? =
        when (val v = this[key]) {
            is Int   -> v
            is Short -> v.toInt()
            is Byte  -> v.toInt() and 0xFF
            else     -> null
        }

    private fun Map<Int, Any?>.pShort(key: Int): Short? =
        when (val v = this[key]) {
            is Short -> v
            is Int   -> v.toShort()
            is Byte  -> (v.toInt() and 0xFF).toShort()
            else     -> null
        }

    private fun Map<Int, Any?>.pByte(key: Int): Byte? =
        when (val v = this[key]) {
            is Byte  -> v
            is Int   -> v.toByte()
            is Short -> v.toByte()
            else     -> null
        }

    private fun Map<Int, Any?>.pFloat(key: Int): Float? =
        when (val v = this[key]) {
            is Float  -> v
            is Double -> v.toFloat()
            else      -> null
        }

    private fun Map<Int, Any?>.pStr(key: Int): String? =
        this[key] as? String

    private fun Map<Int, Any?>.pBytes(key: Int): ByteArray? =
        this[key] as? ByteArray

    /**
     * Extract a Little-Endian float from a ByteArray at [offset].
     * Position data in Albion packets is always LE, NOT BE.
     */
    private fun ByteArray.leFloat(offset: Int): Float? {
        if (offset + 4 > this.size) return null
        return ByteBuffer.wrap(this, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .float
    }
}
