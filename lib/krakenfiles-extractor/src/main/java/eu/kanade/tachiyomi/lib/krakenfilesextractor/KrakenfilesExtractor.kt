package eu.kanade.tachiyomi.lib.krakenfilesextractor

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * Krakenfiles Video Extractor
 * 
 * Extracts direct video links from Krakenfiles embed pages.
 * Simple extraction - just parse <source> tag from embed player.
 */
class KrakenfilesExtractor(private val client: OkHttpClient) {
    
    private val mainUrl = "https://krakenfiles.com"
    
    /**
     * Extract video URLs from Krakenfiles link
     * 
     * @param url Krakenfiles URL (format: /view/xxx or /embed-video/xxx)
     * @param headers HTTP headers for request
     * @return List of Video objects
     */
    fun videosFromUrl(url: String, headers: Headers): List<Video> {
        try {
            // Extract ID from URL
            val id = Regex("/(?:view|embed-video)/([\\da-zA-Z]+)")
                .find(url)?.groupValues?.get(1)
            
            if (id == null) {
                android.util.Log.e("KrakenfilesExtractor", "Failed to extract ID from URL: $url")
                return emptyList()
            }
            
            android.util.Log.d("KrakenfilesExtractor", "Extracting ID: $id")
            
            // Build embed URL
            val embedUrl = "$mainUrl/embed-video/$id"
            android.util.Log.d("KrakenfilesExtractor", "Fetching: $embedUrl")
            
            // Fetch embed page
            val request = okhttp3.Request.Builder()
                .url(embedUrl)
                .headers(headers)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                android.util.Log.e("KrakenfilesExtractor", "Request failed with code: ${response.code}")
                return emptyList()
            }
            
            // Parse HTML
            val html = response.body.string()
            val document = Jsoup.parse(html)
            
            // Extract video URL from <source> tag
            val videoUrl = document.selectFirst("source")?.attr("src")
            
            if (videoUrl.isNullOrBlank()) {
                android.util.Log.e("KrakenfilesExtractor", "No video URL found in page")
                return emptyList()
            }
            
            android.util.Log.d("KrakenfilesExtractor", "✅ Found video: $videoUrl")
            
            // Return video
            return listOf(
                Video(
                    url = videoUrl,
                    quality = "Krakenfiles",
                    videoUrl = videoUrl,
                    headers = headers
                )
            )
            
        } catch (e: Exception) {
            android.util.Log.e("KrakenfilesExtractor", "Error extracting video: ${e.message}", e)
            return emptyList()
        }
    }
}
