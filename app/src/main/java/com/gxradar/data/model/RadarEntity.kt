package com.gxradar.data.model

enum class EntityType {
    RESOURCE,
    MOB_NORMAL,
    MOB_ENCHANTED,
    MOB_BOSS,
    PLAYER_PASSIVE,
    PLAYER_FACTION,
    PLAYER_HOSTILE,
    MIST_WISP,
    UNKNOWN
}

enum class ResourceType { WOOD, ROCK, FIBER, HIDE, ORE, UNKNOWN }

data class RadarEntity(
    val entityId: Int,
    val entityType: EntityType,
    @Volatile var posX: Float,
    @Volatile var posZ: Float,
    val typeId: Int = -1,
    val uniqueName: String? = null,
    val tier: Int = 0,
    val enchantLevel: Int = 0,          // 0=none  1=.1  2=.2  3=.3  4=.4
    val resourceType: ResourceType = ResourceType.UNKNOWN,
    val mobCategory: String? = null,    // "standard" | "champion" | "boss"
    val displayName: String? = null,    // player name or mob uniqueName
    @Volatile var lastUpdateMs: Long = System.currentTimeMillis()
)
