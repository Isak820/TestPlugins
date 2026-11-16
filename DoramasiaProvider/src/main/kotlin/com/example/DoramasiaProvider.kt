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
            image.attr("src"), image.attr("data-src"), image.attr("data-lazy-src"),
            image.attr("data-original"), image.attr("srcset").substringBefore(',').trim().substringBefore(' ')
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
            imageUrl(document.selectFirst(".da-tv-poster img, [class*=poster] img"), currentUrl),
            resolveUrl(currentUrl, document.selectFirst("meta[property='og:image']")?.attr("content")),
            resolveUrl(currentUrl, document.selectFirst("meta[property=og:image]")?.attr("content")),
            imageUrl(document.selectFirst("img"), currentUrl)
        ).firstOrNull { !it.isNullOrBlank() }

        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.select(".da-tv-synopsis p, .sinopsis p, .description p, p")
                .firstOrNull { it.text().isNotBlank() }
                ?.text()
                ?.takeIf { it.isNotBlank() }

        val tags = document.select(".da-tv-tags .da-genre-link, .tags a, .genre a")
            .mapNotNull { it.text().takeIf { text -> text.isNotBlank() } }

        val episodeRows = document.select(
            "a.da-episode-row, a.da-episode-card, a[href*='/episodios/'], a[data-href*='/episodios/'], [data-url*='/episodios/']"
        )

        val episodes = episodeRows.mapNotNull { node ->
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
            val episodePoster = imageUrl(node.selectFirst("img"), currentUrl) ?: poster

            newEpisode(epUrl) {
                name = epName
                season = seasonNum
                episode = episodeNum
                this.posterUrl = episodePoster
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

    private fun isKnownExtractorHost(url: String): Boolean {
        val host = try { URI(url).host?.lowercase() } catch (_: Exception) { null } ?: return false
        return host == "vidmoly.org" || host.endsWith(".vidmoly.org") ||
            host == "vidmoly.me" || host.endsWith(".vidmoly.me") ||
            host == "ok.ru" || host.endsWith(".ok.ru") ||
            host == "odnoklassniki.ru" || host.endsWith(".odnoklassniki.ru")
    }

    private fun isTrackingUrl(url: String): Boolean {
        val host = try { URI(url).host?.lowercase() } catch (_: Exception) { null } ?: return true
        return host == "mc.yandex.ru" || host.endsWith(".yandex.ru") ||
            host == "google-analytics.com" || host.endsWith(".google-analytics.com") ||
            host == "googletagmanager.com" || host.endsWith(".googletagmanager.com")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Resolve links at playback/download time so signed media URLs are not stored in episode data.
        val document = app.get(data).document
        val candidates = linkedSetOf<String>()

        fun addCandidate(raw: String?, isPlayerContainer: Boolean = false) {
            if (raw.isNullOrBlank()) return
            val value = normalizePlayerText(raw.trim())
            if (value.startsWith("about:", true) || value.startsWith("javascript:", true) ||
                value.startsWith("data:", true) || value.startsWith("blob:", true)
            ) return
            val resolved = resolveUrl(data, value) ?: return
            if (isTrackingUrl(resolved)) return

            val path = try { URI(resolved).path.orEmpty().lowercase() } catch (_: Exception) { "" }
            val looksLikeEmbed = isKnownExtractorHost(resolved) || isPlayerContainer ||
                path.contains("embed") || path.contains("player") || path.contains("video")
            if (looksLikeEmbed) candidates.add(resolved)
        }

        // Read iframe, lazy-load and server-button URLs. Ignore empty, about:blank and blob player values.
        document.select("iframe, [class*=player], [id*=player], [class*=server], [id*=server], [data-embed], [data-player], [data-player-src], [data-initial-src], [data-video], [data-url], [data-src]")
            .forEach { element ->
                val isPlayerContainer = element.tagName() == "iframe" ||
                    element.classNames().any { it.contains("player", true) || it.contains("server", true) || it.contains("embed", true) } ||
                    element.id().contains("player", true) || element.id().contains("server", true)
                listOf(
                    "src", "data-src", "data-lazy-src", "data-embed", "data-player", "data-player-src",
                    "data-initial-src", "data-video", "data-url", "data-server", "href"
                ).forEach { attr -> addCandidate(element.attr(attr), isPlayerContainer) }
            }

        // Some server choices are anchors; keep only links that look player-related or use a known host.
        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href")
            val text = "${anchor.text()} ${anchor.className()} ${anchor.id()}".lowercase()
            val isServerLink = listOf("server", "player", "embed", "video", "vidmoly", "ok.ru").any { text.contains(it) }
            addCandidate(href, isServerLink)
        }

        // Player URLs can be JSON-escaped in script bodies instead of visible iframe attributes.
        val scriptText = normalizePlayerText(document.select("script").joinToString("\n") { it.data() })
        val pageText = normalizePlayerText(document.html())
        val embedPattern = Regex(
            """(?i)(?:https?:)?//(?:www\.)?(?:[a-z0-9-]+\.)*(?:vidmoly\.(?:org|me)|ok\.ru|odnoklassniki\.ru)/[^\s"'<>\\]+"""
        )
        embedPattern.findAll(scriptText).forEach { addCandidate(it.value, true) }
        embedPattern.findAll(pageText).forEach { addCandidate(it.value, true) }

        // Do not set ExtractorLink.type manually: the host extractor supplies the right VIDEO/M3U8 type and headers.
        var found = false
        for (embedUrl in candidates) {
            try {
                if (loadExtractor(embedUrl, data, subtitleCallback, callback)) found = true
            } catch (_: Exception) {
                // Continue with any other player candidates on this episode.
            }
        }
        // A true result means an extractor actually produced a playable/downloadable link.
        return found
    }
}
