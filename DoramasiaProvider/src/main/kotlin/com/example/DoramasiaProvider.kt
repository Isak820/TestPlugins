package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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
        return document.select("article.da-browser-card, .da-browser-card, article").mapNotNull { card ->
            val link = sequenceOf(
                card.selectFirst("a[href]")?.attr("href"),
                card.selectFirst("a[data-href]")?.attr("data-href"),
                card.selectFirst("[data-url]")?.attr("data-url")
            ).firstOrNull { !it.isNullOrBlank() }?.let { resolveUrl(url, it) }
                ?: return@mapNotNull null
            val title = sequenceOf(
                card.selectFirst(".da-browser-caption strong")?.text(),
                card.selectFirst("h3")?.text(),
                card.selectFirst(".title")?.text(),
                card.selectFirst("a[href]")?.attr("title")
            ).firstOrNull { !it.isNullOrBlank() }?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val poster = card.selectFirst("img")?.let { imageUrl(it, url) }
            newTvSeriesSearchResponse(title, link, TvType.TvSeries) { posterUrl = poster }
        }
    }

    private fun resolveUrl(baseUrl: String, rawUrl: String?): String? {
        val value = rawUrl?.trim() ?: return null
        if (value.isEmpty() || value.startsWith("#") || value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) || value.startsWith("about:", true)
        ) return null
        return try {
            URI(baseUrl).resolve(URI(value)).toString()
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
        } catch (_: Exception) {
            fixUrl(value).takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
    }

    private fun imageUrl(image: org.jsoup.nodes.Element?, baseUrl: String): String? {
        if (image == null) return null
        val raw = sequenceOf(
            image.attr("data-src"), image.attr("data-lazy-src"), image.attr("src"),
            image.attr("data-original"), image.attr("srcset").substringBefore(',').trim().substringBefore(' ')
        ).firstOrNull { it.isNotBlank() } ?: return null
        return resolveUrl(baseUrl, raw)
    }

    override suspend fun load(url: String): LoadResponse {
        var document = app.get(url).document
        if (url.contains("/episodios/")) {
            val seriesHref = document.selectFirst("a[href*='/doramas/']")?.attr("href")
            val seriesUrl = resolveUrl(url, seriesHref)
            if (seriesUrl != null) document = app.get(seriesUrl).document
        }

        val title = sequenceOf(
            document.selectFirst(".da-tv-overview h1")?.text(),
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content")
        ).firstOrNull { !it.isNullOrBlank() }?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException()
        val poster = imageUrl(document.selectFirst(".da-tv-poster img"), url)
            ?: resolveUrl(url, document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.select(".da-tv-synopsis p, .sinopsis p, .description p")
            .joinToString("\n") { it.text() }.ifBlank { null }
        val tags = document.select(".da-tv-tags .da-genre-link, .tags a, .genre a")
            .map { it.text() }.filter { it.isNotBlank() }

        val episodes = document.select(
            "a.da-episode-card[href], a[href*='/episodios/'], a[data-href*='/episodios/'], [data-url*='/episodios/']"
        ).mapNotNull { node ->
            val href = sequenceOf(node.attr("href"), node.attr("data-href"), node.attr("data-url"))
                .firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
            val episodeUrl = resolveUrl(url, href) ?: return@mapNotNull null
            val episodeName = node.selectFirst(".da-episode-name")?.text()?.takeIf { it.isNotBlank() }
                ?: node.text().takeIf { it.isNotBlank() } ?: "Episodio"
            val episodeNumText = node.selectFirst(".da-ep-number")?.text().orEmpty()
            val numberMatch = Regex("""(\d+)x(\d+)""").find("$episodeNumText $episodeName $episodeUrl")
            val episodePoster = imageUrl(node.selectFirst("img"), url) ?: poster
            newEpisode(episodeUrl) {
                name = episodeName
                season = numberMatch?.groupValues?.get(1)?.toIntOrNull()
                episode = numberMatch?.groupValues?.get(2)?.toIntOrNull()
                posterUrl = episodePoster
            }
        }.distinctBy { it.data }.sortedWith(
            compareBy<Episode> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE }
        )

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val okRuSources = linkedSetOf<String>()
        val otherSources = linkedSetOf<String>()

        fun collect(raw: String?) {
            if (raw.isNullOrBlank()) return
            val normalized = normalizePlayerText(raw.trim())
            if (normalized.startsWith("about:", true) || normalized.startsWith("javascript:", true) ||
                normalized.startsWith("data:", true)
            ) return
            val resolved = resolveUrl(data, normalized) ?: return
            if (Regex("(?i)(^|//)(www\\.)?(ok\\.ru|odnoklassniki\\.ru)/").containsMatchIn(resolved)) {
                okRuSources.add(resolved)
            } else {
                otherSources.add(resolved)
            }
        }

        // The site's episodes use OK.ru. Check common iframe, lazy-load and server-button fields first.
        document.select("iframe, video, source, embed, [data-player-src], [data-initial-src], [data-video], [data-url], [data-src], a[href]")
            .forEach { element ->
                listOf(
                    "src", "data-src", "data-lazy-src", "data-player-src", "data-initial-src",
                    "data-video", "data-url", "data-original", "href"
                ).forEach { attribute -> collect(element.attr(attribute)) }
            }

        // Player URLs are sometimes JSON-escaped inside inline scripts rather than exposed as iframe attributes.
        val scriptText = normalizePlayerText(document.select("script").joinToString("\n") { it.data() })
        val okRuPattern = Regex("""(?i)(?:https?:)?//(?:www\.)?(?:ok\.ru|odnoklassniki\.ru)/(?:videoembed|video|live)/[^\s"'<>\\]+""")
        okRuPattern.findAll(scriptText).forEach { collect(it.value) }
        okRuPattern.findAll(normalizePlayerText(document.html())).forEach { collect(it.value) }

        var found = false
        // Prioritize OK.ru so Cloudstream does not waste time probing unrelated page URLs first.
        for (source in okRuSources + otherSources) {
            if (loadExtractor(source, data, subtitleCallback, callback)) found = true
        }
        return found
    }
}
