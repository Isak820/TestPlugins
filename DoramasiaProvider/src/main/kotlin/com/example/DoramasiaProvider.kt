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
        return app.get(url).document.select("article.da-browser-card").mapNotNull { card ->
            val link = card.selectFirst("a[href]")?.attr("href")
                ?.takeIf { it.startsWith("$mainUrl/doramas/") || it.startsWith("/") }
                ?: return@mapNotNull null
            val title = card.selectFirst(".da-browser-caption strong")?.text()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val poster = card.selectFirst("img")?.let { image ->
                image.attr("data-src").ifBlank {
                    image.attr("data-lazy-src").ifBlank { image.attr("src") }
                }
            }

            newTvSeriesSearchResponse(title, fixUrl(link), TvType.TvSeries) {
                posterUrl = poster?.takeIf { it.isNotBlank() }?.let(::fixUrl)
            }
        }
    }

    private fun resolvePageUrl(baseUrl: String, rawUrl: String): String? {
        val candidate = rawUrl.trim()
        if (candidate.isBlank() || candidate.startsWith("#") ||
            candidate.startsWith("javascript:", ignoreCase = true) ||
            candidate.startsWith("data:", ignoreCase = true) ||
            candidate.startsWith("about:", ignoreCase = true)
        ) return null

        return try {
            URI(baseUrl).resolve(URI(candidate)).toString()
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
        } catch (_: Exception) {
            fixUrl(candidate).takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
    }

    private fun imageUrl(image: org.jsoup.nodes.Element?): String? {
        if (image == null) return null
        val raw = sequenceOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("src"),
            image.attr("srcset").substringBefore(',').trim().substringBefore(' ')
        ).firstOrNull { it.isNotBlank() } ?: return null
        return resolvePageUrl(mainUrl, raw)
    }

    override suspend fun load(url: String): LoadResponse {
        var document = app.get(url).document

        if (url.contains("/episodios/")) {
            val doramaLink = document.selectFirst("a[href*='/doramas/']")?.attr("href")
            if (!doramaLink.isNullOrBlank()) {
                val seriesUrl = resolvePageUrl(url, doramaLink)
                if (seriesUrl != null) document = app.get(seriesUrl).document
            }
        }

        val title = document.selectFirst(".da-tv-overview h1")?.text()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException()

        val poster = imageUrl(document.selectFirst(".da-tv-poster img, meta[property=\"og:image\"]"))
            ?: document.selectFirst("meta[property=\"og:image\"]")?.attr("content")?.let { resolvePageUrl(url, it) }
        val description = document.select(".da-tv-synopsis p")
            .joinToString("\n") { it.text() }
            .ifBlank { null }
        val tags = document.select(".da-tv-tags .da-genre-link").map { it.text() }

        val episodeNodes = document.select(
            "a.da-episode-card[href], a[href*='/episodios/'], [data-href*='/episodios/'], [data-url*='/episodios/']"
        )
        val episodes = episodeNodes.mapNotNull { node ->
            val rawHref = sequenceOf(
                node.attr("href"), node.attr("data-href"), node.attr("data-url")
            ).firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
            val epUrl = resolvePageUrl(url, rawHref) ?: return@mapNotNull null
            val epName = node.selectFirst(".da-episode-name")?.text()
                ?.takeIf { it.isNotBlank() }
                ?: node.text().takeIf { it.isNotBlank() }
                ?: "Episodio"
            val epNumText = node.selectFirst(".da-ep-number")?.text().orEmpty()
            val epRegex = Regex("""(\d+)x(\d+)""").find("$epNumText $epName $epUrl")
            val episodePoster = imageUrl(node.selectFirst("img")) ?: poster

            newEpisode(epUrl) {
                name = epName
                season = epRegex?.groupValues?.get(1)?.toIntOrNull()
                episode = epRegex?.groupValues?.get(2)?.toIntOrNull()
                posterUrl = episodePoster
            }
        }.distinctBy { it.data }.sortedWith(
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

        fun addSource(raw: String) {
            resolvePageUrl(data, raw)?.let(sources::add)
        }

        document.select("[data-player-src], [data-initial-src], [data-video], [data-src]").forEach { element ->
            listOf("data-player-src", "data-initial-src", "data-video", "data-src")
                .map(element::attr)
                .filter(String::isNotBlank)
                .forEach(::addSource)
        }

        document.select("iframe[src], iframe[data-src], iframe[data-lazy-src]").forEach { iframe ->
            listOf("src", "data-src", "data-lazy-src").map(iframe::attr)
                .filter(String::isNotBlank)
                .forEach(::addSource)
        }

        document.select("a[href*='embed'], a[href*='player']").forEach { link ->
            addSource(link.attr("href"))
        }

        var extractorMatched = false
        for (source in sources) {
            extractorMatched = loadExtractor(source, data, subtitleCallback, callback) || extractorMatched
        }
        return extractorMatched
    }
}
