package eu.kanade.tachiyomi.lib.anichinextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class AnichinExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val videoList = mutableListOf<Video>()

        try {
            // Extract video ID from URL
            val videoId = extractVideoId(url) ?: return emptyList()

            // Get redirected domain
            val domain = getRedirectedDomain(url)

            // Build m3u8 URL
            val m3u8Url = "https://$domain/hls/$videoId.m3u8"

            // Headers with referer
            val headers = Headers.headersOf(
                "Referer", "https://$domain/?id=$videoId&timestamp=${System.currentTimeMillis()}",
                "User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            )

            // Fetch master playlist
            val masterPlaylist = client.newCall(
                GET(m3u8Url, headers),
            ).execute().body.string()

            // Parse HLS variants
            videoList.addAll(parseM3u8(masterPlaylist, m3u8Url, headers, prefix))
        } catch (e: Exception) {
            // Log error if needed
        }

        return videoList
    }

    private fun extractVideoId(url: String): String? {
        // Extract from ?id=v72mz9c
        return url.substringAfter("?id=", "")
            .substringBefore("&")
            .takeIf { it.isNotEmpty() }
    }

    private fun getRedirectedDomain(url: String): String {
        return try {
            val response = client.newCall(
                GET(url, Headers.headersOf("User-Agent", "Mozilla/5.0")),
            ).execute()

            // Get final domain after redirect
            response.request.url.host
        } catch (e: Exception) {
            // Fallback to known domain
            "anichinv2.icu"
        }
    }

    private fun parseM3u8(
        playlist: String,
        baseUrl: String,
        headers: Headers,
        prefix: String,
    ): List<Video> {
        val videoList = mutableListOf<Video>()
        val lines = playlist.lines()

        var quality = "Unknown"
        var bandwidth = 0

        for (i in lines.indices) {
            val line = lines[i].trim()

            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    // Parse quality info
                    val resolution = line.substringAfter("RESOLUTION=", "")
                        .substringBefore(",")
                        .substringAfter("x")

                    bandwidth = line.substringAfter("BANDWIDTH=", "0")
                        .substringBefore(",")
                        .toIntOrNull() ?: 0

                    quality = when {
                        resolution.contains("1080") -> "1080p"
                        resolution.contains("720") -> "720p"
                        resolution.contains("480") -> "480p"
                        resolution.contains("360") -> "360p"
                        else -> "${resolution}p"
                    }
                }

                line.isNotEmpty() && !line.startsWith("#") -> {
                    // This is the playlist URL
                    val videoUrl = if (line.startsWith("http")) {
                        line
                    } else {
                        val base = baseUrl.substringBeforeLast("/")
                        "$base/$line"
                    }

                    val qualityLabel = if (prefix.isNotEmpty()) {
                        "$prefix - $quality"
                    } else {
                        quality
                    }

                    videoList.add(
                        Video(
                            url = videoUrl,
                            quality = qualityLabel,
                            videoUrl = videoUrl,
                            headers = headers,
                        ),
                    )
                }
            }
        }

        // Sort by quality (highest first)
        return videoList.sortedByDescending {
            it.quality.filter { char -> char.isDigit() }.toIntOrNull() ?: 0
        }
    }
}
