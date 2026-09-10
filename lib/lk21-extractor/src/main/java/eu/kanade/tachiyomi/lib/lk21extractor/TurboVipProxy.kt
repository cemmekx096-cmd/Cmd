package eu.kanade.tachiyomi.lib.lk21extractor

import fi.iki.elonen.NanoHTTPD
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Local proxy buat TurboVIP.
 *
 * Kenapa perlu ini (bukan cukup OkHttp Interceptor)?
 * Kalau user pakai player eksternal/internal yang fetch video-nya sendiri (mis. mpv,
 * lewat FFmpeg/libavformat internal), request segmen SAMA SEKALI TIDAK lewat OkHttp
 * Kotlin manapun — jadi Interceptor apapun yang nempel di client Aniyomi gak akan pernah
 * kepanggil buat segmen video. Satu-satunya titik yang PASTI dilewati siapapun player-nya
 * adalah socket TCP ke URL yang kita kasih. Makanya kita taruh HTTP server lokal di
 * 127.0.0.1, dan itu yang jadi "URL video" yang diserahkan ke Aniyomi/player.
 *
 * Alur:
 *   Player -> GET http://127.0.0.1:PORT/playlist.m3u8?url=<master_url_asli>
 *          -> proxy fetch master_url_asli (pakai Cookie/Referer asli)
 *          -> setiap baris URL di playlist di-rewrite balik ke proxy
 *   Player -> GET http://127.0.0.1:PORT/segment.ts?url=<segment_url_asli>
 *          -> proxy fetch segment_url_asli (pakai Cookie/Referer asli)
 *          -> kalau ternyata PNG-hidden-TS (trik lh3.googleusercontent.com), di-strip dulu
 *          -> return raw bytes video/mp2t
 */
class TurboVipProxy(
    private val client: OkHttpClient,
    private val upstreamHeaders: Headers,
) : NanoHTTPD("127.0.0.1", 0) {

    private val tag = "TurboVipProxy"

    fun ensureStarted(): Int {
        if (!isProxyRunning()) {
            start(SOCKET_READ_TIMEOUT, false)
            ReportLog.log(tag, "Proxy started on port $listeningPort", LogLevel.INFO)
        }
        return listeningPort
    }

    private fun isProxyRunning(): Boolean {
        return try {
            listeningPort != -1 && isAlive
        } catch (e: Exception) {
            false
        }
    }

    fun buildPlaylistUrl(masterUrl: String): String {
        val port = ensureStarted()
        val encoded = URLEncoder.encode(masterUrl, "UTF-8")
        return "http://127.0.0.1:$port/playlist.m3u8?url=$encoded"
    }

    override fun serve(session: IHTTPSession): Response {
        val tracker = FeatureTracker("$tag-serve")
        tracker.start()

        val targetUrl = session.parameters["url"]?.firstOrNull()
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?: run {
                tracker.error("Missing url param di request ${session.uri}")
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "text/plain", "Missing url param",
                )
            }

        ReportLog.log(tag, "Request masuk: ${session.uri} -> $targetUrl", LogLevel.DEBUG)

        return try {
            val result = when {
                session.uri.endsWith(".m3u8") -> servePlaylist(targetUrl)
                else -> serveSegment(targetUrl)
            }
            tracker.success("Selesai serve ${session.uri}")
            result
        } catch (e: Exception) {
            tracker.error("serve() gagal untuk $targetUrl: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Proxy error: ${e.message}")
        }
    }

    // ── Playlist: fetch asli, rewrite tiap baris URL balik ke proxy ────────────
    private fun servePlaylist(url: String): Response {
        val perf = PerformanceTracker("ServePlaylist")
        perf.start()

        val body = fetchText(url)
        perf.end()

        if (body == null) {
            ReportLog.log(tag, "Gagal fetch playlist: $url", LogLevel.ERROR)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Fetch playlist failed")
        }

        val port = listeningPort
        var rewriteCount = 0
        val rewritten = body.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                line
            } else {
                val absoluteUrl = resolveRelative(url, trimmed)
                val encoded = URLEncoder.encode(absoluteUrl, "UTF-8")
                val ext = if (absoluteUrl.contains(".m3u8")) "playlist.m3u8" else "segment.ts"
                rewriteCount++
                "http://127.0.0.1:$port/$ext?url=$encoded"
            }
        }

        ReportLog.log(tag, "Playlist di-rewrite: $url ($rewriteCount baris URL)", LogLevel.DEBUG)
        return newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", rewritten)
    }

    // ── Segment: fetch asli, strip PNG-hidden-TS kalau perlu, return raw bytes ─
    private fun serveSegment(url: String): Response {
        val perf = PerformanceTracker("ServeSegment")
        perf.start()

        val bytes = fetchBytes(url)
        perf.end()

        if (bytes == null) {
            ReportLog.log(tag, "Gagal fetch segment: $url", LogLevel.ERROR)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Fetch segment failed")
        }

        val isPng = isFakePng(bytes)
        ReportLog.log(tag, "Segment $url: ${bytes.size} bytes, isFakePng=$isPng", LogLevel.DEBUG)

        val finalBytes = if (isPng) {
            val extracted = extractHiddenTs(bytes)
            if (extracted == null) {
                ReportLog.log(tag, "PNG terdeteksi tapi GAGAL nemu TS asli di: $url", LogLevel.WARN)
                bytes
            } else {
                ReportLog.log(tag, "Berhasil strip PNG->TS: ${bytes.size} -> ${extracted.size} bytes", LogLevel.DEBUG)
                extracted
            }
        } else {
            bytes
        }

        return newFixedLengthResponse(
            Response.Status.OK,
            "video/mp2t",
            ByteArrayInputStream(finalBytes),
            finalBytes.size.toLong(),
        )
    }

    private fun fetchText(url: String): String? {
        return try {
            val request = Request.Builder().url(url).headers(upstreamHeaders).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    ReportLog.log(tag, "fetchText status ${resp.code} untuk $url", LogLevel.WARN)
                    return null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            ReportLog.log(tag, "fetchText error untuk $url: ${e.message}", LogLevel.ERROR)
            null
        }
    }

    private fun fetchBytes(url: String): ByteArray? {
        return try {
            val request = Request.Builder().url(url).headers(upstreamHeaders).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    ReportLog.log(tag, "fetchBytes status ${resp.code} untuk $url", LogLevel.WARN)
                    return null
                }
                resp.body?.bytes()
            }
        } catch (e: Exception) {
            ReportLog.log(tag, "fetchBytes error untuk $url: ${e.message}", LogLevel.ERROR)
            null
        }
    }

    private fun resolveRelative(baseUrl: String, maybeRelative: String): String {
        if (maybeRelative.startsWith("http://") || maybeRelative.startsWith("https://")) {
            return maybeRelative
        }
        val base = baseUrl.substringBeforeLast("/")
        return "$base/$maybeRelative"
    }

    // ── PNG-hidden-TS detection & extraction (sama seperti versi Interceptor) ──
    private fun isFakePng(data: ByteArray): Boolean {
        return data.size >= 8 && data.copyOfRange(0, 8).contentEquals(PNG_MAGIC)
    }

    private fun extractHiddenTs(data: ByteArray): ByteArray? {
        val iendIdx = indexOf(data, IEND_MARKER)
        if (iendIdx == -1) return null
        val iendEnd = iendIdx + 4 + 4 // "IEND" (4 byte) + CRC (4 byte)

        var offset = iendEnd
        while (offset < data.size && data[offset] == 0xFF.toByte()) offset++

        val scanLimit = minOf(4096, data.size - offset)
        for (extra in 0 until scanLimit) {
            val testOffset = offset + extra
            if (looksLikeTsSync(data, testOffset)) {
                return data.copyOfRange(testOffset, data.size)
            }
        }
        return null
    }

    private fun looksLikeTsSync(data: ByteArray, offset: Int, packetSize: Int = 188, checkPackets: Int = 10): Boolean {
        if (offset >= data.size) return false
        var countOk = 0
        for (i in 0 until checkPackets) {
            val pos = offset + i * packetSize
            if (pos >= data.size) break
            if (data[pos] == 0x47.toByte()) countOk++ else return false
        }
        return countOk >= 3
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }

    companion object {
        private const val SOCKET_READ_TIMEOUT = 30_000

        private val PNG_MAGIC = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        private val IEND_MARKER = "IEND".toByteArray()

        // Singleton — 1 proxy per app process, reuse terus biar gak buka port berkali-kali
        @Volatile
        private var instance: TurboVipProxy? = null

        fun getInstance(client: OkHttpClient, headers: Headers): TurboVipProxy {
            return instance ?: synchronized(this) {
                instance ?: TurboVipProxy(client, headers).also { instance = it }
            }
        }
    }
}
