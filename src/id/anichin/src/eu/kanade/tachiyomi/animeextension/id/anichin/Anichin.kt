package eu.kanade.tachiyomi.animeextension.id.anichin

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Port dari AnichinProvider (CloudStream3) ke Aniyomi.
 * Selector CSS diambil apa adanya dari versi CS3 — kalau HTML situs berubah,
 * cukup ubah selector di bagian "Selectors" di bawah.
 */
class Anichin : ParsedAnimeHttpSource() {

    override val name = "Anichin"
    override val baseUrl = "https://anichin.moe"
    override val lang = "id"
    override val supportsLatest = true

    override val client: OkHttpClient by lazy { network.cloudflareClient }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Selectors ==============================
    private val listSelector = "div.listupd > article"
    private val nextPageSelector = "div.hpage a.r, a.next.page-numbers, .nav-links a.next, .pagination .next"

    // ============================== Popular ================================
    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/anime/?order=popular&page=$page", headers)

    override fun popularAnimeSelector() = listSelector
    override fun popularAnimeNextPageSelector() = nextPageSelector

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val a = element.selectFirst("div.bsx > a") ?: element.selectFirst("a")!!
        setUrlWithoutDomain(a.attr("href"))
        title = a.attr("title").ifBlank { element.selectFirst(".tt")?.text().orEmpty() }.trim()
        thumbnail_url = element.selectFirst("img")?.imageUrl()
    }

    // ============================== Latest =================================
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/anime/?order=update&page=$page", headers)

    override fun latestUpdatesSelector() = listSelector
    override fun latestUpdatesNextPageSelector() = nextPageSelector
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    // ============================== Search =================================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "$baseUrl/?s=$q" else "$baseUrl/page/$page/?s=$q"
        return GET(url, headers)
    }

    override fun searchAnimeSelector() = listSelector
    override fun searchAnimeNextPageSelector() = nextPageSelector
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    // ============================== Details ================================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        thumbnail_url = document.selectFirst("div.ime > img")?.imageUrl()
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        description = document.selectFirst("div.entry-content")?.text()?.trim()
        genre = document.select(".genxed a, .genre a, .genres a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .joinToString()
        val statusText = document.select(".spe span")
            .firstOrNull { it.text().contains("Status", true) }
            ?.text().orEmpty()
        status = when {
            statusText.contains("Ongoing", true) -> SAnime.ONGOING
            statusText.contains("Completed", true) || statusText.contains("Tamat", true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ===============================
    override fun episodeListSelector() = ".eplister li"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val a = element.selectFirst("a")!!
        setUrlWithoutDomain(a.attr("href"))

        val rawTitle = element.selectFirst(".epl-title")?.text()?.trim().orEmpty()
        val sub = element.selectFirst(".epl-sub span")?.text()?.trim().orEmpty()
        val numText = element.selectFirst(".epl-num")?.text().orEmpty()

        episode_number = Regex("""Episode\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            .find(rawTitle)?.groupValues?.get(1)?.toFloatOrNull()
            ?: Regex("""\d+(?:\.\d+)?""").find(numText)?.value?.toFloatOrNull()
            ?: -1f

        val cleanTitle = rawTitle
            .replace(Regex("""Subtitle Indonesia""", RegexOption.IGNORE_CASE), "")
            .trim()
        name = listOf(cleanTitle, sub).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { "Episode" }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = document.select(episodeListSelector())
            .filter { it.selectFirst("a")?.attr("href").isNullOrBlank().not() }
            .map { episodeFromElement(it) }

        if (episodes.isEmpty()) {
            // Movie / halaman tanpa daftar episode: pakai halaman itu sendiri
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(response.request.url.toString())
                    name = "Movie"
                    episode_number = 1f
                },
            )
        }

        // Perbaikan "episode acak": urutkan berdasarkan nomor episode kalau semua terbaca.
        return if (episodes.all { it.episode_number >= 0f }) {
            episodes.sortedByDescending { it.episode_number }
        } else {
            episodes // urutan situs (terbaru di atas)
        }
    }

    // ============================== Videos =================================
    override fun videoListSelector() = "iframe"
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    private val dailymotion by lazy { DailymotionExtractor(client, headers) }
    private val okru by lazy { OkRuExtractor(client, headers) }
    private val rumble by lazy { RumbleExtractor(client, headers) }
    private val streamRuby by lazy { StreamRubyExtractor(client, headers) }
    private val turbo by lazy { TurboVidExtractor(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val pageUrl = response.request.url.toString()
        val candidates = linkedSetOf<String>()

        fun add(value: String?) {
            if (value.isNullOrBlank()) return
            decodeServerUrls(value).forEach { raw ->
                normalizeUrl(raw, pageUrl)?.let { candidates.add(it) }
            }
        }

        document.select("iframe[src], iframe[data-src], embed[src], source[src], video[src]").forEach {
            add(it.attr("data-src").ifBlank { it.attr("abs:src") })
        }
        document.select("select option[value], .mobius option[value]").forEach { add(it.attr("value")) }
        document.select(
            "[data-src], [data-lazy-src], [data-url], [data-link], [data-video], [data-embed], [data-player], [data-file]",
        ).forEach { el ->
            listOf("data-src", "data-lazy-src", "data-url", "data-link", "data-video", "data-embed", "data-player", "data-file")
                .forEach { attr -> add(el.attr(attr)) }
        }
        extractKnownUrls(document.html()).forEach { normalizeUrl(it, pageUrl)?.let(candidates::add) }

        val servers = candidates
            .filterNot { isNoise(it) }
            .filter { priority(it) < 99 || isDirectMedia(it) }
            .sortedBy { priority(it) }
            .take(MAX_SERVERS)

        val videos = mutableListOf<Video>()
        for (url in servers) {
            val found = runCatching { resolve(url, pageUrl) }.getOrDefault(emptyList())
            videos += found
        }
        return videos.distinctBy { it.videoUrl }
    }

    private fun resolve(url: String, referer: String): List<Video> {
        val u = url.lowercase()
        return when {
            u.contains(".m3u8") -> client.expandHls(url, headers.withReferer(referer), "Anichin")
            u.contains(".mp4") || u.contains(".webm") ->
                listOf(Video(url, "Anichin", url, headers.withReferer(referer)))
            "dailymotion.com" in u || "dai.ly" in u -> dailymotion.videosFromUrl(url, referer)
            "ok.ru" in u || "odnoklassniki.ru" in u -> okru.videosFromUrl(url, referer)
            "rumble.com" in u -> rumble.videosFromUrl(url, referer)
            "streamruby" in u || "rubyvidhub" in u -> streamRuby.videosFromUrl(url, referer)
            "turbovid" in u -> turbo.videosFromUrl(url, referer)
            "vidguard" in u || "bembed.net" in u || "listeamed.net" in u || "vgfplay.com" in u ->
                VidGuardExtractor(client).videosFromUrl(url)
            "vidhide" in u || "morencius" in u || "streamwish" in u ->
                StreamWishExtractor(client, headers).videosFromUrl(url)
            else -> emptyList()
        }
    }

    // ============================== Helpers ================================
    private fun priority(url: String): Int {
        val u = url.lowercase()
        return when {
            "dailymotion.com" in u || "dai.ly" in u -> 0
            "ok.ru" in u || "odnoklassniki.ru" in u -> 1
            "turbovid" in u -> 2
            "streamruby" in u || "rubyvidhub" in u -> 3
            "vidguard" in u || "bembed.net" in u || "listeamed.net" in u || "vgfplay.com" in u -> 4
            "vidhide" in u || "morencius" in u || "streamwish" in u -> 5
            "rumble.com" in u -> 6
            else -> 99
        }
    }

    private fun isDirectMedia(url: String) = url.contains(".m3u8", true) ||
        url.contains(".mp4", true) || url.contains(".webm", true)

    private fun isNoise(url: String): Boolean {
        val v = url.lowercase()
        return v.isBlank() || v.startsWith("#") || v.startsWith("javascript") ||
            listOf(
                "facebook.com", "twitter.com", "telegram", "whatsapp", "youtube.com", "youtu.be",
                "trailer", "banner", "doubleclick", "googlesyndication", "analytics", "tracking", "popads",
            ).any { v.contains(it) }
    }

    private fun normalizeUrl(url: String, base: String): String? {
        val fixed = url.cleanEscaped().trim('"', '\'', ' ', '\n', '\r', '\t')
        if (fixed.isBlank()) return null
        return when {
            fixed.startsWith("//") -> "https:$fixed"
            fixed.startsWith("http://", true) || fixed.startsWith("https://", true) -> fixed
            else -> runCatching { URI(base).resolve(fixed).toString() }.getOrNull()
        }
    }

    /** Nilai server bisa berupa URL, HTML <iframe>, URL-encoded, atau base64. */
    private fun decodeServerUrls(value: String): List<String> {
        val decoded = linkedSetOf<String>()
        val clean = value.trim().cleanEscaped()
        if (clean.isBlank()) return emptyList()
        decoded.add(clean)
        runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { decoded.add(it.cleanEscaped()) }
        decodeBase64(clean)?.takeIf { it.isNotBlank() }?.let { decoded.add(it.cleanEscaped()) }

        val results = linkedSetOf<String>()
        decoded.forEach { text ->
            Jsoup.parse(text).select("iframe[src], iframe[data-src], embed[src], source[src], video[src], a[href]")
                .forEach { el ->
                    el.attr("data-src").ifBlank { el.attr("src") }.ifBlank { el.attr("href") }
                        .takeIf { it.isNotBlank() }?.let(results::add)
                }
            extractKnownUrls(text).forEach(results::add)
            if (results.isEmpty()) results.add(text)
        }
        return results.toList()
    }

    private fun decodeBase64(value: String): String? {
        if (value.length < 8 || value.contains("://") || value.contains("<")) return null
        return runCatching {
            val fixed = value.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
            String(Base64.decode(fixed, Base64.DEFAULT))
        }.getOrNull()
    }

    private fun extractKnownUrls(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val text = raw.cleanEscaped()
        val urls = linkedSetOf<String>()
        Regex("""https?:\\?/\\?/[^"'<>\\\s]+""", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            val u = m.value.replace("\\/", "/")
            if (priority(u) < 99 || isDirectMedia(u)) urls.add(u)
        }
        Regex("""(?i)(?:file|url|src|embed|video|videoUrl|video_url|hls|hlsUrl|embedUrl|embed_url)\s*[:=]\s*["']([^"']+)["']""")
            .findAll(text).forEach { m ->
                val u = m.groupValues[1]
                if (priority(u) < 99 || isDirectMedia(u)) urls.add(u)
            }
        return urls.toList()
    }

    private fun String.cleanEscaped(): String = this
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
        .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("\\/", "/").replace("\\u002F", "/").replace("\\u003A", ":")
        .replace("\\u003D", "=").replace("\\u0026", "&").replace("\\\"", "\"")
        .trim()

    /** Poster sering lazy-load: cek data-lazy-src / data-src sebelum src. */
    private fun Element.imageUrl(): String? =
        listOf("data-lazy-src", "data-src", "src")
            .map { attr("abs:$it") }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }

    private fun Headers.withReferer(referer: String): Headers =
        newBuilder().set("Referer", referer).build()

    companion object {
        private const val MAX_SERVERS = 12
    }
}
