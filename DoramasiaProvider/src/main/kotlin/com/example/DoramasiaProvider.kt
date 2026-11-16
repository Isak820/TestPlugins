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
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl/explorar/?orden=actualizados" else "$mainUrl/explorar/page/$page/?orden=actualizados"
        val results = searchResults(url)
        if (results.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(listOf(HomePageList("Doramas actualizados", results)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return searchResults("$mainUrl/explorar/?buscar=${URLEncoder.encode(query.trim(), "UTF-8")}")
    }

    private suspend fun searchResults(url: String): List<SearchResponse> {
        val document = app.get(url).document
        return document.select("article.da-browser-card, .da-browser-card, article").mapNotNull { card ->
            val anchor = card.selectFirst("a[href]") ?: card.selectFirst("a[data-href]") ?: return@mapNotNull null
            val link = resolveUrl(url, anchor.attr("href").ifBlank { anchor.attr("data-href") }) ?: return@mapNotNull null
            val image = card.selectFirst("div.da-browser-poster img, img")
            val title = sequenceOf(
                image?.attr("alt"),
                card.selectFirst(".da-browser-caption strong")?.text(),
                card.selectFirst("h3")?.text(),
                card.selectFirst(".title")?.text(),
                anchor.attr("title")
            ).firstOrNull { !it.isNullOrBlank() }?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

            newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                posterUrl = imageUrl(image, url)
            }
        }
    }

    private fun resolveUrl(baseUrl: String, rawUrl: String?): String? {
        val value = rawUrl?.trim() ?: return null
        if (value.isEmpty() || value.startsWith("#") || value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) || value.startsWith("about:", true) ||
            value.startsWith("blob:", true)
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
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("src"),
            image.attr("data-original"),
            image.attr("srcset").substringBefore(',').trim().substringBefore(' ')
        ).firstOrNull { it.isNotBlank() } ?: return null
        return resolveUrl(baseUrl, raw)
    }

    override suspend fun load(url: String): LoadResponse {
        var document = app.get(url).document
        var currentUrl = url

        if (url.contains("/episodios/")) {
            val doramaLink = document.selectFirst(
                "a[href*='/doramas/'], a[href*='/drama/'], a[href*='doramasia.net/doramas/']"
            )?.attr("href")
            val resolvedSeries = resolveUrl(url, doramaLink)
            if (resolvedSeries != null) {
                currentUrl = resolvedSeries
                document = app.get(currentUrl).document
            }
        }

        val title = sequenceOf(
            document.selectFirst(".da-tv-overview h1")?.text(),
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property='og:title']")?.attr("content"),
            document.selectFirst("meta[property=og:title]")?.attr("content")
        ).firstOrNull { !it.isNullOrBlank() }?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException()

        val poster = sequenceOf(
            imageUrl(document.selectFirst(".da-tv-poster img, .da-tv-poster picture img, img"), currentUrl),
            resolveUrl(currentUrl, document.selectFirst("meta[property='og:image']")?.attr("content")),
            resolveUrl(currentUrl, document.selectFirst("meta[property=og:image]")?.attr("content"))
        ).firstOrNull { !it.isNullOrBlank() }

        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.select(".da-tv-synopsis p, .sinopsis p, .description p, p")
                .firstOrNull { it.text().isNotBlank() }
                ?.text()
                ?.takeIf { it.isNotBlank() }

        val tags = document.select(".da-tv-tags .da-genre-link, .tags a, .genre a")
            .mapNotNull { it.text().takeIf { text -> text.isNotBlank() } }

        val episodeSelector = "button.da-server-button, a.da-episode-row, a.da-episode-card, a[href*='/episodios/'], a[data-href*='/episodios/'], [data-url*='/episodios/']"
        val episodes = document.select(episodeSelector).mapNotNull { node ->
            val href = sequenceOf(node.attr("href"), node.attr("data-href"), node.attr("data-url"))
                .firstOrNull { !it.isNullOrBlank() } ?: return@mapNotNull null
            val epUrl = resolveUrl(currentUrl, href) ?: return@mapNotNull null
            val epName = node.selectFirst(".da-episode-name")?.text()?.takeIf { it.isNotBlank() }
                ?: node.text().takeIf { it.isNotBlank() } ?: "Episodio"

            val regex = Regex("""(?i)(?:s\s*(\d+)\s*[-.\s]*e\s*(\d+)|(?:cap(?:itulo)?|episodio|episode)\s*(\d+)|((\d+)x(\d+)))""").find(epName)
                ?: Regex("""(?i)(?:s\s*(\d+)\s*[-.\s]*e\s*(\d+)|((\d+)x(\d+)))""").find(epUrl)
                ?: Regex("""(?i)(?:cap(?:itulo)?|episodio|episode)\s*(\d+)""").find(epName)

            val seasonNum = regex?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: regex?.groupValues?.getOrNull(4)?.toIntOrNull()
            val episodeNum = regex?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: regex?.groupValues?.getOrNull(3)?.toIntOrNull()
                ?: regex?.groupValues?.getOrNull(6)?.toIntOrNull()
                ?: regex?.groupValues?.getOrNull(5)?.toIntOrNull()

            val episodePoster = imageUrl(node.selectFirst("img"), currentUrl)

            newEpisode(epUrl) {
                name = epName
                season = seasonNum
                episode = episodeNum
                if (episodePoster != null) {
                    posterUrl = episodePoster
                }
            }
        }.distinctBy { it.data }.sortedWith(
            compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE }
        )

        return newTvSeriesLoadResponse(title, currentUrl, TvType.TvSeries, episodes) {
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

    private fun isTrackingUrl(url: String): Boolean {
        val host = try { URI(url).host?.lowercase() } catch (_: Exception) { null } ?: return true
        return host == "mc.yandex.ru" || host.endsWith(".yandex.ru") ||
            host == "google-analytics.com" || host.endsWith(".google-analytics.com") ||
            host == "googletagmanager.com" || host.endsWith(".googletagmanager.com")
    }

    private fun isKnownExtractorHost(url: String): Boolean {
        val host = try { URI(url).host?.lowercase() } catch (_: Exception) { null } ?: return false
        return host == "ok.ru" || host.endsWith(".ok.ru") ||
            host == "odnoklassniki.ru" || host.endsWith(".odnoklassniki.ru") ||
            host == "vidmoly.org" || host.endsWith(".vidmoly.org") ||
            host == "vidmoly.me" || host.endsWith(".vidmoly.me") ||
            host == "voe.sx" || host.endsWith(".voe.sx") ||
            host == "vidara.so" || host.endsWith(".vidara.so") ||
            host == "vidhide.com" || host.endsWith(".vidhide.com") ||
            host == "uqload.vc" || host.endsWith(".uqload.vc") ||
            host == "uqload.com" || host.endsWith(".uqload.com") ||
            host == "streamwish.to" || host.endsWith(".streamwish.to") ||
            host == "filemoon.sx" || host.endsWith(".filemoon.sx") ||
            host == "dood.la" || host.endsWith(".dood.la") ||
            host == "fembed.co" || host.endsWith(".fembed.co")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val prioritized = linkedSetOf<String>()
        val fallback = linkedSetOf<String>()

        fun addCandidate(raw: String?, preferred: Boolean = false) {
            if (raw.isNullOrBlank()) return
            val value = normalizePlayerText(raw.trim())
            if (value.startsWith("about:", true) || value.startsWith("javascript:", true) ||
                value.startsWith("data:", true) || value.startsWith("blob:", true)
            ) return
            val resolved = resolveUrl(data, value) ?: return
            if (isTrackingUrl(resolved)) return
            val host = try { URI(resolved).host?.lowercase() } catch (_: Exception) { null }
            val path = try { URI(resolved).path.orEmpty().lowercase() } catch (_: Exception) { "" }
            val isPlayerLike = isKnownExtractorHost(resolved) ||
                path.contains("embed") || path.contains("player") || path.contains("video") ||
                path.contains("e/") ||
                resolved.contains(".m3u8", true) || resolved.contains(".mp4", true) || resolved.contains(".mpd", true)
            if (!isPlayerLike) return
            if (preferred || (host != null && (host.contains("ok.ru") || host.contains("vidmoly") || host.contains("voe") || host.contains("vidara") || host.contains("uqload") || host.contains("vidhide") || host.contains("filemoon") || host.contains("streamwish") || host.contains("dood") || host.contains("fembed")))) {
                prioritized.add(resolved)
            } else {
                fallback.add(resolved)
            }
        }

        document.select(
            "button.da-server-button, .da-server-button, iframe, video, source, embed, [data-player-src], [data-initial-src], [data-video], [data-url], [data-src], [data-embed], a[href]"
        ).forEach { element ->
            val preferred = element.tagName() == "button" ||
                element.classNames().any { it.contains("server", true) || it.contains("player", true) } ||
                element.id().contains("server", true) || element.id().contains("player", true)
            listOf(
                "src", "data-src", "data-lazy-src", "data-player-src", "data-initial-src",
                "data-video", "data-url", "data-original", "data-embed", "data-src", "href"
            ).forEach { attr -> addCandidate(element.attr(attr), preferred) }
        }

        val scriptText = normalizePlayerText(document.select("script").joinToString("\n") { it.data() })
        val rawText = normalizePlayerText(document.html())
        val playerHostPattern = Regex(
            """(?i)(?:https?:)?//(?:www\.)?(?:[a-z0-9-]+\.)*(?:ok\.ru|odnoklassniki\.ru|vidmoly\.(?:org|me)|voe\.sx|vidara\.so|uqload\.(?:vc|com)|vidhide\.(?:com|pro)|filemoon\.sx|streamwish\.to|dood\.la|fembed\.co)[^\s"'<>\\]+"""
        )
        val mediaPattern = Regex(
            """(?i)(https?:)?//[^\s"'<>]+\.(?:m3u8|mp4|mpd)(?:\?[^\s"'<>]*)?"""
        )

        playerHostPattern.findAll(scriptText).forEach { addCandidate(it.value, true) }
        playerHostPattern.findAll(rawText).forEach { addCandidate(it.value, true) }
        mediaPattern.findAll(scriptText).forEach { addCandidate(it.value, true) }
        mediaPattern.findAll(rawText).forEach { addCandidate(it.value, true) }

        var found = false
        for (source in prioritized + fallback) {
            try {
                if (loadExtractor(source, data, subtitleCallback, callback)) found = true
            } catch (_: Exception) {
                // Siguientes candidatos.
            }
        }
        return found
    }
}

