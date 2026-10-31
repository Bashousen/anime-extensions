package eu.kanade.tachiyomi.animeextension.pt.meusanimes

import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.bodyString
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLEncoder

@Source
abstract class MeusAnimes : AnimeHttpLegacySource() {

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", baseUrl)

    // Popular
    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(".top10_ani a")
            .map { element ->
                SAnime.create().apply {
                    title = element.select(".top10_ani_title").text()
                    url = element.attr("href")
                    thumbnail_url = element.selectFirst("img")
                        ?.attr("abs:src")
                        ?: ""
                }
            }

        return AnimesPage(animes, false)
    }

    // Search
    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/lista-de-animes?page=$page&search=$q", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = document.select(".ultAnisContainerItem a").map { element ->
            SAnime.create().apply {
                title = element.selectFirst(".aniNome")?.text() ?: element.attr("title")
                url = element.attr("href")
                thumbnail_url = element.selectFirst(".aniImg img")
                    ?.attr("abs:src")
                    ?: ""

                initialized = false
            }
        }

        val hasNextPage = document.selectFirst(".paginacao a[rel=next]") != null

        return AnimesPage(animes, hasNextPage)
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val episodes = doc.select(".sliderIndex_002 .ultEpsContainerItem a")

        val animes = episodes.map { element ->
            SAnime.create().apply {
                val epNum = element.selectFirst(".epNum")!!.text()
                val epName = element.selectFirst(".epNome")!!.text()

                title = "$epName - $epNum"
                url = element.attr("href")
                thumbnail_url = element.selectFirst("img")
                    ?.attr("abs:src")

                initialized = false
            }
        }

        return AnimesPage(animes, false)
    }

    // No filters implemented
    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    // Anime Details: Extract anime data from script tag in page
    private fun extractAnimeData(document: Document): JSONObject? {
        return runCatching {
            val scriptContent = document.select("script")
                .map { it.data() }
                .firstOrNull { it.contains("animeData") }
                ?: return null

            val startToken = "\\\"animeData\\\":{"
            val startIdx = scriptContent.indexOf(startToken)
            if (startIdx == -1) return null

            val jsonStart = startIdx + startToken.length - 1
            val endIdx = scriptContent.indexOf("]}", jsonStart) + 2

            val fragment = scriptContent.substring(jsonStart, endIdx)

            val cleanedJson = fragment
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")

            JSONObject(cleanedJson)
        }.getOrNull()
    }

    // Parse core anime data from JSON
    private fun parseAnimeCore(json: JSONObject): CoreAnimeData {
        val title = json.optString("name")

        val altTitle = json.optString("nameOriginal")
            .takeIf { it.isNotBlank() }

        val description = json.optString("sinopse")
            .trim()
            .ifBlank { "Sinopse não disponível." }

        val status = when {
            json.optString("diaLancamento").isNotBlank() ->
                SAnime.ONGOING
            json.optInt("episodios") > 0 ->
                SAnime.COMPLETED
            else ->
                SAnime.UNKNOWN
        }

        return CoreAnimeData(
            title = title,
            altTitle = altTitle,
            description = description,
            status = status,
        )
    }

    // Fallback: parse anime details from meta tags
    private fun parseAnimeFromMeta(document: Document): SAnime = SAnime.create().apply {
        title = document.select("meta[property=og:title]").attr("content")
        description = document.select("meta[name=description]").attr("content")
        thumbnail_url = document.select("meta[property=og:image]").attr("content")
        initialized = true
    }

    // Data class for core anime information
    private data class CoreAnimeData(
        val title: String,
        val altTitle: String?,
        val description: String,
        val status: Int,
    )

    // Main anime details parser
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        val json = extractAnimeData(document)
            ?: return parseAnimeFromMeta(document)

        val core = parseAnimeCore(json)

        return SAnime.create().apply {
            title = core.title
            artist = core.altTitle
            description = core.description
            status = core.status

            thumbnail_url = json.optString("poster")
                .takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/w500$it" }
                ?: document.select("meta[property=og:image]").attr("content")

            initialized = true
        }
    }

    // Alternative JSON parser (not used in current implementation)
    private fun parseAnimeFromJson(
        json: JSONObject,
        document: Document,
    ): SAnime = SAnime.create().apply {
        title = json.optString("name")

        // Studio
        author = json.optJSONObject("Studio")
            ?.optString("name")

        // Original title goes to "artist" field (Tachiyomi standard)
        artist = json.optString("nameOriginal").takeIf { it.isNotBlank() }

        val year = json.optInt("ano").takeIf { it > 0 }
        val synopsis = json.optString("sinopse")

        description = buildString {
            if (year != null) append("Ano: $year\n\n")
            append(synopsis)
        }

        genre = json.optJSONArray("Animegenero")
            ?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.getJSONObject(i)
                        .optJSONObject("Genero")
                        ?.optString("name")
                }.joinToString(", ")
            }

        status = when (json.optString("status").lowercase()) {
            "ended", "finalizado", "completo" -> SAnime.COMPLETED
            "releasing", "em lançamento", "andamento" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }

        thumbnail_url = json.optString("poster")
            .takeIf { it.isNotBlank() }
            ?.let { "https://image.tmdb.org/t/p/w500$it" }
            ?: document.select("meta[property=og:image]").attr("content")

        initialized = true
    }

    // Episodes: Parse episode list from JSON data
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val json = extractAnimeData(document) ?: return emptyList()
        val episodes = json.optJSONArray("Episode") ?: return emptyList()

        return (0 until episodes.length())
            .map { i ->
                val obj = episodes.getJSONObject(i)
                SEpisode.create().apply {
                    name = obj.optString("name")
                    episode_number = obj.optDouble("episodeNumber").toFloat()
                    url = "/episodio/${obj.optString("slug")}"
                }
            }
            .sortedByDescending { it.episode_number }
    }

    // Videos: Video list request
    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    // Parse video list from episode page
    override fun videoListParse(response: Response): List<Video> {
        val html = response.bodyString()
        val videoList = mutableListOf<Video>()
        val extractor = BloggerExtractor(client)

        // 1. Clean HTML: remove JSON escapes for cleaner URLs
        val cleanHtml = html.replace("\\/", "/")
            .replace("\\u0026", "&")

        val browserUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:146.0) Gecko/20100101 Firefox/146.0"
        val googleHeaders = headersBuilder()
            .set("User-Agent", browserUserAgent)
            .set("Referer", "https://youtube.googleapis.com/")
            .build()

        // 2. Enhanced regex to capture links anywhere in JSON
        val legRegex = """player_leg"\s*:\s*"([^"]+)""".toRegex()
        val dubRegex = """player_dub"\s*:\s*"([^"]+)""".toRegex()

        val legMatch = legRegex.find(cleanHtml)?.groupValues?.get(1)
        val dubMatch = dubRegex.find(cleanHtml)?.groupValues?.get(1)

        // Helper function to add videos from URL
        fun addVideos(url: String, prefix: String) {
            if (url.isEmpty() || !url.contains("blogger.com")) return

            runCatching {
                runBlocking { extractor.videosFromUrl(url, googleHeaders) }
                    .forEach { video ->
                        videoList.add(
                            Video(
                                video.videoUrl,
                                "$prefix: ${video.videoTitle}",
                                video.videoUrl,
                                googleHeaders,
                            ),
                        )
                    }
            }
        }

        // Process Legendado (subtitled) and Dublado (dubbed) streams
        legMatch?.let { addVideos(it, "Legendado") }
        dubMatch?.let { addVideos(it, "Dublado") }

        // 3. Fallback: if keys change, try to capture any loose blogger links
        if (videoList.isEmpty()) {
            val fallbackRegex = """https?://www\.blogger\.com/video\.g\?token=[a-zA-Z0-9_-]+""".toRegex()
            fallbackRegex.findAll(cleanHtml)
                .map { it.value }
                .distinct()
                .take(2)
                .forEach { addVideos(it, "Player") }
        }

        return videoList
    }
}
