package eu.kanade.tachiyomi.lib.anichin2extractor 

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.OkHttpClient

class Anichin2Extractor(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    fun videosFromUrl(url: String, server: String): List<Video> {
        android.util.Log.d("Anichin2Extractor", "Extracting from: $url")

        val videoList = mutableListOf<Video>()

        try {
            when {
                // DAILYMOTION - Extract via official API
                url.contains("dailymotion", ignoreCase = true) -> {
                    videoList.addAll(extractDailymotion(url, server))
                }

                // RUMBLE - Extract from embedded JSON
                url.contains("rumble", ignoreCase = true) -> {
                    videoList.addAll(extractRumble(url, server))
                }

                // RUBYVID / VTUBE - Extract using JsUnpacker
                url.contains("rubyvid", ignoreCase = true) || 
                url.contains("vtube", ignoreCase = true) -> {
                    videoList.addAll(extractRubyVid(url, server))
                }

                else -> {
                    android.util.Log.w("Anichin2Extractor", "Unknown server type: $url")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Anichin2Extractor", "Extraction failed: ${e.message}")
            e.printStackTrace()
        }

        // Sort by quality preference
        return sortByQuality(videoList)
    }

    private fun extractDailymotion(url: String, server: String): List<Video> {
        val videos = mutableListOf<Video>()

        try {
            val videoId = url.substringAfterLast("/").substringBefore("?")
            android.util.Log.d("Anichin2Extractor", "Dailymotion ID: $videoId")

            val apiUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
            val response = client.newCall(GET(apiUrl)).execute()

            if (!response.isSuccessful) {
                android.util.Log.e("Anichin2Extractor", "Dailymotion API failed: ${response.code}")
                return emptyList()
            }

            val jsonString = response.body.string()
            val qualities = json.parseToJsonElement(jsonString)
                .jsonObject["qualities"]?.jsonObject

            qualities?.forEach { (quality, sources) ->
                sources.jsonArray.forEach { source ->
                    val videoUrl = source.jsonObject["url"]
                        ?.toString()
                        ?.removeSurrounding("\"")

                    if (videoUrl != null && videoUrl.contains(".m3u8")) {
                        val qualityLabel = "$server - Dailymotion ${quality}p"
                        videos.add(Video(videoUrl, qualityLabel, videoUrl))
                        android.util.Log.d("Anichin2Extractor", "Found: $qualityLabel")
                    }
                }
            }

            android.util.Log.d("Anichin2Extractor", "Dailymotion: ${videos.size} videos")

        } catch (e: Exception) {
            android.util.Log.e("Anichin2Extractor", "Dailymotion extraction error: ${e.message}")
        }

        return videos
    }

    private fun extractRumble(url: String, server: String): List<Video> {
        val videos = mutableListOf<Video>()

        try {
            val response = client.newCall(GET(url)).execute()
            val html = response.body.string()

            android.util.Log.d("Anichin2Extractor", "Rumble HTML length: ${html.length}")

            // Look for JSON with mp4 URLs
            val mp4Regex = Regex(""""ua":\{"mp4":\{(.*?)\}""")
            val match = mp4Regex.find(html)

            if (match != null) {
                val linkRegex = Regex(""""([^"]+)":\{"url":"([^"]+)"""")
                linkRegex.findAll(match.groupValues[1]).forEach { linkMatch ->
                    val quality = linkMatch.groupValues[1]
                    val videoUrl = linkMatch.groupValues[2].replace("\\/", "/")

                    val qualityLabel = "$server - Rumble ${quality}p"
                    videos.add(Video(videoUrl, qualityLabel, videoUrl))
                    android.util.Log.d("Anichin2Extractor", "Found: $qualityLabel")
                }
            } else {
                android.util.Log.w("Anichin2Extractor", "Rumble: No mp4 JSON found")
            }

            android.util.Log.d("Anichin2Extractor", "Rumble: ${videos.size} videos")

        } catch (e: Exception) {
            android.util.Log.e("Anichin2Extractor", "Rumble extraction error: ${e.message}")
        }

        return videos
    }

    private fun extractRubyVid(url: String, server: String): List<Video> {
        val videos = mutableListOf<Video>()

        try {
            val response = client.newCall(GET(url)).execute()
            val html = response.body.string()

            android.util.Log.d("Anichin2Extractor", "RubyVid HTML length: ${html.length}")

            if (JsUnpacker.detect(html)) {
                android.util.Log.d("Anichin2Extractor", "Packed JS detected, unpacking...")
                val unpacked = JsUnpacker.unpack(html)

                // Look for file URL in unpacked code
                val fileRegex = Regex("""file:\s*["']([^"']+)["']""")
                val match = fileRegex.find(unpacked)

                if (match != null) {
                    val videoUrl = match.groupValues[1]
                    val qualityLabel = "$server - RubyVid"
                    videos.add(Video(videoUrl, qualityLabel, videoUrl))
                    android.util.Log.d("Anichin2Extractor", "Found: $qualityLabel")
                } else {
                    android.util.Log.w("Anichin2Extractor", "RubyVid: No file URL in unpacked code")
                }
            } else {
                android.util.Log.w("Anichin2Extractor", "RubyVid: No packed JS found")
            }

            android.util.Log.d("Anichin2Extractor", "RubyVid: ${videos.size} videos")

        } catch (e: Exception) {
            android.util.Log.e("Anichin2Extractor", "RubyVid extraction error: ${e.message}")
        }

        return videos
    }

    /**
     * Sort videos by quality preference
     * Priority: 720p > 480p > 360p > 1080p > others
     */
    private fun sortByQuality(videos: List<Video>): List<Video> {
        return videos.sortedWith(
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
