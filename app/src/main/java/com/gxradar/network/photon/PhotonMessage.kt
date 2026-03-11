package com.gxradar.network.photon

/**
 * Sealed hierarchy of parsed Photon Protocol16 messages.
 *
 * For Albion Online the interesting type is Event — the actual
 * game event code lives in params[252], NOT in the Photon envelope.
 */
sealed class PhotonMessage {

    /** MessageType 0x04 — server-push event (entity spawns, moves, despawns) */
    data class Event(
        val eventCode: Int,              // resolved from params[252]
        val parameters: Map<Int, Any?>   // full parameter map, key = byte index
    ) : PhotonMessage()

    /** MessageType 0x02 — client→server operation */
    data class OperationRequest(
        val opCode: Int,
        val parameters: Map<Int, Any?>
    ) : PhotonMessage()

    /** MessageType 0x03 — server→client operation response */
    data class OperationResponse(
        val opCode: Int,
        val returnCode: Short,
        val parameters: Map<Int, Any?>
    ) : PhotonMessage()
}
