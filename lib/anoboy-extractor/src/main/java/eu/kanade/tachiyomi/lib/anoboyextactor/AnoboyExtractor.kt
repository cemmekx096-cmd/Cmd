package eu.kanade.tachiyomi.lib.anoboyextractor

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class AnoboyExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, prefix: String = "Anoboy"): List<Video> {
        android.util.Log.d("AnoboyExtractor", "Extracting from: $url")

        val videoList = mutableListOf<Video>()
        val headers = Headers.Builder()
            .add("Referer", "https://anoboy.icu/")
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        return try {
            val document = client.newCall(GET(url, headers)).execute().asJsoup()

            // Try multiple selectors for different Anoboy versions
            val options = document.select("select#select-video option, select[name='server'] option")

            android.util.Log.d("AnoboyExtractor", "Found ${options.size} server options")

            options.forEachIndexed { index, element ->
                val label = element.text().trim()
                val base64Value = element.attr("value")
                
                android.util.Log.d("AnoboyExtractor", "[$index] Server: $label")

                if (base64Value.isNotEmpty()) {
                    try {
                        // Decode base64
                        val decoded = String(Base64.decode(base64Value, Base64.DEFAULT))

                        // Extract iframe URL
                        val iframeRegex = Regex("""src=["']([^"']+)["']""")
                        val match = iframeRegex.find(decoded)

                        if (match != null) {
                            val iframeUrl = match.groupValues[1]
                            val cleanUrl = when {
                                iframeUrl.startsWith("//") -> "https:$iframeUrl"
                                iframeUrl.startsWith("http") -> iframeUrl
                                else -> iframeUrl
                            }

                            val videoLabel = if (prefix.isNotEmpty()) "$prefix - $label" else label
                            videoList.add(Video(cleanUrl, videoLabel, cleanUrl, headers = headers))

                            android.util.Log.d("AnoboyExtractor", "[$index] Extracted: $cleanUrl")
                        } else {
                            android.util.Log.w("AnoboyExtractor", "[$index] No iframe found in decoded HTML")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("AnoboyExtractor", "[$index] Decode error: ${e.message}")
                    }
                }
            }

            android.util.Log.d("AnoboyExtractor", "Total videos: ${videoList.size}")
            videoList

        } catch (e: Exception) {
            android.util.Log.e("AnoboyExtractor", "Extraction failed: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }
}
