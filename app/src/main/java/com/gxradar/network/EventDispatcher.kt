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
 * EventDispatcher — DEBUG VERSION
 *
 * Logs EVERY incoming Photon event so we can diagnose why ENT stays 0.
 * Tag: GXDebug
 *
 * To view logs:
 *   adb logcat -s GXDebug:V *:S
 */
class EventDispatcher(private val logger: DiscoveryLogger) {

    companion object {
        private const val TAG  = "EventDispatcher"
        private const val DTAG = "GXDebug"  // debug tag — filter with adb logcat -s GXDebug

        private const val EV_LEAVE       = 1
        private const val EV_HEARTBEAT   = 2
        private const val EV_MOVE        = 3
        private const val EV_NEW_ITEM    = 18
        private const val EV_NEW_HARVEST = 19
        private const val EV_NEW_MOB     = 20
        private const val EV_NEW_CHAR    = 24
        private const val EV_CHAT        = 60

        // Rate-limit repetitive move events in debug log
        private var moveLogCount  = 0
        private var totalEvents   = 0
        private var unknownCodes  = mutableSetOf<Int>()

        private val RES_KEYWORDS = mapOf(
            "WOOD"  to ResourceType.WOOD,
            "ROCK"  to ResourceType.ROCK,
            "FIBER" to ResourceType.FIBER,
            "HIDE"  to ResourceType.HIDE,
            "ORE"   to ResourceType.ORE
        )
        private val BOSS_WORDS     = listOf("BOSS", "MINIBOSS", "ASPECT", "KEEPER", "GUARDIAN", "SHRINE")
        private val CHAMPION_WORDS = listOf("CHAMPION", "ELITE", "GROUP", "VETERAN")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    fun dispatch(msg: PhotonMessage) {
        when (msg) {
            is PhotonMessage.Event -> {
                totalEvents++
                // Log first 200 events of every unique code we see
                if (totalEvents <= 200 || msg.eventCode !in listOf(EV_HEARTBEAT, EV_MOVE, EV_CHAT)) {
                    logEvent(msg)
                }
                handleEvent(msg)
            }
            is PhotonMessage.OperationResponse -> {
                Log.d(DTAG, "[RESP] opCode=${msg.opCode} returnCode=${msg.returnCode} keys=${msg.parameters.keys}")
            }
            is PhotonMessage.OperationRequest -> {
                Log.v(DTAG, "[REQ]  opCode=${msg.opCode} keys=${msg.parameters.keys}")
            }
        }
    }

    private fun logEvent(ev: PhotonMessage.Event) {
        val p = ev.parameters
        val sb = StringBuilder()
        sb.append("[EVT] code=${ev.eventCode} keys=${p.keys.sorted()} | ")

        // Log the type and value of every parameter
        for (key in p.keys.sorted()) {
            val v = p[key]
            val typeName = v?.javaClass?.simpleName ?: "null"
            val display = when (v) {
                is ByteArray -> "ByteArray[${v.size}]=${v.take(16).map { it.toInt() and 0xFF }}"
                is String    -> "\"$v\""
                else         -> v.toString()
            }
            sb.append("$key($typeName)=$display  ")
        }
        Log.d(DTAG, sb.toString())
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
            else -> {
                if (ev.eventCode !in unknownCodes) {
                    unknownCodes.add(ev.eventCode)
                    Log.w(DTAG, "[UNKNOWN CODE] ${ev.eventCode} — full params: ${ev.parameters}")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resource spawn  — events 18 / 19
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleResource(ev: PhotonMessage.Event) {
        val p        = ev.parameters
        val entityId = p.pInt(0)
        val typeId   = p.pShort(1)?.toInt() ?: -1
        val posBytes = p.pBytes(2)
        val posX     = posBytes?.leFloat(8)  ?: p.pFloat(4)
        val posZ     = posBytes?.leFloat(12) ?: p.pFloat(5)
        val tier     = p.pByte(7)?.toUByte()?.toInt()  ?: 0
        val enchant  = p.pByte(11)?.toUByte()?.toInt() ?: 0
        val name     = p.pStr(17)

        Log.i(DTAG, "[RESOURCE ev=${ev.eventCode}] entityId=$entityId typeId=$typeId " +
            "posBytes=${posBytes?.size} posX=$posX posZ=$posZ tier=$tier enchant=$enchant name=$name")

        if (entityId == null) { Log.e(DTAG, "  ✗ DROPPED: entityId null"); return }
        if (posX == null)     { Log.e(DTAG, "  ✗ DROPPED: posX null (posBytes=${posBytes?.size}, keys=${p.keys.sorted()})"); return }
        if (posZ == null)     { Log.e(DTAG, "  ✗ DROPPED: posZ null"); return }

        val entity: RadarEntity? = when {
            name != null -> resourceFromName(entityId, name, posX, posZ).also {
                if (it == null) Log.w(DTAG, "  ✗ resourceFromName returned null for name=$name")
                else Log.i(DTAG, "  ✓ ADDED via name: tier=${it.tier} type=${it.resourceType} enchant=${it.enchantLevel}")
            }
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
                ).also { Log.i(DTAG, "  ✓ ADDED via typeId=$typeId (unknown name)") }
            }
            else -> null.also { Log.e(DTAG, "  ✗ DROPPED: no name and typeId <= 0") }
        }
        entity?.let { EntityStore.upsert(it) }
        Log.d(DTAG, "  EntityStore size now: ${EntityStore.size()}")
    }

    private fun resourceFromName(id: Int, name: String, x: Float, z: Float): RadarEntity? {
        val u       = name.uppercase()
        val tier    = Regex("T(\\d)_").find(u)?.groupValues?.get(1)?.toIntOrNull()
        val enchant = Regex("LEVEL(\\d)").find(u)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val resType = RES_KEYWORDS.entries.firstOrNull { u.contains(it.key) }?.value

        Log.v(DTAG, "  resourceFromName: name=$name tier=$tier resType=$resType enchant=$enchant")

        if (tier == null) { Log.w(DTAG, "  ✗ no tier in name: $name"); return null }
        if (resType == null) { Log.w(DTAG, "  ✗ no resType in name: $name"); return null }

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
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleMob(ev: PhotonMessage.Event) {
        val p        = ev.parameters
        val entityId = p.pInt(0)
        val typeId   = p.pShort(1)?.toInt() ?: -1
        val posBytes = p.pBytes(2)
        val posX     = posBytes?.leFloat(8)  ?: p.pFloat(4)
        val posZ     = posBytes?.leFloat(12) ?: p.pFloat(5)
        val tier     = p.pByte(7)?.toUByte()?.toInt() ?: 0
        val name     = p.pStr(17)

        Log.i(DTAG, "[MOB] entityId=$entityId typeId=$typeId posX=$posX posZ=$posZ tier=$tier name=$name")

        if (entityId == null) { Log.e(DTAG, "  ✗ DROPPED: entityId null"); return }
        if (posX == null)     { Log.e(DTAG, "  ✗ DROPPED: posX null (keys=${p.keys.sorted()})"); return }
        if (posZ == null)     { Log.e(DTAG, "  ✗ DROPPED: posZ null"); return }

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
        Log.i(DTAG, "  ✓ MOB ADDED type=$entityType  EntityStore size=${EntityStore.size()}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Player spawn  — event 24
    // ─────────────────────────────────────────────────────────────────────────

    private fun handlePlayer(ev: PhotonMessage.Event) {
        val p        = ev.parameters
        val entityId = p.pInt(0)
        val name     = p.pStr(1) ?: "?"
        val posBytes = p.pBytes(2)
        val posX     = posBytes?.leFloat(8)  ?: p.pFloat(4)
        val posZ     = posBytes?.leFloat(12) ?: p.pFloat(5)
        val faction  = p.pByte(11)?.toUByte()?.toInt() ?: 0

        Log.i(DTAG, "[PLAYER] entityId=$entityId name=$name posX=$posX posZ=$posZ faction=$faction")

        if (entityId == null) { Log.e(DTAG, "  ✗ DROPPED: entityId null"); return }
        if (posX == null)     { Log.e(DTAG, "  ✗ DROPPED: posX null (posBytes=${posBytes?.size}, keys=${p.keys.sorted()})"); return }
        if (posZ == null)     { Log.e(DTAG, "  ✗ DROPPED: posZ null"); return }

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
        Log.i(DTAG, "  ✓ PLAYER ADDED $name type=$type  EntityStore size=${EntityStore.size()}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Move  — event 3
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleMove(ev: PhotonMessage.Event) {
        val p        = ev.parameters
        val entityId = p.pInt(0) ?: return
        val posBytes = p.pBytes(1)
        val posX     = posBytes?.leFloat(0) ?: p.pFloat(2) ?: return
        val posZ     = posBytes?.leFloat(4) ?: p.pFloat(3) ?: return
        EntityStore.updatePosition(entityId, posX, posZ)
        if (moveLogCount++ < 5) {
            Log.v(DTAG, "[MOVE] entityId=$entityId posX=$posX posZ=$posZ")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Leave  — event 1
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleLeave(ev: PhotonMessage.Event) {
        val id = ev.parameters.pInt(0) ?: return
        EntityStore.remove(id)
        Log.v(DTAG, "[LEAVE] removed entityId=$id  size=${EntityStore.size()}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Extension helpers
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

    private fun ByteArray.leFloat(offset: Int): Float? {
        if (offset + 4 > this.size) return null
        return ByteBuffer.wrap(this, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .float
    }
}
