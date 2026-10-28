package eu.kanade.tachiyomi.animeextension.all.av19t

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.InetAddress

class Av19t : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AV19T"
    override val lang = "all"
    override val supportsLatest = true

    // ---------- Preferences ----------

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val trCache: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("av19t_title_cache", 0x0000)
    }

    // Domain bisa diganti user kalau situs pindah
    override val baseUrl: String
        get() = preferences.getString(PREF_BASE, DEFAULT_BASE)!!.trim().trimEnd('/')

    // ---------- DNS manual (DoH) ----------
    // Dibaca tiap lookup, jadi perubahan di pengaturan langsung berlaku tanpa restart.
    // Catatan: hanya untuk request extension, bukan untuk player.

    private val dynamicDns = object : Dns {
        @Volatile private var cachedUrl = ""

        @Volatile private var cachedDoh: Dns = Dns.SYSTEM

        override fun lookup(hostname: String): List<InetAddress> {
            val url = preferences.getString(PREF_DOH, "").orEmpty().trim()
            if (url.isEmpty()) return Dns.SYSTEM.lookup(hostname)
            if (url != cachedUrl) {
                cachedDoh = DnsOverHttps.Builder()
                    .client(network.client)
                    .url(url.toHttpUrl())
                    .build()
                cachedUrl = url
            }
            return cachedDoh.lookup(hostname)
        }
    }

    override val client: OkHttpClient by lazy {
        network.client.newBuilder().dns(dynamicDns).build()
    }

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder().add("Referer", "$baseUrl/")

    // ---------- Popular ----------

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/bbs/best.php?onetable=&period=7d&page=$page&rows=$ROWS", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val raws = parseList(response.asJsoup())
        return toPage(raws, hasNext = raws.size >= ROWS)
    }

    // ---------- Latest = home page ----------

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        toPage(parseList(response.asJsoup()), hasNext = false)

    // ---------- Search / Filter ----------

    override fun getFilterList() = AnimeFilterList(SectionFilter())

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            // TODO: endpoint search belum diverifikasi (asumsi pola gnuboard)
            val url = "$baseUrl/bbs/search.php".toHttpUrl().newBuilder()
                .addQueryParameter("sfl", "wr_subject")
                .addQueryParameter("stx", query)
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url, headers)
        }
        val idx = (filters.firstOrNull { it is SectionFilter } as? SectionFilter)?.state ?: 0
        val path = SECTIONS[idx].second
        return if (path.isEmpty()) GET(baseUrl, headers) else GET("$baseUrl/$path?page=$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val raws = parseList(response.asJsoup())
        val isHome = response.request.url.encodedPath.let { it == "/" || it.isEmpty() }
        return toPage(raws, hasNext = !isHome && raws.isNotEmpty())
    }

    private class SectionFilter : AnimeFilter.Select<String>(
        "Kategori",
        SECTIONS.map { it.first }.toTypedArray(),
    )

    // ---------- Parsing list (3 struktur) ----------

    private data class Raw(val url: String, val title: String, val thumb: String?)

    private fun parseList(doc: Document): List<Raw> {
        // 1) Populer: best.php
        doc.select("a.uxbest-card").takeIf { it.isNotEmpty() }?.let { els ->
            return els.map {
                Raw(
                    rel(it.attr("href")),
                    it.selectFirst(".uxbest-title")?.text().orEmpty(),
                    img(it.selectFirst(".uxbest-thumb img")),
                )
            }
        }
        // 2) Page kategori (/korea, /bj, /nsub)
        doc.select("article.luxg-card").takeIf { it.isNotEmpty() }?.let { els ->
            return els.mapNotNull {
                val a = it.selectFirst("a.luxg-a") ?: return@mapNotNull null
                Raw(
                    rel(a.attr("href")),
                    it.selectFirst(".luxg-subject")?.text().orEmpty(),
                    img(it.selectFirst(".luxg-img")),
                )
            }
        }
        // 3) Home
        return doc.select("article[id^=post-]").mapNotNull {
            val a = it.selectFirst("a.archive-entry") ?: it.closest("a.archive-entry")
                ?: return@mapNotNull null
            Raw(
                rel(a.attr("href")),
                it.selectFirst(".entry-header span")?.text().orEmpty(),
                img(it.selectFirst(".avhome-thumb img")),
            )
        }
    }

    // Simpan URL relatif supaya library user aman kalau domain berganti
    private fun rel(href: String) = href.replace(Regex("^https?://[^/]+"), "")

    private fun img(el: Element?): String? =
        el?.let { it.attr("abs:data-src").ifEmpty { it.attr("abs:src") } }?.ifEmpty { null }

    private fun toPage(raws: List<Raw>, hasNext: Boolean): AnimesPage {
        val titles = translateTitles(raws.map { it.title })
        val list = raws.mapIndexed { i, r ->
            SAnime.create().apply {
                url = r.url
                title = titles[i]
                thumbnail_url = r.thumb
            }
        }
        return AnimesPage(list, hasNext)
    }

    // ---------- Details / Episode ----------

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val raw = doc.selectFirst("h1")?.text().orEmpty()
        val tr = translateTitles(listOf(raw)).first()
        return SAnime.create().apply {
            title = tr
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            description = if (tr != raw) "Judul asli: $raw" else null
            status = SAnime.COMPLETED
        }
    }

    // Satu video = satu "episode"
    override fun episodeListParse(response: Response): List<SEpisode> = listOf(
        SEpisode.create().apply {
            url = response.request.url.encodedPath
            name = "Video"
            episode_number = 1f
        },
    )

    // ---------- Video ----------

    override fun videoListParse(response: Response): List<Video> {
        val iframe = response.asJsoup().selectFirst("iframe#videoIframe")?.attr("abs:src")
            ?: return emptyList()
        return extractVideos(iframe)
    }

    // iframe: p.nnvivi.site/player.php?k=... -> HTML berisi url: "https:\/\/k.vdnext.com/.../v.html?tok=..&exp=.."
    // v.html ternyata playlist m3u8 biasa. Token dibuat per kunjungan, jadi diambil saat play.
    private fun extractVideos(iframeUrl: String): List<Video> {
        val iframeHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
        val html = client.newCall(GET(iframeUrl, iframeHeaders)).execute().use { it.body.string() }
        val m3u8 = Regex("""url:\s*"(https:\\/\\/[^"]+)"""")
            .find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: return emptyList()
        // TODO: sesuaikan header dengan yang dikirim browser (cek di Reqable)
        val videoHeaders = headers.newBuilder().set("Referer", "https://p.nnvivi.site/").build()
        return listOf(Video(m3u8, "Default", m3u8, videoHeaders))
    }

    // ---------- Translate judul (batch + cache persisten) ----------

    private fun translateTitles(titles: List<String>): List<String> {
        if (!preferences.getBoolean(PREF_TR, true) || titles.isEmpty()) return titles
        val target = preferences.getString(PREF_LANG, "id")!!.ifBlank { "id" }
        fun key(t: String) = "$target|$t"

        val missing = titles.filter { it.isNotBlank() && trCache.getString(key(it), null) == null }
            .distinct()

        missing.chunked(TR_CHUNK).forEach { chunk ->
            val out = requestTranslate(chunk, target) ?: return@forEach // gagal -> pakai judul asli
            val edit = trCache.edit()
            chunk.forEachIndexed { i, t -> edit.putString(key(t), out[i].trim()) }
            edit.apply()
        }
        return titles.map { trCache.getString(key(it), null) ?: it }
    }

    // Endpoint tidak resmi, bisa berubah / kena rate limit. Ganti kalau perlu.
    private fun requestTranslate(lines: List<String>, target: String): List<String>? = runCatching {
        val q = lines.joinToString("\n") { it.replace("\n", " ") }
        val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", target)
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", q)
            .build()
        network.client.newCall(GET(url)).execute().use { resp ->
            val segs = JSONArray(resp.body.string()).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until segs.length()) sb.append(segs.getJSONArray(i).getString(0))
            val out = sb.toString().split("\n")
            if (out.size == lines.size) out else null // jumlah baris harus sama
        }
    }.getOrNull()

    // ---------- Pengaturan ----------

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_BASE
            title = "Domain situs"
            summary = "Default: $DEFAULT_BASE"
            setDefaultValue(DEFAULT_BASE)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_DOH
            title = "DNS-over-HTTPS (opsional)"
            summary = "Contoh: https://cloudflare-dns.com/dns-query (kosong = DNS sistem)"
            setDefaultValue("")
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_TR
            title = "Terjemahkan judul"
            setDefaultValue(true)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_LANG
            title = "Bahasa tujuan"
            summary = "Kode bahasa, contoh: id, en"
            setDefaultValue("id")
        }.also(screen::addPreference)
    }

    companion object {
        private const val DEFAULT_BASE = "https://av19t.com"
        private const val PREF_BASE = "pref_base_url"
        private const val PREF_DOH = "pref_doh_url"
        private const val PREF_TR = "pref_translate_title"
        private const val PREF_LANG = "pref_translate_lang"
        private const val ROWS = 24
        private const val TR_CHUNK = 20

        private val SECTIONS = listOf(
            "Beranda (semua)" to "",
            "Korea" to "korea",
            "Movie Korea (BJ)" to "bj",
            "JP" to "nsub",
        )
    }
}
