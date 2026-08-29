package eu.kanade.tachiyomi.lib.gofileextractor

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * Gofile Video Extractor
 * 
 * Extracts video links from Gofile by:
 * 1. Creating guest account to get account token
 * 2. Extracting website token from JavaScript
 * 3. Using both tokens to fetch content via API
 * 4. Parsing video files from response
 */
class GofileExtractor(private val client: OkHttpClient) {
    
    private val mainUrl = "https://gofile.io"
    private val mainApi = "https://api.gofile.io"
    private val TAG = "GofileExtractor"
    
    /**
     * Extract video URLs from Gofile link
     * 
     * @param url Gofile URL (format: /d/xxx or /?c=xxx)
     * @param headers HTTP headers for request
     * @return List of Video objects with different qualities
     */
    fun videosFromUrl(url: String, headers: Headers): List<Video> {
        try {
            // Extract content ID
            val id = Regex("/(?:\\?c=|d/)([\\da-zA-Z-]+)")
                .find(url)?.groupValues?.get(1)
            
            if (id == null) {
                Log.e(TAG, "Failed to extract ID from URL: $url")
                return emptyList()
            }
            
            Log.d(TAG, "Extracting content ID: $id")
            
            // Get account token
            Log.d(TAG, "Getting account token...")
            val accountToken = getAccountToken()
            if (accountToken == null) {
                Log.e(TAG, "Failed to get account token")
                return emptyList()
            }
            Log.d(TAG, "Account token: ${accountToken.take(10)}...")
            
            // Get website token
            Log.d(TAG, "Getting website token...")
            val websiteToken = getWebsiteToken()
            if (websiteToken == null) {
                Log.e(TAG, "Failed to get website token")
                return emptyList()
            }
            Log.d(TAG, "Website token: ${websiteToken.take(10)}...")
            
            // Get content
            Log.d(TAG, "Fetching content...")
            val videos = getContent(id, accountToken, websiteToken, headers)
            
            Log.d(TAG, "✅ Extracted ${videos.size} videos")
            return videos
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting video: ${e.message}", e)
            return emptyList()
        }
    }
    
    /**
     * Create guest account and get token
     */
    private fun getAccountToken(): String? {
        return try {
            val request = okhttp3.Request.Builder()
                .url("$mainApi/createAccount")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "Account creation failed: ${response.code}")
                return null
            }
            
            val json = JSONObject(response.body.string())
            val data = json.optJSONObject("data")
            val token = data?.optString("token")
            
            token
        } catch (e: Exception) {
            Log.e(TAG, "Error getting account token: ${e.message}", e)
            null
        }
    }
    
    /**
     * Extract website token from JavaScript file
     */
    private fun getWebsiteToken(): String? {
        return try {
            val request = okhttp3.Request.Builder()
                .url("$mainUrl/dist/js/alljs.js")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch JS file: ${response.code}")
                return null
            }
            
            val js = response.body.string()
            
            // Extract website token from JS
            val token = Regex("""fetchData\.wt\s*=\s*"([^"]+)""")
                .find(js)?.groupValues?.get(1)
            
            token
        } catch (e: Exception) {
            Log.e(TAG, "Error getting website token: ${e.message}", e)
            null
        }
    }
    
    /**
     * Fetch content using tokens
     */
    private fun getContent(
        contentId: String,
        accountToken: String,
        websiteToken: String,
        headers: Headers
    ): List<Video> {
        return try {
            val contentUrl = "$mainApi/getContent?contentId=$contentId&token=$accountToken&wt=$websiteToken"
            
            val request = okhttp3.Request.Builder()
                .url(contentUrl)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "Content request failed: ${response.code}")
                return emptyList()
            }
            
            val json = JSONObject(response.body.string())
            val data = json.optJSONObject("data")
            val contents = data?.optJSONObject("contents")
            
            if (contents == null) {
                Log.e(TAG, "No contents in response")
                return emptyList()
            }
            
            // Parse video files
            val videos = mutableListOf<Video>()
            
            contents.keys().forEach { key ->
                try {
                    val file = contents.getJSONObject(key)
                    val link = file.optString("link")
                    val name = file.optString("name")
                    
                    if (link.isNotEmpty()) {
                        val quality = extractQuality(name)
                        
                        videos.add(
                            Video(
                                url = link,
                                quality = "Gofile - $quality",
                                videoUrl = link,
                                headers = headers.newBuilder()
                                    .add("Cookie", "accountToken=$accountToken")
                                    .build()
                            )
                        )
                        
                        Log.d(TAG, "Found: $name ($quality)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing file: ${e.message}")
                }
            }
            
            videos
        } catch (e: Exception) {
            Log.e(TAG, "Error getting content: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Extract quality from filename
     */
    private fun extractQuality(filename: String): String {
        return Regex("""(\d{3,4})[pP]""")
            .find(filename)?.groupValues?.get(1)
            ?.let { "${it}p" }
            ?: "Unknown"
    }
}
