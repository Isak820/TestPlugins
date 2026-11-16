package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLEncoder

class DoramasiaProvider : MainAPI() {

    override var mainUrl = "https://doramasia.net"
    override var name = "Doramasia"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl/explorar/?orden=actualizados"
        else "$mainUrl/explorar/page/$page/?orden=actualizados"
        val results = searchResults(url)
        if (results.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(listOf(HomePageList("Doramas actualizados", results)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        return searchResults("$mainUrl/explorar/?buscar=$encodedQuery")
    }

    private suspend fun searchResults(url: String): List<SearchResponse> {
        val document = app.get(url).document
        return document.select("article.da-browser-card").mapNotNull { card ->
            val linkRaw = card.selectFirst("a[href]")?.attr("href")
                ?: card.selectFirst("a[data-href]")?.attr("data-href")
                ?: return@mapNotNull null
            val link = resolveUrl(url, linkRaw) ?: return@mapNotNull null
            val image = card.selectFirst("div.da-browser-poster img, img")
            val title = sequenceOf(
                image?.attr("alt"),
                card.selectFirst(".da-browser-caption strong")?.text(),
                card.selectFirst("h3")?.text(),
                card.selectFirst(".title")?.text(),
                card.selectFirst("a[href]")?.attr("title")
            ).firstOrNull { !it.isNullOrBlank() }?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                posterUrl = imageUrl(image, url)
            }
        }
    }

    private fun resolveUrl(baseUrl: String, rawUrl: String?): String? {
        val value = rawUrl?.trim() ?: return null
        if (value.isEmpty() || value.startsWith("#") || value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) || value.startsWith("about:", true) || value.startsWith("blob:", true)
        ) return null
        return try {
            URI(baseUrl).resolve(URI(value)).toString()
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
        } catch (_: Exception) {
            fixUrl(value).takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
    }

    private fun imageUrl(image: Element?, baseUrl: String): String? {
        if (image == null) return null
        val raw = sequenceOf(
            image.attr("src"), image.attr("data-src"), image.attr("data-lazy-src"),
            image.attr("data-original"), image.attr("srcset").substringBefore(',').trim().substringBefore(' ')
        ).firstOrNull { it.isNotBlank() } ?: return null
        return resolveUrl(baseUrl, raw)
    }

    override suspend fun load(url: String): LoadResponse {
        var document = app.get(url).document
        var seriesUrl = url
        if (url.contains("/episodios/")) {
            val seriesHref = document.selectFirst("a[href*='/doramas/']")?.attr("href")
            val resolvedSeries = resolveUrl(url, seriesHref)
            if (resolvedSeries != null) {
                seriesUrl = resolvedSeries
                document = app.get(resolvedSeries).document
            }
        }

        val title = sequenceOf(
            document.selectFirst(".da-tv-overview h1")?.text(),
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content")
        ).firstOrNull { !it.isNullOrBlank() }?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException()

        val poster = imageUrl(document.selectFirst(".da-tv-poster img, [class*=poster] img"), seriesUrl)
            ?: resolveUrl(seriesUrl, document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.select(".da-tv-synopsis p, .sinopsis p, .description p")
                .joinToString("\n") { it.text() }.ifBlank { null }
        val tags = document.select(".da-tv-tags .da-genre-link, .tags a, .genre a")
            .map { it.text() }.filter { it.isNotBlank() }

        // Doramasia's episode list uses a.da-episode-row and each row contains its own TMDB still.
        val episodes = document.select(
            "a.da-episode-row, a.da-episode-card[href], a[href*='/episodios/'], a[data-href*='/episodios/'], [data-url*='/episodios/']"
        ).mapNotNull { node ->
            val href = sequenceOf(node.attr("href"), node.attr("data-href"), node.attr("data-url"))
                .firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
            val episodeUrl = resolveUrl(seriesUrl, href) ?: return@mapNotNull null
            val episodeName = node.selectFirst(".da-episode-name")?.text()?.takeIf { it.isNotBlank() }
                ?: node.text().takeIf { it.isNotBlank() } ?: "Episodio"
            val numberSource = "${node.selectFirst(".da-ep-number")?.text().orEmpty()} $episodeName $episodeUrl"
            val seasonEpisode = Regex("""(?i)S\s*(\d+)\s*[.]?\s*E\s*(\d+)""").find(numberSource)
                ?: Regex("""(\d+)x(\d+)""").find(numberSource)
            val episodeOnly = Regex("""(?i)(?:cap[ií]tulo|episodio|episode)\s*(\d+)""")
                .find(numberSource)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val episodePoster = imageUrl(node.selectFirst("img"), seriesUrl)
            newEpisode(episodeUrl) {
                name = episodeName
                season = seasonEpisode?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: if (episodeOnly != null) 1 else null
                episode = seasonEpisode?.groupValues?.getOrNull(2)?.toIntOrNull() ?: episodeOnly
                // Don't substitute the series poster: preserve each episode's own still or leave it unset.
                posterUrl = episodePoster
            }
        }.distinctBy { it.data }.sortedWith(
            compareBy<Episode> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE }
        )

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes) {
            posterUrl = poster
            plot = description
            this.tags = tags
        }
    }

    private fun normalizePlayerText(value: String): String = Parser.unescapeEntities(value, false)
        .replace("\\/", "/")
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\u003d", "=", ignoreCase = true)
        .replace("\\u002f", "/", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)

    private fun isSupportedPlayerUrl(url: String): Boolean {
        val host = try { URI(url).host?.lowercase() } catch (_: Exception) { null } ?: return false
        return host == "vidmoly.org" || host.endsWith(".vidmoly.org") ||
            host == "vidmoly.me" || host.endsWith(".vidmoly.me") ||
            host == "ok.ru" || host.endsWith(".ok.ru") ||
            host == "odnoklassniki.ru" || host.endsWith(".odnoklassniki.ru")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val candidates = linkedSetOf<String>()

        fun addCandidate(raw: String?) {
            if (raw.isNullOrBlank()) return
            val normalized = normalizePlayerText(raw.trim())
            val resolved = resolveUrl(data, normalized) ?: return
            if (isSupportedPlayerUrl(resolved)) candidates.add(resolved)
        }

        // The player is dynamically populated, so ignore about:blank/blob and inspect server controls too.
        document.select("iframe[src], a[href], [data-url], [data-src], [data-embed], [data-player], [data-server], [data-video]")
            .forEach { element ->
                listOf("src", "href", "data-url", "data-src", "data-embed", "data-player", "data-server", "data-video")
                    .forEach { addCandidate(element.attr(it)) }
            }

        // Vidmoly and OK.ru embed links can be present as escaped strings in the episode HTML/scripts.
        val html = normalizePlayerText(document.html())
        val playerUrlPattern = Regex(
            """(?i)(?:https?:)?//(?:www\.)?(?:[a-z0-9-]+\.)*(?:vidmoly\.(?:org|me)|ok\.ru|odnoklassniki\.ru)/(?:[^\s"'<>\\]+)"""
        )
        playerUrlPattern.findAll(html).forEach { addCandidate(it.value) }

        var found = false
        for (source in candidates) {
            if (loadExtractor(source, data, subtitleCallback, callback)) found = true
        }
        return found
    }
}
