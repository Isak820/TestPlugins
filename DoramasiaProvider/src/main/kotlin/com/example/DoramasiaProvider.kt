package com.example

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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
            listOf(
                HomePageList("Doramas actualizados", results)
            )
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        return searchResults("$mainUrl/explorar/?buscar=$encodedQuery")
    }

    private suspend fun searchResults(url: String): List<SearchResponse> {
        return app.get(url).document.select("article.da-browser-card").mapNotNull { card ->
            val link = card.selectFirst("a")?.attr("href")
                ?.takeIf { it.startsWith("$mainUrl/doramas/") || it.startsWith("/") }
                ?: return@mapNotNull null
            val title = card.selectFirst(".da-browser-caption strong")?.text()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val poster = card.selectFirst("img")?.attr("src")

            newTvSeriesSearchResponse(title, fixUrl(link), TvType.TvSeries) {
                posterUrl = poster?.takeIf { it.isNotBlank() }?.let(::fixUrl)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var document = app.get(url).document

        // If page is an episode link (/episodios/), find link back to dorama page
        if (url.contains("/episodios/")) {
            val doramaLink = document.selectFirst("a[href*='/doramas/']")?.attr("href")
            if (doramaLink != null) {
                document = app.get(fixUrl(doramaLink)).document
            }
        }

        val title = document.selectFirst(".da-tv-overview h1")?.text()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException()
        val poster = document.selectFirst(".da-tv-poster img")?.attr("src")
        val description = document.select(".da-tv-synopsis p")
            .joinToString("\n") { it.text() }
            .ifBlank { null }
        val tags = document.select(".da-tv-tags .da-genre-link").map { it.text() }

        val episodes = document.select("a.da-episode-card, a[href*='/episodios/']").mapNotNull { card ->
            val href = card.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val epUrl = fixUrl(href)
            val epName = card.selectFirst(".da-episode-name")?.text()
                ?: card.text().takeIf { it.isNotBlank() }
                ?: "Episodio"
            val epNumText = card.selectFirst(".da-ep-number")?.text()
            val epPoster = card.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }?.let(::fixUrl)

            val epRegex = Regex("""(\d+)x(\d+)""").find(epNumText ?: href)
            val seasonNum = epRegex?.groupValues?.get(1)?.toIntOrNull()
            val episodeNum = epRegex?.groupValues?.get(2)?.toIntOrNull()

            newEpisode(epUrl) {
                name = epName
                season = seasonNum
                episode = episodeNum
                posterUrl = epPoster
            }
        }.distinctBy { it.data }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
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
        val sources = mutableSetOf<String>()

        // Extract from data-player-src on server buttons
        document.select(".da-server-button, button[data-player-src]").forEach { button ->
            val src = button.attr("data-player-src").takeIf { it.isNotBlank() }
            if (src != null) sources.add(src)
        }

        // Extract from active player iframe
        document.select("iframe#da-active-player, iframe[data-initial-src]").forEach { iframe ->
            val initialSrc = iframe.attr("data-initial-src").takeIf { it.isNotBlank() }
            val src = iframe.attr("src").takeIf { it.isNotBlank() && it != "about:blank" }
            if (initialSrc != null) sources.add(initialSrc)
            if (src != null) sources.add(src)
        }

        // Fallback: search all iframes
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() && !it.startsWith("about:") }
            if (src != null) sources.add(src)
        }

        for (source in sources) {
            val fixedSource = fixUrl(source)
            loadExtractor(fixedSource, data, subtitleCallback, callback)
        }

        return sources.isNotEmpty()
    }
}