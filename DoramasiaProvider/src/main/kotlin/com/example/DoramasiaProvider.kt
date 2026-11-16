package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI
import java.net.URLEncoder

class DoramasiaProvider : MainAPI() {

    override var mainUrl = "https://doramasia.net"
    override var name = "Doramasia"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/explorar/?orden=actualizados"
        } else {
            "$mainUrl/explorar/page/$page/?orden=actualizados"
        }

        val results = searchResults(url)
        if (results.isEmpty()) throw ErrorLoadingException()

        return newHomePageResponse(
            listOf(HomePageList("Doramas actualizados", results))
        )
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
            ).firstOrNull { it.isNotBlank() }?.let { resolveUrl(url, it) }
                ?: return@mapNotNull null

            val title = sequenceOf(
                card.selectFirst(".da-browser-caption strong")?.text(),
                card.selectFirst("h3")?.text(),
                card.selectFirst(".title")?.text(),
                card.selectFirst("a[href]")?.attr("title")
            ).firstOrNull { !it.isNullOrBlank() }?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val poster = sequenceOf(
                card.selectFirst("img")?.attr("data-src"),
                card.selectFirst("img")?.attr("data-lazy-src"),
                card.selectFirst("img")?.attr("src"),
                card.selectFirst("[style]")?.attr("style")
            ).firstOrNull { !it.isNullOrBlank() }
                ?.let { extractImageUrl(it, url) }

            newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                posterUrl = poster
            }
        }
    }

    private fun resolveUrl(baseUrl: String, rawUrl: String?): String? {
        val value = rawUrl?.trim() ?: return null
        if (value.isEmpty() || value.startsWith("#") || value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) || value.startsWith("about:", true)
        ) return null

        return try {
            val resolved = URI(baseUrl).resolve(URI(value)).toString()
            if (resolved.startsWith("http://") || resolved.startsWith("https://")) resolved else null
        } catch (_: Exception) {
            fixUrl(value).takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
    }

    private fun extractImageUrl(raw: String?, baseUrl: String): String? {
        val value = raw?.trim() ?: return null
        val extracted = when {
            value.startsWith("http") -> value
            value.contains("data:image") -> null
            value.contains("url(") -> value.substringAfter("url(").substringBefore(")").trim('"', '\'')
            value.startsWith("//") -> "https:$value"
            else -> "$baseUrl/$value".replace("//", "/")
        } ?: return null

        return resolveUrl(baseUrl, extracted)
    }

    private fun extractImageFromElement(element: org.jsoup.nodes.Element?, baseUrl: String): String? {
        if (element == null) return null
        val candidates = sequenceOf(
            element.attr("data-src"),
            element.attr("data-lazy-src"),
            element.attr("src"),
            element.attr("data-original"),
            element.attr("srcset").substringBefore(',').trim().substringBefore(' ')
        )
        return candidates.firstOrNull { it.isNotBlank() }?.let { extractImageUrl(it, baseUrl) }
    }

    override suspend fun load(url: String): LoadResponse {
        var document = app.get(url).document

        if (url.contains("/episodios/")) {
            val doramaLink = document.selectFirst("a[href*='/doramas/'], a[href*='/drama/'], a[href*='doramasia.net/doramas/']")
                ?.attr("href")
            if (!doramaLink.isNullOrBlank()) {
                val resolved = resolveUrl(url, doramaLink)
                if (resolved != null) document = app.get(resolved).document
            }
        }

        val title = sequenceOf(
            document.selectFirst(".da-tv-overview h1")?.text(),
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property='og:title']")?.attr("content")
        ).firstOrNull { !it.isNullOrBlank() }?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException()

        val poster = sequenceOf(
            document.selectFirst(".da-tv-poster img")?.let { extractImageFromElement(it, url) },
            document.selectFirst("meta[property='og:image']")?.attr("content")?.let { resolveUrl(url, it) },
            document.selectFirst("img")?.let { extractImageFromElement(it, url) }
        ).firstOrNull { !it.isNullOrBlank() }

        val description = document.select(".da-tv-synopsis p, .sinopsis p, .description p, p").firstOrNull { it.text().isNotBlank() }?.text()
            ?.takeIf { it.isNotBlank() }
        val tags = document.select(".da-tv-tags .da-genre-link, .tags a, .genre a").mapNotNull { it.text().takeIf { text -> text.isNotBlank() } }

        val episodeLinks = linkedSetOf<Pair<String, String>>()
        val selectors = listOf(
            "a[href*='/episodios/']",
            "a[data-href*='/episodios/']",
            "[data-url*='/episodios/']",
            ".episode-item a[href]",
            ".episodios-lista a[href]",
            ".episodes a[href]",
            "a[href*='episodios']"
        )

        for (selector in selectors) {
            document.select(selector).forEach { node ->
                val href = sequenceOf(node.attr("href"), node.attr("data-href"), node.attr("data-url"))
                    .firstOrNull { !it.isNullOrBlank() }
                    ?: return@forEach
                val urlValue = resolveUrl(url, href) ?: return@forEach
                val epName = node.text().takeIf { it.isNotBlank() }
                    ?: node.attr("title").takeIf { it.isNotBlank() }
                    ?: "Episodio"
                episodeLinks += urlValue to epName
            }
        }

        val episodes = if (episodeLinks.isEmpty()) {
            val fallback = document.select("a[href]").mapNotNull { node ->
                val href = node.attr("href").takeIf { it.contains("/episodios/") } ?: return@mapNotNull null
                val resolved = resolveUrl(url, href) ?: return@mapNotNull null
                val text = node.text().takeIf { it.isNotBlank() } ?: "Episodio"
                resolved to text
            }
            fallback
        } else {
            episodeLinks
        }.distinctBy { it.first }.mapNotNull { (epUrl, epName) ->
            val epRegex = Regex("""(?i)(?:s|season\s*)(\d+)[^\d]*(?:e|episodio\s*)(\d+)""").find(epName)
                ?: Regex("""(\d+)x(\d+)""").find(epUrl)
                ?: Regex("""(\d+)""").find(epName)

            val seasonNum = epRegex?.groupValues?.getOrNull(1)?.toIntOrNull()
            val episodeNum = epRegex?.groupValues?.getOrNull(2)?.toIntOrNull()
            val posterUrl = document.select("img").firstOrNull { img ->
                val src = img.attr("src").ifBlank { img.attr("data-src") }
                src.contains("episodios") || src.contains("poster") || src.contains("cover")
            }?.let { extractImageFromElement(it, url) } ?: poster

            newEpisode(epUrl) {
                name = epName.takeIf { it.isNotBlank() } ?: "Episodio"
                season = seasonNum
                episode = episodeNum
                this.posterUrl = posterUrl
            }
        }.sortedWith(
            compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE }
        )

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val sources = linkedSetOf<String>()

        fun addSource(raw: String?) {
            raw ?: return
            if (raw.isBlank() || raw.startsWith("about:", true) || raw.startsWith("javascript:", true) || raw.startsWith("data:", true)) return
            resolveUrl(data, raw)?.let { sources += it }
        }

        document.select("iframe, video, source, embed, script").forEach { element ->
            listOf(
                "src", "data-src", "data-lazy-src", "data-video", "data-player-src",
                "data-url", "data-original", "value", "href"
            ).forEach { attr -> addSource(element.attr(attr)) }
        }

        document.select("[data-player-src], [data-initial-src], [data-video], [data-url], [data-src]").forEach { element ->
            listOf("data-player-src", "data-initial-src", "data-video", "data-url", "data-src").forEach { attr -> addSource(element.attr(attr)) }
        }

        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href")
            if (href.contains("embed") || href.contains("player") || href.contains("stream") || href.contains("m3u8") || href.contains("mp4") || href.contains("video")) {
                addSource(href)
            }
        }

        document.select("script").forEach { script ->
            val pattern = Regex("https?:\\/\\/[^\"'\\s]+")
            pattern.findAll(script.data()).forEach { match ->
                addSource(match.value)
            }
        }

        if (sources.isEmpty()) {
            val fallback = document.select("iframe").firstOrNull()?.attr("src")
            if (!fallback.isNullOrBlank()) addSource(fallback)
        }

        var found = false
        for (source in sources) {
            found = loadExtractor(source, data, subtitleCallback, callback) || found
        }
        return found || sources.isNotEmpty()
    }
}
