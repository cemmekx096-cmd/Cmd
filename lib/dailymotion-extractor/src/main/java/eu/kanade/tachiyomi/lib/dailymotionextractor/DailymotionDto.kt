package eu.kanade.tachiyomi.lib.dailymotionextractor

import kotlinx.serialization.Serializable

/**
 * Data classes untuk Dailymotion Player Metadata API
 */

@Serializable
data class DailymotionMetadata(
    val qualities: Map<String, List<QualityInfo>>? = null,
    val id: String? = null,
    val title: String? = null,
    val duration: Int? = null,
)

@Serializable
data class QualityInfo(
    val type: String,
    val url: String,
)
