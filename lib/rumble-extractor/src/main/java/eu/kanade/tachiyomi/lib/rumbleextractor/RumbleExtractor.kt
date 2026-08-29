package eu.kanade.tachiyomi.lib.rumbleextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

/**
 * Extractor untuk Rumble video streaming service
 *
 * Supports:
 * - rumble.com/embed/ URLs
 * - MP4 dan HLS formats
 * - Multiple quality options
 *
 * @param client OkHttpClient instance untuk network requests
 */
class RumbleExtractor(private val client: OkHttpClient) {

    /**
     * Extract videos dari Rumble URL
     *
     * @param url Embed URL (e.g., https://rumble.com/embed/VIDEO_ID/?pub=PUB_ID)
     * @param prefix Label prefix untuk quality labels
     * @return List of Video
     */
    fun videosFromUrl(url: String, prefix: String = "Rumble"): List<Video> {
        return try {
            val headers = buildHeaders(url)
            val html = fetchEmbedPage(url, headers)

            extractVideos(html, headers, prefix)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Build headers
     */
    private fun buildHeaders(url: String): Headers {
        return Headers.headersOf(
            "Referer", url,
            "User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
            "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
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
     * Extract video URLs dari HTML
     */
    private fun extractVideos(
        html: String,
        headers: Headers,
        prefix: String,
    ): List<Video> {
        val videoList = mutableListOf<Video>()

        // Pattern 1: MP4 URLs dalam JSON structure
        // "mp4": {"url": "https://..."}
        val mp4Pattern = """"mp4":\s*\{\s*"url":\s*"([^"]+)"""".toRegex()
        mp4Pattern.findAll(html).forEach { match ->
            val videoUrl = match.groupValues[1]
                .replace("\\", "") // Unescape

            videoList.add(
                Video(
                    url = videoUrl,
                    quality = "$prefix - MP4",
                    videoUrl = videoUrl,
                    headers = headers,
                ),
            )
        }

        // Pattern 2: HLS URLs
        // "hls": "https://...m3u8"
        if (videoList.isEmpty()) {
            val hlsPattern = """"hls":\s*"([^"]+\.m3u8)"""".toRegex()
            hlsPattern.find(html)?.groupValues?.get(1)?.let { hlsUrl ->
                val unescapedUrl = hlsUrl.replace("\\", "")

                videoList.add(
                    Video(
                        url = unescapedUrl,
                        quality = "$prefix - HLS",
                        videoUrl = unescapedUrl,
                        headers = headers,
                    ),
                )
            }
        }

        // Pattern 3: Direct video URLs dalam src attributes
        if (videoList.isEmpty()) {
            val srcPattern = """src=["']([^"']+\.mp4[^"']*)["']""".toRegex()
            srcPattern.find(html)?.groupValues?.get(1)?.let { videoUrl ->
                videoList.add(
                    Video(
                        url = videoUrl,
                        quality = prefix,
                        videoUrl = videoUrl,
                        headers = headers,
                    ),
                )
            }
        }

        // Pattern 4: u.m3u8 atau index.m3u8 (common HLS names)
        if (videoList.isEmpty()) {
            val m3u8Pattern = """"(https://[^"]*(?:u|index)\.m3u8[^"]*)"""".toRegex()
            m3u8Pattern.find(html)?.groupValues?.get(1)?.let { videoUrl ->
                videoList.add(
                    Video(
                        url = videoUrl,
                        quality = prefix,
                        videoUrl = videoUrl,
                        headers = headers,
                    ),
                )
            }
        }

        return videoList
    }

    /**
     * Extract videos dari base64 encoded iframe
     */
    fun videosFromBase64(base64: String, prefix: String = "Rumble"): List<Video> {
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
