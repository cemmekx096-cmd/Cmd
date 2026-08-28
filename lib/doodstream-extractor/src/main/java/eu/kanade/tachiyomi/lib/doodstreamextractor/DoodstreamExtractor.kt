package eu.kanade.tachiyomi.lib.doodstreamextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

class DoodstreamExtractor(private val client: OkHttpClient) {
    
    companion object {
        private val DOODSTREAM_HEADERS = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("Accept-Encoding", "gzip, deflate, br")
            .add("DNT", "1")
            .add("Connection", "keep-alive")
            .add("Upgrade-Insecure-Requests", "1")
            .add("Sec-Fetch-Dest", "document")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "none")
            .add("Sec-Fetch-User", "?1")
            .add("TE", "trailers")
            .build()
    }
    
    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        android.util.Log.d("DoodstreamExtractor", "=== EXTRACT START ===")
        android.util.Log.d("DoodstreamExtractor", "URL: $url")
        
        return try {
            val videoId = extractVideoId(url)
            if (videoId.isEmpty()) {
                android.util.Log.w("DoodstreamExtractor", "No video ID found")
                return listOf(Video(url, "${prefix}Doodstream (Direct)", url))
            }
            
            android.util.Log.d("DoodstreamExtractor", "Video ID: $videoId")
            
            // 1. Try direct CDN patterns
            val directVideos = tryDirectCdnPatterns(videoId, prefix)
            if (directVideos.isNotEmpty()) return directVideos
            
            // 2. Try embed page
            val embedVideos = tryEmbedPageExtraction(url, videoId, prefix)
            if (embedVideos.isNotEmpty()) return embedVideos
            
            // 3. Fallback
            android.util.Log.w("DoodstreamExtractor", "All methods failed, using fallback")
            listOf(Video(url, "${prefix}Doodstream (Fallback)", url))
            
        } catch (e: Exception) {
            android.util.Log.e("DoodstreamExtractor", "Extraction failed", e)
            listOf(Video(url, "${prefix}Doodstream (Error)", url))
        } finally {
            android.util.Log.d("DoodstreamExtractor", "=== EXTRACT END ===")
        }
    }
    
    private fun extractVideoId(url: String): String {
        val patterns = listOf(
            Regex("""/(d|e|v)/([a-zA-Z0-9]{10,})"""),
            Regex("""\?v=([a-zA-Z0-9]{10,})"""),
            Regex("""&id=([a-zA-Z0-9]{10,})"""),
            Regex("""video/([a-zA-Z0-9]{10,})""")
        )
        
        patterns.forEach { pattern ->
            val match = pattern.find(url)
            if (match != null) {
                val id = match.groupValues.last()
                if (id.length >= 10) return id
            }
        }
        
        // Fallback
        return url.substringAfterLast("/")
            .substringBefore("?")
            .substringBefore("&")
            .takeIf { it.length >= 10 } ?: ""
    }
    
    private fun tryDirectCdnPatterns(videoId: String, prefix: String): List<Video> {
        val testUrls = listOf(
            "https://dsvplay.com/v/$videoId.mp4",
            "https://myvidplay.com/v/$videoId.mp4",
            "https://dsvplay.com/d/$videoId.mp4",
            "https://myvidplay.com/d/$videoId.mp4",
            "https://$videoId.doodcdn.com/$videoId.mp4",
            "https://$videoId.cdndownload.com/$videoId.mp4",
            "https://dsvplay.com/$videoId.mp4",
            "https://myvidplay.com/$videoId.mp4"
        )
        
        android.util.Log.d("DoodstreamExtractor", "Testing ${testUrls.size} direct URLs")
        
        for (testUrl in testUrls) {
            try {
                // Custom HEAD request since network.HEAD doesn't exist
                val request = Request.Builder()
                    .url(testUrl)
                    .headers(DOODSTREAM_HEADERS)
                    .head()
                    .build()
                
                val response = client.newCall(request).execute()
                
                val contentType = response.header("Content-Type", "")?.lowercase(Locale.US) ?: ""
                val contentLength = response.header("Content-Length", "0")?.toLong() ?: 0L
                
                android.util.Log.d("DoodstreamExtractor", "Test $testUrl → Code:${response.code}, Type:$contentType, Size:${contentLength}bytes")
                
                if (response.isSuccessful && 
                    (contentType.contains("video/") || 
                     contentType.contains("application/x-mpegurl") ||
                     contentType.contains("application/vnd.apple.mpegurl")) &&
                    contentLength > 1024) {
                    
                    android.util.Log.d("DoodstreamExtractor", "✓ Found valid URL: $testUrl")
                    return listOf(Video(testUrl, "${prefix}Doodstream CDN", testUrl))
                }
                
                response.close()
            } catch (e: Exception) {
                // Continue
            }
        }
        
        return emptyList()
    }
    
    private fun tryEmbedPageExtraction(url: String, videoId: String, prefix: String): List<Video> {
        val embedUrl = if (url.contains("/d/")) {
            url.replace("/d/", "/e/")
        } else {
            url
        }
        
        android.util.Log.d("DoodstreamExtractor", "Trying embed: $embedUrl")
        
        return try {
            val response = client.newCall(GET(embedUrl, DOODSTREAM_HEADERS)).execute()
            val html = response.body?.string() ?: ""
            
            val videos = mutableListOf<Video>()
            
            // Direct video URLs
            val directPatterns = listOf(
                Regex("""(https?://[^\s"']*\.mp4[^\s"']*)"""),
                Regex("""(https?://[^\s"']*\.m3u8[^\s"']*)"""),
                Regex("""(https?://[^\s"']*\.mkv[^\s"']*)"""),
                Regex("""(https?://[^\s"']*\.webm[^\s"']*)""")
            )
            
            directPatterns.forEach { pattern ->
                pattern.findAll(html).forEach { match ->
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.contains(videoId) || 
                        videoUrl.contains("dood") || 
                        videoUrl.contains("clouddatacdn")) {
                        videos.add(Video(videoUrl, "${prefix}Doodstream Direct", videoUrl))
                    }
                }
            }
            
            // Script patterns
            val scriptPatterns = listOf(
                Regex("""sources\s*:\s*\[\s*\{\s*src\s*:\s*["']([^"']+)["']"""),
                Regex("""file\s*:\s*["']([^"']+)["']"""),
                Regex(""""url"\s*:\s*"([^"]+)"""),
                Regex("""video_url\s*:\s*["']([^"']+)["']"""),
                Regex("""videoSrc\s*:\s*["']([^"']+)["']""")
            )
            
            scriptPatterns.forEach { pattern ->
                pattern.findAll(html).forEach { match ->
                    val videoUrl = match.groupValues[1]
                    videos.add(Video(videoUrl, "${prefix}Doodstream Script", videoUrl))
                }
            }
            
            // Iframe sources
            val iframePattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
            iframePattern.findAll(html).forEach { match ->
                val iframeUrl = match.groupValues[1]
                if (iframeUrl.contains(".mp4") || iframeUrl.contains(".m3u8")) {
                    videos.add(Video(iframeUrl, "${prefix}Doodstream Iframe", iframeUrl))
                }
            }
            
            // Remove duplicates
            videos.distinctBy { it.videoUrl }
            
        } catch (e: Exception) {
            android.util.Log.e("DoodstreamExtractor", "Embed extraction failed", e)
            emptyList()
        }
    }
}
