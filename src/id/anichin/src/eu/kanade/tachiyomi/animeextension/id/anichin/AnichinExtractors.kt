package eu.kanade.tachiyomi.animeextension.id.anichin

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

// ============================================================================
// Util bersama
// ============================================================================

private fun OkHttpClient.getText(url: String, headers: Headers): String? =
    runCatching { newCall(GET(url, headers)).execute().use { it.body?.string() } }.getOrNull()

private fun String.origin(): String =
    runCatching { URI(this).let { "${it.scheme}://${it.host}" } }.getOrDefault(this)

/**
 * Expand master playlist HLS jadi daftar Video per resolusi.
 * Kalau bukan master (atau gagal di-fetch), kembalikan URL itu sebagai satu Video.
 */
internal fun OkHttpClient.expandHls(masterUrl: String, headers: Headers, prefix: String): List<Video> {
    val body = getText(masterUrl, headers)
    if (body == null || !body.contains("#EXT-X-STREAM-INF")) {
        return listOf(Video(masterUrl, prefix, masterUrl, headers))
    }
    val lines = body.lines()
    val out = mutableListOf<Pair<Int, Video>>()
    lines.forEachIndexed { i, line ->
        if (!line.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
        val height = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim()
            ?: return@forEachIndexed
        val abs = runCatching { URI(masterUrl).resolve(uri).toString() }.getOrDefault(uri)
        val quality = if (height > 0) "$prefix - ${height}p" else prefix
        out += height to Video(abs, quality, abs, headers)
    }
    return if (out.isEmpty()) {
        listOf(Video(masterUrl, prefix, masterUrl, headers))
    } else {
        out.sortedByDescending { it.first }.map { it.second }
    }
}

// ============================================================================
// Dailymotion (+ geo.dailymotion.com)
// ============================================================================

class DailymotionExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val idRegex = Regex("^[kx][a-zA-Z0-9]+$")

    fun videosFromUrl(url: String, referer: String, prefix: String = "Dailymotion"): List<Video> {
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val id = listOf(
            Regex("""(?i)[?&]video=([A-Za-z0-9]+)"""),
            Regex("""(?i)dailymotion\.com/(?:embed/)?video/([A-Za-z0-9]+)"""),
            Regex("""(?i)dai\.ly/([A-Za-z0-9]+)"""),
        ).firstNotNullOfOrNull { it.find(decoded)?.groupValues?.getOrNull(1) }
            ?.takeIf { idRegex.matches(it) }
            ?: return emptyList()

        if (decoded.contains("geo.dailymotion.com", true)) {
            val geo = fromGeo(id, url, referer, prefix)
            if (geo.isNotEmpty()) return geo
        }
        return fromMetadata(id, url, prefix)
    }

    private fun jsonHeaders(referer: String) = headers.newBuilder()
        .set("Referer", referer)
        .set("Accept", "application/json,text/plain,*/*")
        .build()

    private fun fromGeo(id: String, embedUrl: String, referer: String, prefix: String): List<Video> {
        val embedder = URLEncoder.encode(referer, "UTF-8")
        val text = client.getText(
            "https://geo.dailymotion.com/video/$id.json?legacy=true&embedder=$embedder",
            jsonHeaders(embedUrl),
        ) ?: return emptyList()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()

        val streams = qualityUrls(json)
        if (streams.isNotEmpty()) {
            return streams.flatMap { client.expandHls(it, headers.newBuilder().set("Referer", embedUrl).build(), prefix) }
        }
        val canonical = json.optString("id").takeIf { idRegex.matches(it) } ?: return emptyList()
        return fromMetadata(canonical, embedUrl, prefix)
    }

    private fun fromMetadata(id: String, referer: String, prefix: String): List<Video> {
        val text = client.getText(
            "https://www.dailymotion.com/player/metadata/video/$id",
            jsonHeaders(referer),
        ) ?: return emptyList()
        val json = runCatching { JSONObject(text) }.getOrNull()
        val urls = if (json != null) {
            qualityUrls(json)
        } else {
            Regex(""""url"\s*:\s*"([^"]+)"""").findAll(text)
                .map { it.groupValues[1].replace("\\/", "/") }
                .filter { it.contains(".m3u8", true) }.distinct().toList()
        }
        val mediaHeaders = headers.newBuilder().set("Referer", referer).build()
        return urls.flatMap { client.expandHls(it, mediaHeaders, prefix) }
    }

    private fun qualityUrls(json: JSONObject): List<String> {
        val urls = linkedSetOf<String>()
        val qualities = json.optJSONObject("qualities") ?: return emptyList()
        qualities.keys().forEach { key ->
            val arr = qualities.optJSONArray(key) ?: return@forEach
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val type = item.optString("type").lowercase()
                val url = item.optString("url").trim().replace("\\/", "/")
                if (url.isNotBlank() && (type.contains("mpegurl") || url.contains(".m3u8", true))) urls.add(url)
            }
        }
        return urls.toList()
    }
}

// ============================================================================
// OK.ru
// ============================================================================

class OkRuExtractor(private val client: OkHttpClient, private val headers: Headers) {

    fun videosFromUrl(url: String, referer: String, prefix: String = "OK.ru"): List<Video> {
        val embed = url
            .replace("odnoklassniki.ru", "ok.ru")
            .replace("http://ok.ru", "https://ok.ru")
            .replace("/video/", "/videoembed/")
        val page = client.getText(embed, headers.newBuilder().set("Referer", referer).build())
            ?.clean() ?: return emptyList()
        val media = headers.newBuilder().set("Origin", "https://ok.ru").set("Referer", embed).build()

        field(page, "hlsManifestUrl")?.let { hls ->
            val v = client.expandHls(hls, media, prefix)
            if (v.isNotEmpty()) return v
        }
        field(page, "metadataWebmUrl")?.let { return listOf(Video(it, "$prefix - WebM", it, media)) }

        val arr = Regex(""""videos"\s*:\s*(\[[^\]]*])""").find(page)?.groupValues?.get(1) ?: return emptyList()
        val names = mapOf(
            "MOBILE" to "144p", "LOWEST" to "240p", "LOW" to "360p", "SD" to "480p",
            "HD" to "720p", "FULL" to "1080p", "QUAD" to "1440p", "ULTRA" to "2160p",
        )
        return runCatching {
            val json = JSONArray(arr)
            (0 until json.length()).mapNotNull { i ->
                val o = json.optJSONObject(i) ?: return@mapNotNull null
                val n = o.optString("name").trim().uppercase()
                var u = o.optString("url").trim()
                if (n.isBlank() || u.isBlank()) return@mapNotNull null
                if (u.startsWith("//")) u = "https:$u"
                Video(u, "$prefix - ${names[n] ?: n}", u, media)
            }.reversed()
        }.getOrDefault(emptyList())
    }

    private fun field(raw: String, name: String): String? =
        Regex(""""$name"\s*:\s*"([^"]+)"""").find(raw)?.groupValues?.get(1)
            ?.clean()?.takeIf { it.startsWith("http", true) }

    private fun String.clean(): String = this
        .replace("\\&quot;", "\"").replace("&quot;", "\"")
        .replace("\\u0026", "&").replace("\\u003d", "=").replace("\\u003D", "=")
        .replace("\\u002F", "/").replace("\\/", "/").replace("\\&", "&").replace("\\=", "=")
        .replace("\\\\", "\\")
        .replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { Integer.parseInt(it.groupValues[1], 16).toChar().toString() }
}

// ============================================================================
// Rumble
// ============================================================================

class RumbleExtractor(private val client: OkHttpClient, private val headers: Headers) {

    fun videosFromUrl(url: String, referer: String, prefix: String = "Rumble"): List<Video> {
        val html = client.getText(url, headers.newBuilder().set("Referer", referer).build()) ?: return emptyList()
        val m3u8s = Regex(""""url"\s*:\s*"(https:\\?/\\?/[^"]*?\.m3u8[^"]*)"""").findAll(html)
            .map { it.groupValues[1].replace("\\/", "/") }
            .filter { it.contains("rumble.com") }
            .distinct().toList()

        var last: List<Video> = emptyList()
        for (m3u8 in m3u8s) {
            val videos = client.expandHls(m3u8, headers, prefix)
            if (videos.size > 1) return videos // master playlist multi-kualitas
            last = videos
        }
        return last
    }
}

// ============================================================================
// StreamRuby (rubyvidhub.com / streamruby.com / streamruby.net)
// ============================================================================

class StreamRubyExtractor(private val client: OkHttpClient, private val headers: Headers) {

    fun videosFromUrl(url: String, referer: String, prefix: String = "StreamRuby"): List<Video> {
        val id = Regex("""embed-([a-zA-Z0-9]+)\.html""").find(url)?.groupValues?.get(1) ?: return emptyList()
        val origin = url.origin()
        val body = FormBody.Builder()
            .add("op", "embed").add("file_code", id).add("auto", "1").add("referer", "")
            .build()
        val text = runCatching {
            client.newCall(POST("$origin/dl", headers.newBuilder().set("Referer", referer).build(), body))
                .execute().use { it.body?.string() }
        }.getOrNull() ?: return emptyList()

        val script = unpack(text) ?: text
        val m3u8 = Regex("""file:\s*"(.*?m3u8.*?)"""").find(script)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: return emptyList()
        val media = headers.newBuilder().set("Referer", "$origin/").build()
        return client.expandHls(m3u8, media, prefix)
    }

    /** Unpacker untuk JS ber-format Dean Edwards p,a,c,k,e,d. */
    private fun unpack(text: String): String? {
        val m = Regex("""\}\('(.*)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
            .find(text) ?: return null
        val payload = m.groupValues[1].replace("\\'", "'")
        val radix = m.groupValues[2].toInt()
        val dict = m.groupValues[4].split("|")
        return Regex("""\b\w+\b""").replace(payload) { w ->
            val idx = decodeBase(w.value, radix)
            dict.getOrNull(idx)?.takeIf { it.isNotEmpty() } ?: w.value
        }
    }

    private fun decodeBase(s: String, radix: Int): Int {
        var n = 0
        for (c in s) {
            val d = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'z' -> c - 'a' + 10
                in 'A'..'Z' -> c - 'A' + 36
                else -> return -1
            }
            if (d >= radix) return -1
            n = n * radix + d
        }
        return n
    }
}

// ============================================================================
// TurboVid (turbovidhls.com) — port dari Turbovidhls CS3
// ============================================================================

class TurboVidExtractor(private val client: OkHttpClient, private val headers: Headers) {

    fun videosFromUrl(url: String, referer: String, prefix: String = "TurboVid"): List<Video> {
        val origin = url.origin()
        val page = client.getText(url, headers.newBuilder().set("Referer", referer).build()) ?: return emptyList()

        var master = Regex("""var\s+urlPlay\s*=\s*['"]([^'"]+)['"]""").find(page)?.groupValues?.get(1)?.trim()
            ?: return emptyList()
        master = when {
            master.startsWith("//") -> "https:$master"
            master.startsWith("/") -> origin + master
            else -> master
        }
        val media = headers.newBuilder().set("Referer", "$origin/").set("Origin", origin).build()
        return client.expandHls(master, media, prefix)
    }
}
