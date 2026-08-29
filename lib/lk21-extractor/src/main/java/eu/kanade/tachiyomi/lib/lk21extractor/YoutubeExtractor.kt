package eu.kanade.tachiyomi.lib.lk21extractor

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient

/**
 * YouTube Video Extractor untuk Trailer
 * Simplified untuk LK21Movies extension
 */
class YoutubeExtractor(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "YoutubeExtractor"
    }

    /**
     * Extract video dari YouTube URL
     * Note: YouTube extraction sangat limited karena proteksi mereka
     * Ini hanya untuk basic trailer preview
     */
    fun videosFromUrl(url: String): List<Video> {
        if (!url.contains("youtube.com") && !url.contains("youtu.be")) {
            Log.w(TAG, "Not a YouTube URL: $url")
            return emptyList()
        }

        return try {
            Log.d(TAG, "Extracting YouTube: $url")

            val response = client.newCall(GET(url)).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} for YouTube")
                return emptyList()
            }

            val html = response.body?.string() ?: return emptyList()
            val videoList = mutableListOf<Video>()

            // Extract video ID dari URL
            val videoId = extractVideoId(url)
            if (videoId == null) {
                Log.w(TAG, "Could not extract video ID from: $url")
                return emptyList()
            }

            // Method 1: googlevideo.com stream URLs
            val urlPattern = """https?://[^\s"'<>]+googlevideo\.com/[^\s"'<>]+""".toRegex()
            urlPattern.findAll(html).forEach { match ->
                var streamUrl = match.value
                    .replace("\\u0026", "&")
                    .replace("\\/", "/")
                    .replace("\\\\u0026", "&")

                // Filter valid URLs
                if (streamUrl.contains("&") && !videoList.any { it.url == streamUrl }) {
                    videoList.add(
                        Video(
                            url = streamUrl,
                            quality = "YouTube Trailer",
                            videoUrl = streamUrl
                        )
                    )
                }
            }

            // Method 2: Embed fallback
            if (videoList.isEmpty()) {
                val embedUrl = "https://www.youtube.com/embed/$videoId"
                videoList.add(
                    Video(
                        url = embedUrl,
                        quality = "YouTube Embed",
                        videoUrl = embedUrl
                    )
                )
            }

            Log.d(TAG, "Extracted ${videoList.size} YouTube videos")
            videoList.distinctBy { it.url }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting YouTube video", e)
            // Return fallback embed URL
            val videoId = extractVideoId(url)
            if (videoId != null) {
                listOf(
                    Video(
                        url = "https://www.youtube.com/embed/$videoId",
                        quality = "YouTube Embed (Fallback)",
                        videoUrl = "https://www.youtube.com/embed/$videoId"
                    )
                )
            } else {
                emptyList()
            }
        }
    }

    /**
     * Extract video ID dari berbagai format YouTube URL
     * Supports:
     * - https://www.youtube.com/watch?v=VIDEO_ID
     * - https://youtu.be/VIDEO_ID
     * - https://www.youtube.com/embed/VIDEO_ID
     */
    private fun extractVideoId(url: String): String? {
        return when {
            // youtube.com/watch?v=...
            url.contains("youtube.com/watch") -> {
                url.substringAfter("v=").substringBefore("&")
            }
            // youtu.be/...
            url.contains("youtu.be/") -> {
                url.substringAfter("youtu.be/").substringBefore("?")
            }
            // youtube.com/embed/...
            url.contains("youtube.com/embed/") -> {
                url.substringAfter("embed/").substringBefore("?")
            }
            else -> null
        }?.takeIf { it.length == 11 } // YouTube video IDs are 11 chars
    }
}
