package eu.kanade.tachiyomi.lib.dailymotionextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient

/**
 * Extractor untuk Dailymotion video streaming service
 *
 * Supports:
 * - geo.dailymotion.com embed players
 * - Player metadata API
 * - Multiple quality options (240p - 1080p)
 *
 * @param client OkHttpClient instance untuk network requests
 * @param json Json instance untuk parsing (optional, uses default if not provided)
 */
class DailymotionExtractor(
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    companion object {
        private const val API_BASE = "https://www.dailymotion.com/player/metadata/video"
        private val QUALITY_ORDER = listOf("1080", "720", "480", "380", "240")
    }

    /**
     * Extract videos dari Dailymotion URL
     *
     * @param url Embed URL atau player URL
     * @param prefix Label prefix untuk quality labels
     * @return List of Video dengan berbagai kualitas
     */
    fun videosFromUrl(url: String, prefix: String = "Dailymotion"): List<Video> {
        return try {
            val videoId = extractVideoId(url) ?: return emptyList()
            val metadata = fetchMetadata(videoId)

            parseQualities(metadata, prefix, url)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Extract video ID dari URL
     *
     * Supports formats:
     * - geo.dailymotion.com/player/{VIDEO_ID}.html
     * - geo.dailymotion.com/player/{VIDEO_ID}.html?video={HASH}
     * - dailymotion.com/video/{VIDEO_ID}
     */
    private fun extractVideoId(url: String): String? {
        return when {
            url.contains("/player/") -> {
                url.substringAfter("/player/")
                    .substringBefore(".html")
                    .substringBefore("?")
            }
            url.contains("/video/") -> {
                url.substringAfter("/video/")
                    .substringBefore("?")
                    .substringBefore("/")
            }
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }

    /**
     * Fetch metadata dari Dailymotion API
     */
    private fun fetchMetadata(videoId: String): DailymotionMetadata {
        val apiUrl = "$API_BASE/$videoId"

        val response = client.newCall(
            GET(
                apiUrl,
                Headers.headersOf(
                    "User-Agent", "Mozilla/5.0",
                    "Accept", "application/json",
                ),
            ),
        ).execute()

        val jsonText = response.body.string()
        return json.decodeFromString<DailymotionMetadata>(jsonText)
    }

    /**
     * Parse qualities dari metadata
     */
    private fun parseQualities(
        metadata: DailymotionMetadata,
        prefix: String,
        refererUrl: String,
    ): List<Video> {
        val videoList = mutableListOf<Video>()
        val qualities = metadata.qualities ?: return emptyList()

        val headers = Headers.headersOf(
            "Referer", "https://geo.dailymotion.com/",
            "User-Agent", "Mozilla/5.0",
            "Accept", "*/*",
        )

        // Process dalam urutan quality (highest first)
        QUALITY_ORDER.forEach { quality ->
            val qualityArray = qualities[quality]
            if (qualityArray != null && qualityArray.isNotEmpty()) {
                val qualityInfo = qualityArray[0]

                videoList.add(
                    Video(
                        url = qualityInfo.url,
                        quality = "$prefix - ${quality}p",
                        videoUrl = qualityInfo.url,
                        headers = headers,
                    ),
                )
            }
        }

        return videoList
    }

    /**
     * Extract videos dari base64 encoded iframe
     * Utility function untuk auto-detection flow
     */
    fun videosFromBase64(base64: String, prefix: String = "Dailymotion"): List<Video> {
        return try {
            val decoded = String(android.util.Base64.decode(base64, android.util.Base64.DEFAULT))
            val iframeSrcRegex = """<iframe[^>]+src=["']([^"']+)["']""".toRegex()
            val url = iframeSrcRegex.find(decoded)?.groupValues?.get(1) ?: return emptyList()

            videosFromUrl(url, prefix)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
