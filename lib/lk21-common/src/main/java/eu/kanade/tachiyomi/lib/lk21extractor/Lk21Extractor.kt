package eu.kanade.tachiyomi.lib.lk21extractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class Lk21Extractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, name: String = "LK21"): List<Video> {
        android.util.Log.d("Lk21Extractor", "Extracting from: $url")

        val videoList = mutableListOf<Video>()
        val headers = Headers.Builder()
            .add("Referer", "https://lk21.party/")
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        return try {
            val document = client.newCall(GET(url, headers)).execute().asJsoup()

            // Try multiple selectors for different LK21 versions
            val playerOptions = document.select(
                "select#player-select option, " +
                "ul#player-list li a, " +
                "select.mirror option",
            )

            android.util.Log.d("Lk21Extractor", "Found ${playerOptions.size} player options")

            playerOptions.forEachIndexed { index, element ->
                try {
                    // Get player URL and name
                    val playerUrl = element.attr("value").ifEmpty {
                        element.attr("data-url") 
                    }

                    val serverName = element.attr("data-server").ifEmpty {
                        element.text().trim()
                    }.uppercase()

                    android.util.Log.d("Lk21Extractor", "[$index] Server: $serverName")

                    if (playerUrl.isNotEmpty()) {
                        // Try to extract actual video from player iframe
                        val videos = extractFromPlayer(playerUrl, "$name - $serverName", headers)

                        if (videos.isNotEmpty()) {
                            videoList.addAll(videos)
                            android.util.Log.d("Lk21Extractor", "[$index] Extracted ${videos.size} videos")
                        } else {
                            // Fallback: add iframe URL
                            videoList.add(
                                Video(
                                    playerUrl, 
                                    "$name - $serverName (Iframe)",
                                    playerUrl, 
                                    headers = headers,
                                ),
                            )
                            android.util.Log.d("Lk21Extractor", "[$index] Added as iframe fallback")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Lk21Extractor", "[$index] Error: ${e.message}")
                }
            }

            android.util.Log.d("Lk21Extractor", "Total videos: ${videoList.size}")

            // Sort by quality preference
            sortVideos(videoList)

        } catch (e: Exception) {
            android.util.Log.e("Lk21Extractor", "Extraction failed: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Extract actual video URLs from player iframe
     */
    private fun extractFromPlayer(playerUrl: String, serverName: String, headers: Headers): List<Video> {
        val videos = mutableListOf<Video>()

        try {
            android.util.Log.d("Lk21Extractor", "Loading player: $playerUrl")

            val response = client.newCall(GET(playerUrl, headers)).execute()
            val html = response.body.string()

            // Method 1: Find .m3u8 URLs
            val m3u8Regex = Regex("""["']?(https?://[^"'\s]+\.m3u8[^"'\s]*)["']?""")
            m3u8Regex.findAll(html).forEach { match ->
                val m3u8Url = match.groupValues[1].trim('"', '\'', ' ')
                if (m3u8Url.length > 20) { // Filter out false positives
                    videos.add(Video(m3u8Url, "$serverName - HLS", m3u8Url, headers = headers))
                    android.util.Log.d("Lk21Extractor", "Found M3U8: ${m3u8Url.take(60)}...")
                }
            }

            // Method 2: Find .mp4 URLs
            val mp4Regex = Regex("""["']?(https?://[^"'\s]+\.mp4[^"'\s]*)["']?""")
            mp4Regex.findAll(html).forEach { match ->
                val mp4Url = match.groupValues[1].trim('"', '\'', ' ')
                if (mp4Url.length > 20) {
                    val quality = when {
                        mp4Url.contains("1080") -> "$serverName - 1080p"
                        mp4Url.contains("720") -> "$serverName - 720p"
                        mp4Url.contains("480") -> "$serverName - 480p"
                        mp4Url.contains("360") -> "$serverName - 360p"
                        else -> "$serverName - MP4"
                    }
                    videos.add(Video(mp4Url, quality, mp4Url, headers = headers))
                    android.util.Log.d("Lk21Extractor", "Found MP4: ${mp4Url.take(60)}...")
                }
            }

            // Method 3: Find player configs
            val configRegex = Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""")
            configRegex.findAll(html).forEach { match ->
                val fileUrl = match.groupValues[1]
                if (fileUrl.startsWith("http") && (fileUrl.contains(".m3u8") || fileUrl.contains(".mp4"))) {
                    videos.add(Video(fileUrl, "$serverName - Config", fileUrl, headers = headers))
                    android.util.Log.d("Lk21Extractor", "Found config URL: ${fileUrl.take(60)}...")
                }
            }

            // Method 4: Check for packed JS
            if (JsUnpacker.detect(html)) {
                android.util.Log.d("Lk21Extractor", "Packed JS detected, unpacking...")
                val unpacked = JsUnpacker.unpack(html)

                // Look for sources in unpacked code
                val sourceRegex = Regex("""file:\s*["']([^"']+)["']""")
                sourceRegex.findAll(unpacked).forEach { match ->
                    val sourceUrl = match.groupValues[1]
                    if (sourceUrl.startsWith("http")) {
                        videos.add(Video(sourceUrl, "$serverName - Unpacked", sourceUrl, headers = headers))
                        android.util.Log.d("Lk21Extractor", "Found unpacked source: ${sourceUrl.take(60)}...")
                    }
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("Lk21Extractor", "Player extraction error: ${e.message}")
        }

        // Remove duplicates
        return videos.distinctBy { it.url }
    }

    private fun sortVideos(list: List<Video>): List<Video> {
        return list.sortedWith(
            compareByDescending<Video> {
                val quality = it.quality.lowercase()
                when {
                    quality.contains("720p") -> 10
                    quality.contains("480p") -> 9
                    quality.contains("360p") -> 8
                    quality.contains("1080p") -> 5
                    else -> 0
                }
            },
        )
    }
}
