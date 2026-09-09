package eu.kanade.tachiyomi.lib.lk21extractor

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * LK21 Video Extractor
 *
 * Dispatcher utama — detect provider dari full URL lalu delegate ke extractor.
 *
 * Provider yang di-support:
 * ├── Emturbovid  → emturbovid.com / turbovidhls.com
 * ├── Playcdn     → videonode.de / playcdn.de (P2P — flow token verify.php)
 * ├── Hownetwork  → cloud.hownetwork.xyz / stream.hownetwork.xyz (P2P lama, fallback)
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

                // Videonode / Playcdn — P2P (baru, gantiin Hownetwork)
                url.contains("videonode.de", ignoreCase = true) ||
                url.contains("playcdn.de", ignoreCase = true) ->
                    extractPlaycdn(url, serverName)

                // Hownetwork / P2P (lama — kemungkinan sudah mati, dibiarkan sbg fallback)
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
    // 1b. Videonode / Playcdn Extractor (P2P baru)
    // Flow:
    //   Tahap 1: GET videonode.de/iframe3/p2p/{id} → cari <iframe src=playcdn.de/...>
    //   Tahap 2: GET iframe playcdn.de/video.php?id=...&ok=1 → ambil var data={..,"token":".."}
    //   Tahap 3: POST playcdn.de/verify.php {token, is_ios:false} → JSON {fileUrl: "...m3u8"}
    //   Tahap 4: Extract hash 32-hex dari fileUrl → generate semua quality (0=480p,1=720p,2=1080p,3=4K)
    // CATATAN: token sekali pakai & berlaku singkat, jadi Tahap 1-3 harus berurutan
    // tanpa jeda/cache di antaranya.
    // =========================================================================
    private fun extractPlaycdn(url: String, serverName: String): List<Video> {
        return try {
            val videonodeBase = "https://videonode.de/"
            val playcdnBase = "https://playcdn.de"
            val mobileUa = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

            // ── Tahap 1: Wrapper → cari iframe ke playcdn.de ──
            Log.d(tag, "[Playcdn] Tahap 1 - Wrapper: $url")
            val wrapperHeaders = Headers.Builder()
                .add("Referer", videonodeBase)
                .add("User-Agent", mobileUa)
                .build()

            val wrapperDoc = client.newCall(GET(url, wrapperHeaders)).execute().asJsoup()
            val iframeSrc = wrapperDoc.selectFirst("iframe[src*=playcdn.de]")?.attr("src")

            if (iframeSrc.isNullOrEmpty()) {
                Log.w(tag, "[Playcdn] Iframe playcdn.de tidak ditemukan di wrapper")
                return emptyList()
            }
            Log.d(tag, "[Playcdn] Tahap 1 OK - iframe: $iframeSrc")

            // ── Tahap 2: Buka iframe → ambil token ──
            val iframeHeaders = Headers.Builder()
                .add("Referer", videonodeBase)
                .add("Origin", playcdnBase)
                .add("User-Agent", mobileUa)
                .build()

            val iframeBody = client.newCall(GET(iframeSrc, iframeHeaders)).execute().body.string()

            val token = Regex("""var\s+data\s*=\s*\{[^}]*"token"\s*:\s*"([^"]+)"""")
                .find(iframeBody)?.groupValues?.get(1)

            if (token.isNullOrEmpty()) {
                Log.w(tag, "[Playcdn] Token tidak ditemukan di iframe")
                return emptyList()
            }
            Log.d(tag, "[Playcdn] Tahap 2 OK - token didapat")

            // ── Tahap 3: Tukar token via verify.php ──
            val verifyHeaders = Headers.Builder()
                .add("Referer", iframeSrc)
                .add("Origin", playcdnBase)
                .add("User-Agent", mobileUa)
                .build()

            val verifyPayload = JSONObject().apply {
                put("token", token)
                put("is_ios", false)
            }
            val verifyBody = verifyPayload.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val verifyResponse = client.newCall(
                POST("$playcdnBase/verify.php", verifyHeaders, verifyBody),
            ).execute()
            val verifyJson = JSONObject(verifyResponse.body.string())
            val fileUrl = verifyJson.optString("fileUrl", "").trim()

            if (fileUrl.isEmpty()) {
                Log.w(tag, "[Playcdn] fileUrl kosong dari verify.php (token mungkin sudah expired)")
                return emptyList()
            }
            Log.d(tag, "[Playcdn] Tahap 3 OK - fileUrl: $fileUrl")

            // ── Tahap 4: Extract hash → generate semua quality ──
            val hash = Regex("""[a-f0-9]{32}""").find(fileUrl)?.value
            val videoHeaders = Headers.Builder()
                .add("Referer", "$playcdnBase/")
                .add("Origin", playcdnBase)
                .build()

            if (hash == null) {
                Log.w(tag, "[Playcdn] Hash tidak ditemukan, fallback ke fileUrl asli")
                return listOf(Video(fileUrl, "$serverName - Playcdn", fileUrl, videoHeaders))
            }

            val queryString = fileUrl.substringAfter("?", "").let { if (it.isNotEmpty()) "?$it" else "" }
            val qualityMap = linkedMapOf(3 to "4K", 2 to "1080p", 1 to "720p", 0 to "480p")

            qualityMap.map { (qIndex, qLabel) ->
                val qualityUrl = "https://stream.playcdn.de/playlist/$hash/$qIndex/0.m3u8$queryString"
                Video(qualityUrl, "$serverName - Playcdn $qLabel", qualityUrl, videoHeaders)
            }
        } catch (e: Exception) {
            Log.e(tag, "[Playcdn] Error: ${e.message}")
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
            // (pakai pageText yg sudah dibaca — asJsoup() tanpa param akan re-read body
            // yang sudah closed dan throw exception)
            val iframeSrc = pageResponse.asJsoup(pageText)
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
                pageResponse.asJsoup(pageText)
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
