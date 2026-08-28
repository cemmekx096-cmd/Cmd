package eu.kanade.tachiyomi.lib.acefileextractor

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * Acefile Video Extractor
 * 
 * Extracts video links from Acefile by:
 * 1. Fetching player page
 * 2. Unpacking P.A.C.K.E.R. encoded JavaScript
 * 3. Extracting service & server URL from unpacked JS
 * 4. Making API request to get final video URL
 */
class AcefileExtractor(private val client: OkHttpClient) {
    
    private val mainUrl = "https://acefile.co"
    private val TAG = "AcefileExtractor"
    
    /**
     * Extract video URLs from Acefile link
     * 
     * @param url Acefile URL (various formats supported)
     * @param headers HTTP headers for request
     * @return List of Video objects
     */
    fun videosFromUrl(url: String, headers: Headers): List<Video> {
        try {
            // Extract file ID from URL
            val id = Regex("""/(?:d|download|player|f|file)/(\w+)""")
                .find(url)?.groupValues?.get(1)
            
            if (id == null) {
                Log.e(TAG, "Failed to extract ID from URL: $url")
                return emptyList()
            }
            
            Log.d(TAG, "Extracting ID: $id")
            
            // Fetch player page
            val playerUrl = "$mainUrl/player/$id"
            Log.d(TAG, "Fetching player: $playerUrl")
            
            val request = okhttp3.Request.Builder()
                .url(playerUrl)
                .headers(headers)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "Request failed with code: ${response.code}")
                return emptyList()
            }
            
            val html = response.body.string()
            
            // Unpack JavaScript
            Log.d(TAG, "Unpacking JavaScript...")
            val unpacker = JsUnpacker(html)
            val script = if (unpacker.detect()) {
                Log.d(TAG, "Packed JS detected, unpacking...")
                unpacker.unpack() ?: html
            } else {
                Log.d(TAG, "No packed JS detected")
                html
            }
            
            // Extract service parameter
            val service = Regex("""service\s*=\s*['"]([^'"]+)""")
                .find(script)?.groupValues?.get(1)
            
            if (service == null) {
                Log.e(TAG, "Failed to extract service from script")
                return emptyList()
            }
            
            Log.d(TAG, "Service: $service")
            
            // Extract server URL template
            val serverUrlTemplate = Regex("""['"](\S+check&id\S+?)['"]""")
                .find(script)?.groupValues?.get(1)
            
            if (serverUrlTemplate == null) {
                Log.e(TAG, "Failed to extract server URL from script")
                return emptyList()
            }
            
            // Replace service placeholder
            val serverUrl = serverUrlTemplate.replace("\"+service+\"", service)
            Log.d(TAG, "Server URL: $serverUrl")
            
            // Fetch video URL from server
            Log.d(TAG, "Fetching video URL from server...")
            val videoRequest = okhttp3.Request.Builder()
                .url(serverUrl)
                .header("Referer", "$mainUrl/")
                .build()
            
            val videoResponse = client.newCall(videoRequest).execute()
            
            if (!videoResponse.isSuccessful) {
                Log.e(TAG, "Video request failed with code: ${videoResponse.code}")
                return emptyList()
            }
            
            // Parse JSON response
            val json = JSONObject(videoResponse.body.string())
            val videoUrl = json.optString("data")
            
            if (videoUrl.isEmpty()) {
                Log.e(TAG, "No video URL in response")
                return emptyList()
            }
            
            Log.d(TAG, "✅ Found video: $videoUrl")
            
            return listOf(
                Video(
                    url = videoUrl,
                    quality = "Acefile",
                    videoUrl = videoUrl,
                    headers = headers
                )
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting video: ${e.message}", e)
            return emptyList()
        }
    }
}
