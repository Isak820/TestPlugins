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
        val url = if (page == 1) "$mainUrl/explorar/?orden=actualizados"
        else "$mainUrl/explorar/page/$page/?orden=actualizados"
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
            val link = resolveUrl(url, anchor.attr("href").ifBlank { anchor.attr("data-href") })
                ?: return@mapNotNull null
            val image = card.selectFirst(".da-browser-poster img, img")
            val title = sequenceOf(
                image?.attr("alt"),
                card.selectFirst(".da-browser-caption strong")?.text(),
                card.selectFirst("h3")?.text(),
                card.selectFirst(".title")?.text(),
                anchor.attr("title")
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
            image.attr("data-src"), image.attr("data-lazy-src"), image.attr("data-original"),
            image.attr("src"),
            image.attr("srcset").substringBefore(',').trim().substringBefore(' ')
        ).firstOrNull { it.isNotBlank() } ?: return null
        return resolveUrl(baseUrl, raw)
    }

    private fun elementImageUrl(element: Element?, baseUrl: String): String? {
        if (element == null) return null
        imageUrl(if (element.tagName() == "img") element else element.selectFirst("img"), baseUrl)?.let { return it }
        val styled = if (element.hasAttr("style")) element else element.selectFirst("[style*=background-image]")
        val style = styled?.attr("style") ?: return null
        val raw = Regex("""(?i)background-image\s*:\s*url\([\"']?([^\"')]+)""")
            .find(style)?.groupValues?.getOrNull(1) ?: return null
        return resolveUrl(baseUrl, raw)
    }

    private fun episodeNumber(text: String): Pair<Int?, Int?> {
        val match = Regex(
            """(?i)(?:s\s*(\d+)\s*[-.\s]*e\s*(\d+)|(?:cap(?:itulo)?|episodio|episode)\s*(\d+)|((\d+)x(\d+)))"""
        ).find(text) ?: return null to null
        val season = match.groupValues.getOrNull(1)?.toIntOrNull()
            ?: match.groupValues.getOrNull(5)?.toIntOrNull()
        val number = match.groupValues.getOrNull(2)?.toIntOrNull()
            ?: match.groupValues.getOrNull(3)?.toIntOrNull()
            ?: match.groupValues.getOrNull(6)?.toIntOrNull()
        return season to number
    }

    private fun episodePath(url: String): String? = try {
        URI(url).path?.takeIf { it.contains("/episodios/") }
    } catch (_: Exception) { null }

    override suspend fun load(url: String): LoadResponse {
        var document = app.get(url).document
        var currentUrl = url
        if (url.contains("/episodios/")) {
            val seriesHref = document.selectFirst(
                "a[href*='/doramas/'], a[href*='/drama/'], a[href*='doramasia.net/doramas/']"
            )?.attr("href")
            val seriesUrl = resolveUrl(url, seriesHref)
            if (seriesUrl != null) {
                currentUrl = seriesUrl
                document = app.get(currentUrl).document
            }
        }

        val title = sequenceOf(
            document.selectFirst(".da-tv-overview h1")?.text(),
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property='og:title']")?.attr("content")
        ).firstOrNull { !it.isNullOrBlank() }?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException()

        // Deliberately avoid og:image and arbitrary page images: Doramasia may put its logo there.
        val poster = elementImageUrl(
            document.selectFirst(".da-tv-poster img, .da-tv-poster picture, .da-tv-poster, [class*=poster] img"),
            currentUrl
        )
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.select(".da-tv-synopsis p, .sinopsis p, .description p")
                .firstOrNull { it.text().isNotBlank() }?.text()?.takeIf { it.isNotBlank() }
        val tags = document.select(".da-tv-tags .da-genre-link, .tags a, .genre a")
            .mapNotNull { it.text().trim().takeIf { text -> text.isNotBlank() } }

        // Episodes are distinct from the server buttons. Accept real episode rows and actual
        // /episodios/ links only; ignore buttons and unrelated data-url elements.
        val episodeNodes = document.select(
            "a.da-episode-row, .da-episode-row a, a.da-episode-card, " +
                "a[href*='/episodios/'], [data-href*='/episodios/'], [data-url*='/episodios/']"
        )
        val episodeMap = LinkedHashMap<String, Element>()
        episodeNodes.forEach { node ->
            val href = sequenceOf(
                node.attr("href"), node.attr("data-href"), node.attr("data-url"),
                node.selectFirst("a[href*='/episodios/']")?.attr("href"),
                node.selectFirst("a[data-href*='/episodios/']")?.attr("data-href")
            ).firstOrNull { !it.isNullOrBlank() } ?: return@forEach
            val episodeUrl = resolveUrl(currentUrl, href) ?: return@forEach
            if (episodePath(episodeUrl) == null) return@forEach
            episodeMap.putIfAbsent(episodeUrl, node)
        }

        // Some Doramasia templates serialize episode links in inline scripts rather than anchors.
        val normalizedHtml = normalizePlayerText(document.html())
        val episodeUrlRegex = Regex(
            """(?i)(?:https?://(?:www\.)?doramasia\.net)?(/episodios/[a-z0-9_-]+/?)(?:[\"'\s<>]|$)"""
        )
        episodeUrlRegex.findAll(normalizedHtml).forEach { match ->
            val episodeUrl = resolveUrl(currentUrl, match.groupValues[1]) ?: return@forEach
            if (episodePath(episodeUrl) != null) episodeMap.putIfAbsent(episodeUrl, document.body())
        }

        val episodes = episodeMap.map { (episodeUrl, node) ->
            val name = sequenceOf(
                node.selectFirst(".da-episode-name")?.text(),
                node.selectFirst(".da-episode-title, .episode-title, .title, h3, h4")?.text(),
                node.selectFirst("img[alt]")?.attr("alt"),
                node.attr("aria-label"), node.attr("title"), node.text()
            ).firstOrNull { !it.isNullOrBlank() }?.trim()?.takeIf { it.isNotBlank() }
                ?: episodePath(episodeUrl)?.substringAfterLast('/')?.replace('-', ' ') ?: "Episodio"
            val (season, number) = episodeNumber("$name $episodeUrl")
            val thumbnail = elementImageUrl(node, currentUrl)
            newEpisode(episodeUrl) {
                this.name = name
                this.season = season
                this.episode = number
                // Do not substitute the series artwork when an episode thumbnail is absent.
                if (thumbnail != null) this.posterUrl = thumbnail
            }
        }.sortedWith(
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

    private fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (_: Exception) { null }

    private fun normalizedOkRuUrl(url: String): String {
        val match = Regex(
            """^(https?://)(?:www\.|m\.)?ok\.ru/(?:video/|videoembed/)(\d+)/?(?:[?#].*)?$""",
            RegexOption.IGNORE_CASE
        ).find(url) ?: return url
        return "${match.groupValues[1]}ok.ru/videoembed/${match.groupValues[2]}"
    }

    private fun isKnownPlayerHost(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host == "ok.ru" || host.endsWith(".ok.ru") ||
            host == "odnoklassniki.ru" || host.endsWith(".odnoklassniki.ru") ||
            host == "vidmoly.org" || host.endsWith(".vidmoly.org") ||
            host == "vidmoly.me" || host.endsWith(".vidmoly.me") ||
            host == "voe.sx" || host.endsWith(".voe.sx") ||
            host == "vidara.so" || host.endsWith(".vidara.so") ||
            host == "uqload.vc" || host.endsWith(".uqload.vc") ||
            host == "uqload.com" || host.endsWith(".uqload.com") ||
            host == "vidhide.com" || host.endsWith(".vidhide.com") ||
            host == "vidhidepro.com" || host.endsWith(".vidhidepro.com") ||
            host == "streamwish.to" || host.endsWith(".streamwish.to") ||
            host == "filemoon.sx" || host.endsWith(".filemoon.sx") ||
            host == "dood.la" || host.endsWith(".dood.la") ||
            host == "fembed.co" || host.endsWith(".fembed.co")
    }

    private fun isDirectMedia(url: String): Boolean {
        val path = try { URI(url).path.orEmpty().lowercase() } catch (_: Exception) { "" }
        return path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".mpd")
    }

    private fun isTrackingUrl(url: String): Boolean {
        val host = hostOf(url) ?: return true
        return host == "mc.yandex.ru" || host.endsWith(".yandex.ru") ||
            host == "google-analytics.com" || host.endsWith(".google-analytics.com") ||
            host == "googletagmanager.com" || host.endsWith(".googletagmanager.com")
    }

    private fun isSelectedServer(element: Element): Boolean {
        val classes = element.classNames()
        return element.attr("aria-pressed").equals("true", true) ||
            element.attr("aria-selected").equals("true", true) ||
            element.attr("data-selected").equals("true", true) ||
            classes.any { it.equals("active", true) || it.equals("selected", true) || it.equals("current", true) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val preferred = linkedSetOf<String>()
        val otherCandidates = linkedSetOf<String>()

        fun addCandidate(raw: String?, prioritize: Boolean = false) {
            if (raw.isNullOrBlank()) return
            var value = normalizePlayerText(raw.trim()).trimEnd(',', ';', ')', ']', '}', '\\')
            if (value.startsWith("//")) value = "https:$value"
            if (value.startsWith("about:", true) || value.startsWith("javascript:", true) ||
                value.startsWith("data:", true) || value.startsWith("blob:", true)
            ) return
            val resolved = resolveUrl(data, value)?.let(::normalizedOkRuUrl) ?: return
            if (isTrackingUrl(resolved) || (!isKnownPlayerHost(resolved) && !isDirectMedia(resolved))) return
            if (prioritize) preferred.add(resolved) else otherCandidates.add(resolved)
        }

        // Doramasia's observed server controls. Prefer the selected button but retain other valid
        // server embeds as fallbacks. Never interpret these controls as episodes.
        document.select("button.da-server-button, .da-server-button").forEach { button ->
            val selected = isSelectedServer(button)
            listOf(
                "data-player-src", "data-initial-src", "data-src", "data-url", "data-embed",
                "data-video", "src", "href", "value"
            ).forEach { attr -> addCandidate(button.attr(attr), selected) }
        }

        // Real player frames/lazy player attributes only. about:blank is rejected above.
        document.select(
            "iframe[src], iframe[data-src], iframe[data-lazy-src], [data-player-src], " +
                "[data-initial-src], [data-embed], [data-video]"
        ).forEach { element ->
            val isPlayerContext = element.tagName() == "iframe" ||
                element.classNames().any { it.contains("player", true) || it.contains("server", true) } ||
                element.id().contains("player", true) || element.id().contains("server", true)
            val priority = isPlayerContext && isSelectedServer(element)
            listOf(
                "src", "data-src", "data-lazy-src", "data-player-src", "data-initial-src",
                "data-embed", "data-video", "data-url"
            ).forEach { attr -> addCandidate(element.attr(attr), priority) }
        }

        // Restrict script scanning to recognized embed domains or explicit media-source fields.
        val scriptText = normalizePlayerText(document.select("script").joinToString("\n") { it.data() })
        val htmlText = normalizePlayerText(document.html())
        val playerUrlPattern = Regex(
            """(?i)(?:https?:)?//(?:www\.)?(?:[a-z0-9-]+\.)*(?:ok\.ru|odnoklassniki\.ru|vidmoly\.(?:org|me)|voe\.sx|vidara\.so|uqload\.(?:vc|com)|vidhide(?:pro)?\.com|streamwish\.to|filemoon\.sx|dood\.la|fembed\.co)/[^\s"'<>\\]+"""
        )
        val directMediaPattern = Regex(
            """(?i)(?:https?:)?//[^\s"'<>\\]+\.(?:m3u8|mp4|mpd)(?:\?[^\s"'<>\\]*)?"""
        )
        val keyedMediaPattern = Regex(
            """(?i)[\"']?(?:file|src|url|source|embed|player)[\"']?\s*[:=]\s*[\"']((?:https?:)?//[^\"'\s<>]+)[\"']"""
        )
        for (text in listOf(scriptText, htmlText)) {
            playerUrlPattern.findAll(text).forEach { addCandidate(it.value) }
            directMediaPattern.findAll(text).forEach { addCandidate(it.value) }
            keyedMediaPattern.findAll(text).forEach { addCandidate(it.groupValues.getOrNull(1)) }
        }

        var emittedLinks = 0
        val countingCallback: (ExtractorLink) -> Unit = { link ->
            emittedLinks++
            callback(link)
        }
        for (source in preferred + otherCandidates) {
            try {
                // The native Cloudstream Odnoklassniki extractor expects the real videoembed URL.
                // It handles OK.ru's current media response and supplies its required headers.
                loadExtractor(source, data, subtitleCallback, countingCallback)
            } catch (_: Exception) {
                // Keep trying other servers if this host or embed is unavailable.
            }
        }
        return emittedLinks > 0
    }
}
