package eu.kanade.tachiyomi.animeextension.id.moviebox

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * MovieBox (moviebox.ph)
 *
 * Di-porting dari provider CloudStream (MovieboxProvider.kt).
 * Situs film/drama, bukan situs anime spesifik -- tapi bisa jalan lewat
 * kerangka Aniyomi karena strukturnya video-on-demand yang sama.
 *
 * CATATAN ASUMSI (tolong sesuaikan kalau beda dengan repo kamu):
 *  - Package/nama class ini asumsi konvensi standar Aniyomi
 *    (eu.kanade.tachiyomi.animeextension.id.moviebox). Kalau repo kamu
 *    pakai konvensi lain, tinggal disesuaikan.
 *  - Pakai kotlinx.serialization buat parsing JSON (umum dipakai di
 *    extension Aniyomi/Tachiyomi modern). Kalau repo kamu masih pakai
 *    Jackson/Gson kayak versi CloudStream, kasih tahu, nanti aku ubah.
 *  - anime.url dipakai buat nyimpen subjectId mentah (bukan path relatif),
 *    karena API-nya berbasis ID, bukan slug/path seperti web biasa.
 *  - episode.url nyimpen JSON kecil { id, season, episode } (mirip
 *    LoadData di versi CloudStream) supaya videoListRequest tau subjectId,
 *    season, dan episode-nya.
 */
class MovieBox : AnimeHttpSource() {

    override val name = "MovieBox"
    override val baseUrl = "https://moviebox.ph"
    override val lang = "id"
    override val supportsLatest = true

    private val mainApiUrl = "https://h5-api.aoneroom.com"
    private val secondApiUrl = "https://filmboom.top"

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        // ===== Token cache (in-memory) =====
        private var cachedToken: String? = null
        private var cachedTokenExpiry: Long = 0L

        // ===== ID kategori main page =====
        private const val POPULAR_ID = "872031290915189720" // Trending Now
        private const val LATEST_ID = "4380734070238626200" // K-drama new release

        // ===== Daftar kategori buat filter =====
        val CATEGORIES = listOf(
            "Trending drama" to "4809349160627587984",
            "Trending movie" to "8821254238245470240",
            "Hot TV" to "567783349092340776",
            "Into Animeverse" to "8617025562613270856",
            "Trending Indonesia drama" to "5283462032510044280",
            "K-drama new release" to "4380734070238626200",
            "Trending western" to "1469286917119311888",
            "Recently added" to "4019055174353407000",
            "Trending c-drama" to "8624142774394406504",
            "Trending India" to "5228078672430649576",
            "Midnight horror" to "5848753831881965888",
            "Funny horror & crime" to "3528002473103362040",
            "Thai drama" to "1164329479448281992",
            "Family time" to "3770510954220470720",
            "Trending Indonesia dub" to "5549742004948601072",
            "Killer instinct" to "5863917898430924656",
            "No regrets for loving you" to "4539350473970797944",
            "Animated film" to "7132534597631837112",
            "Monster & Titan" to "1653005382303864120",
            "Hollywood" to "8019599703232971616",
            "Best Asian drama" to "1976033493293449744",
        )
    }

    // ===== Token =====
    // Sinkron (bukan suspend) karena AnimeHttpSource build Request/parse
    // Response secara sinkron, beda dengan CloudStream yang suspend.
    private fun getMbToken(): String {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < cachedTokenExpiry) return it }

        val request = Request.Builder()
            .url("$mainApiUrl/wefeed-h5api-bff/home?host=moviebox.ph")
            .headers(
                Headers.headersOf(
                    "Referer", "$baseUrl/",
                    "Origin", baseUrl,
                    "X-Client-Info", "{\"timezone\":\"Asia/Jakarta\"}",
                    "X-Request-Lang", "en",
                    "Accept", "application/json",
                ),
            )
            .build()

        val response = client.newCall(request).execute()

        var token: String? = null
        val xUser = response.header("x-user") ?: response.header("X-User")
        if (!xUser.isNullOrBlank()) {
            token = Regex(""""token"\s*:\s*"([^"]+)"""").find(xUser)?.groupValues?.get(1)
        }
        if (token.isNullOrBlank()) {
            token = response.headers("Set-Cookie")
                .firstNotNullOfOrNull { cookie ->
                    Regex("""(?:token|mb_token)=([^;]+)""").find(cookie)?.groupValues?.get(1)
                }
        }
        response.close()

        if (token.isNullOrBlank()) throw Exception("Gagal ambil token MovieBox")

        cachedToken = token
        // Endpoint gak ngasih info expiry eksplisit -- cache 1 jam biar aman,
        // sama kayak logic di versi CloudStream.
        cachedTokenExpiry = now + 60 * 60 * 1000L

        return token
    }

    private fun apiHeaders(): Headers = Headers.headersOf(
        "Authorization", "Bearer ${getMbToken()}",
        "Content-Type", "application/json",
        "X-Client-Info", "{\"timezone\":\"Asia/Jakarta\"}",
        "X-Request-Lang", "en",
        "Referer", "$baseUrl/",
        "Origin", baseUrl,
    )

    // ===== Popular =====
    override fun popularAnimeRequest(page: Int): Request {
        return Request.Builder()
            .url("$mainApiUrl/wefeed-h5api-bff/ranking-list/content?id=$POPULAR_ID&page=$page&perPage=12")
            .headers(apiHeaders())
            .build()
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = json.parseToJsonElement(response.body.string()).jsonObject["data"]?.jsonObject
        val list = data?.get("subjectList")?.jsonArray.orEmpty()
        val animes = list.map { it.jsonObject.toSAnime() }
        return AnimesPage(animes, animes.isNotEmpty())
    }

    // ===== Latest =====
    override fun latestUpdatesRequest(page: Int): Request {
        return Request.Builder()
            .url("$mainApiUrl/wefeed-h5api-bff/ranking-list/content?id=$LATEST_ID&page=$page&perPage=12")
            .headers(apiHeaders())
            .build()
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ===== Search + Filter (kategori) =====
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val bodyJson = buildJsonObject {
                put("keyword", query)
                put("page", page)
                put("perPage", 20)
            }
            val body = bodyJson.toString().toRequestBody("application/json".toMediaType())

            return Request.Builder()
                .url("$mainApiUrl/wefeed-h5api-bff/subject/search")
                .headers(apiHeaders())
                .post(body)
                .build()
        }

        val categoryFilter = filters.filterIsInstance<CategoryFilter>().firstOrNull()
        val categoryId = categoryFilter?.let { CATEGORIES[it.state].second } ?: POPULAR_ID

        return Request.Builder()
            .url("$mainApiUrl/wefeed-h5api-bff/ranking-list/content?id=$categoryId&page=$page&perPage=12")
            .headers(apiHeaders())
            .build()
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val data = json.parseToJsonElement(response.body.string()).jsonObject["data"]?.jsonObject
        // Endpoint search pakai "items", endpoint ranking-list pakai "subjectList"
        val list = data?.get("items")?.jsonArray
            ?: data?.get("subjectList")?.jsonArray.orEmpty()
        val animes = list.map { it.jsonObject.toSAnime() }
        return AnimesPage(animes, animes.isNotEmpty())
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CategoryFilter(),
    )

    class CategoryFilter : AnimeFilter.Select<String>(
        "Kategori",
        CATEGORIES.map { it.first }.toTypedArray(),
    )

    // ===== Detail =====
    // anime.url nyimpen subjectId mentah.
    override fun animeDetailsRequest(anime: SAnime): Request {
        return Request.Builder()
            .url("$secondApiUrl/wefeed-h5-bff/web/subject/detail?subjectId=${anime.url}")
            .build()
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val data = json.parseToJsonElement(response.body.string()).jsonObject["data"]?.jsonObject
        val subject = data?.get("subject")?.jsonObject
        return subject?.toSAnime() ?: SAnime.create()
    }

    // ===== Episode list =====
    // Pakai endpoint detail yang sama karena "resource.seasons" sudah include di response detail.
    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = json.parseToJsonElement(response.body.string()).jsonObject["data"]?.jsonObject
        val subject = data?.get("subject")?.jsonObject
        val subjectId = subject?.get("subjectId")?.jsonPrimitive?.content ?: ""
        val detailPath = subject?.get("detailPath")?.jsonPrimitive?.content ?: ""
        val seasons = data?.get("resource")?.jsonObject?.get("seasons")?.jsonArray.orEmpty()

        return seasons.flatMap { seasonEl ->
            val season = seasonEl.jsonObject
            val se = season["se"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            val allEp = season["allEp"]?.jsonPrimitive?.content
            val maxEp = season["maxEp"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val epNumbers = if (allEp.isNullOrBlank()) (1..maxEp) else allEp.split(",").mapNotNull { it.trim().toIntOrNull() }

            epNumbers.map { ep ->
                SEpisode.create().apply {
                    // detailPath ikut disimpan di sini karena cuma tersedia dari response
                    // detail -- dibutuhkan buat bangun Referer yang benar di videoListRequest
                    // (endpoint play/caption menolak kasih stream kalau Referer-nya generik).
                    url = """{"id":"$subjectId","season":$se,"episode":$ep,"detailPath":"$detailPath"}"""
                    episode_number = ep.toFloat()
                    name = "S$se E$ep"
                }
            }
        }.sortedWith(compareBy({ it.name.substringBefore(" ") }, { it.episode_number }))
    }

    // ===== Video list =====
    override fun videoListRequest(episode: SEpisode): Request {
        val media = json.parseToJsonElement(episode.url).jsonObject
        val id = media["id"]?.jsonPrimitive?.content
        val se = media["season"]?.jsonPrimitive?.content ?: "0"
        val ep = media["episode"]?.jsonPrimitive?.content ?: "0"
        val detailPath = media["detailPath"]?.jsonPrimitive?.content ?: ""

        // Endpoint play/caption di secondApiUrl TIDAK butuh Authorization Bearer,
        // tapi butuh Referer yang meniru halaman video-player asli -- Referer generik
        // bikin server balikin streams kosong walau status 200.
        val referer = "$secondApiUrl/spa/videoPlayPage/movies/$detailPath?id=$id&type=/movie/detail&lang=en"

        return Request.Builder()
            .url("$secondApiUrl/wefeed-h5-bff/web/subject/play?subjectId=$id&se=$se&ep=$ep")
            .header("Referer", referer)
            .build()
    }

    override fun videoListParse(response: Response): List<Video> {
        // subjectId & referer diambil dari request asli (bukan dari objek stream/hardcode),
        // supaya caption request pakai Referer yang sama persis dengan request play.
        val subjectId = response.request.url.queryParameter("subjectId")
        val referer = response.request.header("Referer") ?: "$secondApiUrl/"
        val data = json.parseToJsonElement(response.body.string()).jsonObject["data"]?.jsonObject
        val streams = data?.get("streams")?.jsonArray.orEmpty()

        val subtitles = fetchSubtitles(streams, subjectId, referer)

        return streams.reversed().distinctBy { it.jsonObject["url"]?.jsonPrimitive?.content }
            .mapNotNull { streamEl ->
                val stream = streamEl.jsonObject
                val url = stream["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val quality = stream["resolutions"]?.jsonPrimitive?.content ?: "Unknown"
                Video(url, quality, url, headers = Headers.headersOf("Referer", "$secondApiUrl/"), subtitleTracks = subtitles)
            }
    }

    // Ambil subtitle -- butuh id+format dari stream pertama + subjectId dari
    // episode (bukan dari objek stream), mirror dari alur "caption" di CloudStream.
    private fun fetchSubtitles(streams: JsonArray, subjectId: String?, referer: String): List<Track> {
        val first = streams.firstOrNull()?.jsonObject ?: return emptyList()
        val id = first["id"]?.jsonPrimitive?.content ?: return emptyList()
        val format = first["format"]?.jsonPrimitive?.content ?: return emptyList()

        val request = Request.Builder()
            .url("$secondApiUrl/wefeed-h5-bff/web/subject/caption?format=$format&id=$id&subjectId=$subjectId")
            .header("Referer", referer)
            .build()

        return try {
            val response = client.newCall(request).execute()
            val data = json.parseToJsonElement(response.body.string()).jsonObject["data"]?.jsonObject
            val captions = data?.get("captions")?.jsonArray.orEmpty()
            response.close()
            captions.mapNotNull { capEl ->
                val cap = capEl.jsonObject
                val url = cap["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val lang = cap["lanName"]?.jsonPrimitive?.content ?: ""
                Track(url, lang)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ===== Helper: JsonObject item -> SAnime =====
    private fun JsonObject.toSAnime(): SAnime = SAnime.create().apply {
        val subjectId = this@toSAnime["subjectId"]?.jsonPrimitive?.content ?: ""
        url = subjectId
        title = this@toSAnime["title"]?.jsonPrimitive?.content ?: ""
        thumbnail_url = this@toSAnime["cover"]?.jsonObject?.get("url")?.jsonPrimitive?.content
        description = this@toSAnime["description"]?.jsonPrimitive?.content
        genre = this@toSAnime["genre"]?.jsonPrimitive?.content
    }
}
