package eu.kanade.tachiyomi.lib.lk21extractor

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * LK21 Video Extractor
 *
 * Dispatcher utama — detect provider dari full URL lalu delegate ke extractor.
 *
 * Provider yang di-support:
 * ├── Emturbovid  → emturbovid.com / turbovidhls.com
 * ├── Hownetwork  → cloud.hownetwork.xyz / stream.hownetwork.xyz (P2P)
 * ├── Filesim     → f16px.com / furher.in / co4nxtrl.com (Cast)
 * └── Hydrax      → abysscdn.com (TODO)
 */
class Lk21Extractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val tag = "LK21-Extractor"

    // ── Dispatcher ────────────────────────────────────────────────────────────
    fun videosFromUrl(url: String, serverName: String = "Player"): List<Video> {
        if (url.isBlank()) return emptyList()

        Log.d(tag, "Dispatching: $serverName → $url")

        return try {
            when {
                // Emturbovid / TurboVIP
                url.contains("emturbovid.com", ignoreCase = true) ||
                url.contains("turbovidhls.com", ignoreCase = true) ->
                    extractEmturbovid(url, serverName)

                // Hownetwork / P2P
                url.contains("hownetwork.xyz", ignoreCase = true) ->
                    extractHownetwork(url, serverName)

                // Filesim / Cast
                url.contains("f16px.com", ignoreCase = true) ||
                url.contains("furher.in", ignoreCase = true) ||
                url.contains("co4nxtrl.com", ignoreCase = true) ||
                url.contains("files.im", ignoreCase = true) ->
                    extractFilesim(url, serverName)

                // Hydrax / Abyss — TODO
                url.contains("abysscdn.com", ignoreCase = true) ||
                url.contains("abyss.to", ignoreCase = true) -> {
                    Log.w(tag, "Hydrax/Abyss not yet implemented: $url")
                    emptyList()
                }

                else -> {
                    Log.w(tag, "Unknown provider: $url")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Dispatch error for $url: ${e.message}")
            emptyList()
        }
    }

    // =========================================================================
    // 1. Emturbovid Extractor
    // Port dari: EmturbovidExtractor.kt (CloudStream)
    // Flow: GET url → script[var urlPlay = '...'] → m3u8 URL
    // =========================================================================
    private fun extractEmturbovid(url: String, serverName: String): List<Video> {
        return try {
            Log.d(tag, "[Emturbovid] Fetching: $url")

            val emturboBase = if (url.contains("emturbovid")) "https://emturbovid.com" else "https://turbovidhls.com"
            val reqHeaders = Headers.Builder()
                .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .add("Accept-Language", "en-US,en;q=0.5")
                .add("Referer", "$emturboBase/")
                .add("Connection", "keep-alive")
                .add("Upgrade-Insecure-Requests", "1")
                .add("Sec-Fetch-Dest", "iframe")
                .add("Sec-Fetch-Mode", "navigate")
                .add("Sec-Fetch-Site", "cross-site")
                .build()

            val document = client.newCall(GET(url, reqHeaders)).execute().asJsoup()

            val playerScript = document
                .select("script")
                .firstOrNull { it.data().contains("var urlPlay") }
                ?.data()

            if (playerScript.isNullOrEmpty()) {
                Log.w(tag, "[Emturbovid] Script not found")
                return emptyList()
            }

            val m3u8Url = playerScript
                .substringAfter("var urlPlay = '")
                .substringBefore("'")
                .trim()

            if (m3u8Url.isEmpty() || !m3u8Url.startsWith("http")) {
                Log.w(tag, "[Emturbovid] Invalid m3u8: $m3u8Url")
                return emptyList()
            }

            Log.d(tag, "[Emturbovid] m3u8: $m3u8Url")

            // Ikut CloudStream: langsung return master m3u8 + referer
            // JANGAN parse/split playlist — biarkan player Aniyomi yang handle
            // PENTING: Referer harus pakai CDN domain dari m3u8 URL, bukan emturbovid.com!
            val cdnDomain = when {
                m3u8Url.contains("turboviplay.com") -> "https://turboviplay.com/"
                m3u8Url.contains("turbovidhls.com") -> "https://turbovidhls.com/"
                else -> "https://emturbovid.com/"
            }
            
            val videoHeaders = Headers.Builder()
                .add("Referer", cdnDomain)
                .add("Origin", cdnDomain.trimEnd('/'))
                .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .add("Accept", "*/*")
                .add("Accept-Language", "en-US,en;q=0.5")
                .add("Connection", "keep-alive")
                .add("Sec-Fetch-Dest", "empty")
                .add("Sec-Fetch-Mode", "cors")
                .add("Sec-Fetch-Site", "cross-site")
                .build()

            Log.d(tag, "[Emturbovid] Video headers Referer: $cdnDomain")
            listOf(Video(m3u8Url, "$serverName - Emturbovid", m3u8Url, videoHeaders))
        } catch (e: Exception) {
            Log.e(tag, "[Emturbovid] Error: ${e.message}")
            emptyList()
        }
    }

    // =========================================================================
    // 2. Hownetwork Extractor (P2P)
    // Port dari: Extractors.kt CloudStream LK21
    // Flow: POST /api2.php?id={id} → JSON {file: url} → video URL
    // =========================================================================
    private fun extractHownetwork(url: String, serverName: String): List<Video> {
        return try {
            val id = url.substringAfter("id=").substringBefore("&").trim()
            if (id.isEmpty()) {
                Log.w(tag, "[Hownetwork] No ID in URL: $url")
                return emptyList()
            }

            val baseUrl = when {
                url.contains("cloud.hownetwork") -> "https://cloud.hownetwork.xyz"
                url.contains("stream.hownetwork") -> "https://stream.hownetwork.xyz"
                else -> "https://cloud.hownetwork.xyz"
            }

            val apiUrl = "$baseUrl/api2.php?id=$id"
            Log.d(tag, "[Hownetwork] POST: $apiUrl")

            val reqHeaders = headers.newBuilder()
                .set("Referer", url)
                .set("X-Requested-With", "XMLHttpRequest")
                .set("Accept", "*/*")
                .build()

            val formBody = FormBody.Builder()
                .add("r", "")
                .add("d", baseUrl)
                .build()

            val response = client.newCall(POST(apiUrl, reqHeaders, formBody)).execute()
            val json = JSONObject(response.body.string())
            val fileUrl = json.optString("file", "").trim()

            if (fileUrl.isEmpty()) {
                Log.w(tag, "[Hownetwork] Empty file URL")
                return emptyList()
            }

            Log.d(tag, "[Hownetwork] File: $fileUrl")

            val videoHeaders = Headers.Builder()
                .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0")
                .add("Referer", baseUrl)
                .add("Accept", "*/*")
                .add("Pragma", "no-cache")
                .add("Cache-Control", "no-cache")
                .build()

            if (fileUrl.contains(".m3u8")) {
                parseM3u8(fileUrl, url, serverName, "P2P", videoHeaders)
            } else {
                listOf(Video(fileUrl, "$serverName - P2P", fileUrl, videoHeaders))
            }
        } catch (e: Exception) {
            Log.e(tag, "[Hownetwork] Error: ${e.message}")
            emptyList()
        }
    }

    // =========================================================================
    // 3. Filesim Extractor (f16px / Cast)
    // Port dari: Filesim.kt (CloudStream)
    // Flow: GET /e/{id} → JS unpack → regex file:"*.m3u8"
    // =========================================================================
    private fun extractFilesim(url: String, serverName: String): List<Video> {
        return try {
            // Normalize ke /e/ format
            val embedUrl = url
                .replace("/download/", "/e/")
                .replace("/f/", "/e/")

            Log.d(tag, "[Filesim] Fetching: $embedUrl")

            val filesimHeaders = Headers.Builder()
                .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .add("Accept-Language", "en-US,en;q=0.5")
                .add("Referer", "https://playeriframe.sbs/")
                .add("Connection", "keep-alive")
                .add("Upgrade-Insecure-Requests", "1")
                .add("Sec-Fetch-Dest", "iframe")
                .add("Sec-Fetch-Mode", "navigate")
                .add("Sec-Fetch-Site", "cross-site")
                .build()

            var pageResponse = client.newCall(GET(embedUrl, filesimHeaders)).execute()
            var pageText = pageResponse.body.string()

            // Follow iframe kalau ada
            val iframeSrc = pageResponse.asJsoup()
                .selectFirst("iframe[src]")?.attr("src")

            if (iframeSrc != null) {
                Log.d(tag, "[Filesim] Following iframe: $iframeSrc")
                val iframeHeaders = headers.newBuilder()
                    .set("Referer", embedUrl)
                    .set("Accept-Language", "en-US,en;q=0.5")
                    .set("Sec-Fetch-Dest", "iframe")
                    .build()
                pageResponse = client.newCall(GET(iframeSrc, iframeHeaders)).execute()
                pageText = pageResponse.body.string()
            }

            // JS unpack
            val scriptData = if (pageText.contains("eval(function(p,a,c,k,e")) {
                Log.d(tag, "[Filesim] JS unpacking...")
                JsUnpacker.unpackAndCombine(pageText) ?: pageText
            } else {
                pageResponse.asJsoup()
                    .select("script")
                    .firstOrNull {
                        it.data().contains("sources:") ||
                        it.data().contains("\"file\"") ||
                        it.data().contains("'file'")
                    }?.data() ?: pageText
            }

            // Regex m3u8
            val m3u8Url =
                Regex("""file:\s*["'](https?://[^"']*\.m3u8[^"']*)["']""").find(scriptData)?.groupValues?.get(1)
                ?: Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']""").find(scriptData)?.groupValues?.get(1)

            if (m3u8Url.isNullOrEmpty()) {
                Log.w(tag, "[Filesim] No m3u8 found")
                return emptyList()
            }

            Log.d(tag, "[Filesim] m3u8: $m3u8Url")
            parseM3u8(m3u8Url, embedUrl, serverName, "Cast")
        } catch (e: Exception) {
            Log.e(tag, "[Filesim] Error: ${e.message}")
            emptyList()
        }
    }

    // =========================================================================
    // Helper: Parse M3U8 playlist → multi-quality videos
    // =========================================================================
    private fun parseM3u8(
        m3u8Url: String,
        referer: String,
        serverName: String,
        providerName: String,
        videoHeaders: Headers? = null,
    ): List<Video> {
        return try {
            val reqHeaders = headers.newBuilder()
                .set("Referer", referer)
                .build()

            val playlistBody = client.newCall(GET(m3u8Url, reqHeaders)).execute().body.string()
            val finalHeaders = videoHeaders ?: reqHeaders

            if (playlistBody.contains("#EXT-X-STREAM-INF")) {
                val videos = mutableListOf<Video>()
                val baseUrl = m3u8Url.substringBeforeLast("/")

                playlistBody.lines().windowed(2).forEach { lines ->
                    if (lines[0].contains("#EXT-X-STREAM-INF")) {
                        val quality = Regex("RESOLUTION=\\d+x(\\d+)")
                            .find(lines[0])?.groupValues?.get(1)?.let { "${it}p" }
                            ?: Regex("BANDWIDTH=(\\d+)").find(lines[0])?.groupValues?.get(1)
                                ?.toLongOrNull()?.let { "${it / 1000}kbps" }
                            ?: "Unknown"

                        val segmentUrl = when {
                            lines[1].trim().startsWith("http") -> lines[1].trim()
                            else -> "$baseUrl/${lines[1].trim()}"
                        }

                        videos.add(Video(
                            segmentUrl,
                            "$serverName - $providerName $quality",
                            segmentUrl,
                            finalHeaders,
                        ))
                        Log.d(tag, "[$providerName] Quality: $quality")
                    }
                }
                videos
            } else {
                listOf(Video(m3u8Url, "$serverName - $providerName", m3u8Url, finalHeaders))
            }
        } catch (e: Exception) {
            Log.e(tag, "[$providerName] M3U8 error: ${e.message}")
            listOf(Video(m3u8Url, "$serverName - $providerName", m3u8Url))
        }
    }
}

// JsUnpacker sudah ada di JsunPacker.kt — tidak perlu redeclare di sini


// =========================================================================
// JS Unpacker — unpack eval(function(p,a,c,k,e,...))
// =========================================================================
object JsUnpacker {

    fun unpackAndCombine(html: String): String? {
        return try {
            val packed = Regex("""eval\(function\(p,a,c,k,e[^)]*\)[^)]*\)""")
                .findAll(html)
                .mapNotNull { unpack(it.value) }
                .joinToString("\n")
            packed.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    fun unpack(packed: String): String? {
        return try {
            val p = Regex("""'(.*?)'""").find(packed)?.groupValues?.get(1) ?: return null
            val a = Regex(""",(\d+),""").find(packed)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val c = Regex(""",\d+,(\d+),""").find(packed)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val kStr = Regex("""'([^']*)'\.split\('""").find(packed)?.groupValues?.get(1) ?: return null
            val k = kStr.split("|")

            var result = p
            for (i in c - 1 downTo 0) {
                if (k.getOrNull(i)?.isNotEmpty() == true) {
                    result = result.replace(Regex("\\b${toBase(i, a)}\\b"), k[i])
                }
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    private fun toBase(num: Int, base: Int): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        return if (num < base) chars[num].toString()
        else toBase(num / base, base) + chars[num % base]
    }
}
