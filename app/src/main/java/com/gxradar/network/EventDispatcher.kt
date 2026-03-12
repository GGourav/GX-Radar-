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
 * EventDispatcher — DEBUG BUILD
 * Writes every Photon event to debug.log so we can diagnose ENT=0.
 * Share the log from inside the app via the Share Log button.
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

        // Counters for summary
        @Volatile var totalDispatched = 0
        @Volatile var totalAdded      = 0
        @Volatile var totalDropped    = 0
        private val seenCodes = mutableSetOf<Int>()
        private var moveCount  = 0
        private var logCount   = 0
        private const val MAX_LOG_LINES = 500  // stop writing after 500 lines to save space
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    fun dispatch(msg: PhotonMessage) {
        when (msg) {
            is PhotonMessage.Event -> {
                totalDispatched++
                handleEvent(msg)
            }
            is PhotonMessage.OperationResponse -> {
                dbg("[RESP] opCode=${msg.opCode} retCode=${msg.returnCode} keys=${msg.parameters.keys.sorted()}")
            }
            else -> Unit
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Routing
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleEvent(ev: PhotonMessage.Event) {
        val code = ev.eventCode

        // Log every unique event code we see (once)
        if (seenCodes.add(code)) {
            dbg("[NEW CODE] $code  keys=${ev.parameters.keys.sorted()}  sample=${ev.parameters.entries.take(4).map { "${it.key}:${it.value?.javaClass?.simpleName}" }}")
        }

        when (code) {
            EV_HEARTBEAT, EV_CHAT -> Unit
            EV_NEW_ITEM,
            EV_NEW_HARVEST        -> handleResource(ev)
            EV_NEW_MOB            -> handleMob(ev)
            EV_NEW_CHAR           -> handlePlayer(ev)
            EV_MOVE               -> handleMove(ev)
            EV_LEAVE              -> handleLeave(ev)
            else -> dbg("[UNKNOWN] code=$code keys=${ev.parameters.keys.sorted()}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resource spawn
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

        dbg("[RES ev=${ev.eventCode}] id=$entityId tid=$typeId pb=${posBytes?.size} x=$posX z=$posZ t=$tier e=$enchant n=$name")

        if (entityId == null) { dbg("  DROP: entityId=null"); totalDropped++; return }
        if (posX     == null) { dbg("  DROP: posX=null  allKeys=${p.keys.sorted()}  pb=${posBytes?.take(20)}"); totalDropped++; return }
        if (posZ     == null) { dbg("  DROP: posZ=null"); totalDropped++; return }

        val entity: RadarEntity? = when {
            name != null -> resourceFromName(entityId, name, posX, posZ).also {
                if (it == null) dbg("  DROP: name parse failed for '$name'")
            }
            typeId > 0 -> {
                logger.logUnknownEntity(ev.eventCode, typeId, null, posX, posZ)
                RadarEntity(entityId=entityId, entityType=EntityType.RESOURCE,
                    posX=posX, posZ=posZ, typeId=typeId, tier=tier, enchantLevel=enchant)
            }
            else -> null.also { dbg("  DROP: name=null and typeId=$typeId") }
        }

        if (entity != null) {
            EntityStore.upsert(entity)
            totalAdded++
            dbg("  OK -> EntityStore=${EntityStore.size()}")
        } else {
            totalDropped++
        }
    }

    private fun resourceFromName(id: Int, name: String, x: Float, z: Float): RadarEntity? {
        val u       = name.uppercase()
        val tier    = Regex("T(\\d)_").find(u)?.groupValues?.get(1)?.toIntOrNull()
        val enchant = Regex("LEVEL(\\d)").find(u)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val resType = RES_KEYWORDS.entries.firstOrNull { u.contains(it.key) }?.value
        if (tier == null || resType == null) return null
        return RadarEntity(entityId=id, entityType=EntityType.RESOURCE,
            posX=x, posZ=z, uniqueName=name, tier=tier, enchantLevel=enchant, resourceType=resType)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mob spawn
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

        dbg("[MOB] id=$entityId tid=$typeId x=$posX z=$posZ t=$tier n=$name")

        if (entityId == null) { dbg("  DROP: entityId=null"); totalDropped++; return }
        if (posX     == null) { dbg("  DROP: posX=null  allKeys=${p.keys.sorted()}  pb=${posBytes?.take(20)}"); totalDropped++; return }
        if (posZ     == null) { dbg("  DROP: posZ=null"); totalDropped++; return }

        if (name == null) logger.logUnknownEntity(ev.eventCode, typeId, null, posX, posZ)

        val u = name?.uppercase() ?: ""
        val entityType = when {
            BOSS_WORDS.any     { u.contains(it) } -> EntityType.MOB_BOSS
            CHAMPION_WORDS.any { u.contains(it) } -> EntityType.MOB_ENCHANTED
            else                                   -> EntityType.MOB_NORMAL
        }
        EntityStore.upsert(RadarEntity(entityId=entityId, entityType=entityType,
            posX=posX, posZ=posZ, typeId=typeId, uniqueName=name, tier=tier, displayName=name))
        totalAdded++
        dbg("  OK $entityType -> EntityStore=${EntityStore.size()}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Player spawn
    // ─────────────────────────────────────────────────────────────────────────

    private fun handlePlayer(ev: PhotonMessage.Event) {
        val p        = ev.parameters
        val entityId = p.pInt(0)
        val name     = p.pStr(1) ?: "?"
        val posBytes = p.pBytes(2)
        val posX     = posBytes?.leFloat(8)  ?: p.pFloat(4)
        val posZ     = posBytes?.leFloat(12) ?: p.pFloat(5)
        val faction  = p.pByte(11)?.toUByte()?.toInt() ?: 0

        dbg("[PLAYER] id=$entityId name=$name x=$posX z=$posZ faction=$faction")

        if (entityId == null) { dbg("  DROP: entityId=null"); totalDropped++; return }
        if (posX     == null) { dbg("  DROP: posX=null  allKeys=${p.keys.sorted()}  pb=${posBytes?.size}"); totalDropped++; return }
        if (posZ     == null) { dbg("  DROP: posZ=null"); totalDropped++; return }

        val type = when (faction) { 255 -> EntityType.PLAYER_HOSTILE; 1 -> EntityType.PLAYER_FACTION; else -> EntityType.PLAYER_PASSIVE }
        EntityStore.upsert(RadarEntity(entityId=entityId, entityType=type, posX=posX, posZ=posZ, displayName=name))
        totalAdded++
        dbg("  OK $name $type -> EntityStore=${EntityStore.size()}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Move
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleMove(ev: PhotonMessage.Event) {
        val p        = ev.parameters
        val entityId = p.pInt(0) ?: return
        val posBytes = p.pBytes(1)
        val posX     = posBytes?.leFloat(0) ?: p.pFloat(2) ?: return
        val posZ     = posBytes?.leFloat(4) ?: p.pFloat(3) ?: return
        EntityStore.updatePosition(entityId, posX, posZ)
        if (moveCount++ < 3) dbg("[MOVE] id=$entityId x=$posX z=$posZ")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Leave
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleLeave(ev: PhotonMessage.Event) {
        val id = ev.parameters.pInt(0) ?: return
        EntityStore.remove(id)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Debug writer — stops after MAX_LOG_LINES to avoid filling storage
    // ─────────────────────────────────────────────────────────────────────────

    private fun dbg(line: String) {
        Log.d(TAG, line)
        if (logCount++ < MAX_LOG_LINES) {
            logger.writeDebug(line)
        } else if (logCount == MAX_LOG_LINES + 1) {
            logger.writeDebug("--- log limit reached: dispatched=$totalDispatched added=$totalAdded dropped=$totalDropped seenCodes=$seenCodes ---")
        }
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

    private fun Map<Int, Any?>.pStr(key: Int): String? = this[key] as? String

    private fun Map<Int, Any?>.pBytes(key: Int): ByteArray? = this[key] as? ByteArray

    private fun ByteArray.leFloat(offset: Int): Float? {
        if (offset + 4 > this.size) return null
        return ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float
    }

    private fun ByteArray.take(n: Int) = this.toList().take(n).map { it.toInt() and 0xFF }
}
