package eu.kanade.tachiyomi.lib.rubyvidhubextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

/**
 * Extractor untuk RubyVidHub video streaming service
 *
 * Supports:
 * - rubyvidhub.com embed pages
 * - Packed JavaScript extraction
 * - HLS streaming dengan multiple qualities
 *
 * Quality markers:
 * - _o = Original quality
 * - _h = High (720p)
 * - _n = Normal (480p)
 * - _l = Low (360p)
 *
 * @param client OkHttpClient instance untuk network requests
 */
class RubyVidHubExtractor(private val client: OkHttpClient) {

    /**
     * Extract videos dari RubyVidHub URL
     *
     * @param url Embed URL (e.g., https://rubyvidhub.com/embed-nl6xk6ovhayo.html)
     * @param prefix Label prefix untuk quality labels
     * @return List of Video dengan berbagai kualitas
     */
    fun videosFromUrl(url: String, prefix: String = "RubyVid"): List<Video> {
        return try {
            val headers = buildHeaders(url)
            val html = fetchEmbedPage(url, headers)
            val masterUrl = extractMasterPlaylistUrl(html) ?: return emptyList()

            val masterPlaylist = fetchMasterPlaylist(masterUrl, headers)
            parseM3u8Playlist(masterPlaylist, masterUrl, headers, prefix)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Build headers dengan referer
     */
    private fun buildHeaders(url: String): Headers {
        return Headers.headersOf(
            "Referer", url,
            "User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Accept", "*/*",
            "Accept-Language", "en-US,en;q=0.9",
        )
    }

    /**
     * Fetch embed page HTML
     */
    private fun fetchEmbedPage(url: String, headers: Headers): String {
        return client.newCall(GET(url, headers))
            .execute()
            .body
            .string()
    }

    /**
     * Extract master playlist URL dari HTML
     * Handles packed JavaScript
     */
    private fun extractMasterPlaylistUrl(html: String): String? {
        // Try to extract using JsUnpacker
        JsUnpacker.extractSourceUrl(html)?.let { return it }

        // Fallback: direct regex patterns
        val patterns = listOf(
            """sources:\s*\[\s*\{\s*file:\s*["']([^"']+\.m3u8[^"']*)["']""".toRegex(),
            """"(https://[^"]*\.m3u8[^"]*)"""".toRegex(),
            """file:\s*["']([^"']+\.m3u8[^"']*)["']""".toRegex(),
        )

        for (pattern in patterns) {
            pattern.find(html)?.groupValues?.get(1)?.let { url ->
                if (url.isNotEmpty()) return url
            }
        }

        return null
    }

    /**
     * Fetch master playlist
     */
    private fun fetchMasterPlaylist(url: String, headers: Headers): String {
        return client.newCall(GET(url, headers))
            .execute()
            .body
            .string()
    }

    /**
     * Parse M3U8 master playlist untuk extract semua kualitas
     */
    private fun parseM3u8Playlist(
        playlist: String,
        baseUrl: String,
        headers: Headers,
        prefix: String,
    ): List<Video> {
        val videoList = mutableListOf<Video>()
        val lines = playlist.lines()

        var currentQuality = "Unknown"

        for (i in lines.indices) {
            val line = lines[i].trim()

            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    // Parse quality info jika ada
                    val resolution = line.substringAfter("RESOLUTION=", "")
                        .substringBefore(",")
                        .substringAfter("x", "")

                    currentQuality = when {
                        resolution.contains("1080") -> "1080p"
                        resolution.contains("720") -> "720p"
                        resolution.contains("480") -> "480p"
                        resolution.contains("360") -> "360p"
                        else -> "Unknown"
                    }
                }

                line.isNotEmpty() && !line.startsWith("#") -> {
                    // URL playlist untuk quality tertentu
                    val videoUrl = if (line.startsWith("http")) {
                        line
                    } else {
                        val base = baseUrl.substringBeforeLast("/")
                        "$base/$line"
                    }

                    // Detect quality dari URL jika belum terdetect dari STREAM-INF
                    val detectedQuality = when {
                        currentQuality != "Unknown" -> currentQuality
                        videoUrl.contains("_o") || videoUrl.contains("_o.") -> "Original"
                        videoUrl.contains("_h") || videoUrl.contains("_h.") -> "720p"
                        videoUrl.contains("_n") || videoUrl.contains("_n.") -> "480p"
                        videoUrl.contains("_l") || videoUrl.contains("_l.") -> "360p"
                        else -> "Unknown"
                    }

                    val qualityLabel = if (prefix.isNotEmpty()) {
                        "$prefix - $detectedQuality"
                    } else {
                        detectedQuality
                    }

                    videoList.add(
                        Video(
                            url = videoUrl,
                            quality = qualityLabel,
                            videoUrl = videoUrl,
                            headers = headers,
                        ),
                    )

                    currentQuality = "Unknown"
                }
            }
        }

        // Sort by quality priority
        return videoList.sortedByDescending { video ->
            when {
                video.quality.contains("Original", ignoreCase = true) -> 5
                video.quality.contains("1080") -> 4
                video.quality.contains("720") -> 3
                video.quality.contains("480") -> 2
                video.quality.contains("360") -> 1
                else -> 0
            }
        }
    }
}
