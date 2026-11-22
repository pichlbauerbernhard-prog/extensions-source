package eu.kanade.tachiyomi.extension.en.imagefap

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.attrOrNull
import keiyoushi.utils.firstInstance
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

@Source
abstract class ImageFap : KeiSource() {

    // ImageFap bans IPs that request pages too quickly; images on the CDN are not limited
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder =
        rateLimit(permits = 1, period = 1.seconds) { it.host == "www.imagefap.com" }

    override val supportsLatest get() = false

    override suspend fun getPopularManga(page: Int): MangasPage = getGalleryList(listUrl(page).build())

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = listUrl(page)
            .addQueryParameter("type", "1")
            .addQueryParameter("gen", "0")
            .addQueryParameter("search", query.trim())
            .addQueryParameter("sort", filters.firstInstance<SortFilter>().selected)
            .addQueryParameter("order", "0")
            .build()
        return getGalleryList(url)
    }

    // Page 0 and page 1 are both the first page
    private fun listUrl(page: Int) = "$baseUrl/gallery.php".toHttpUrl().newBuilder()
        .addQueryParameter("page", page.toString())

    private suspend fun getGalleryList(url: HttpUrl): MangasPage {
        val document = fetchDocument(url.toString())
        val seen = HashSet<String>()
        val mangas = document.select("a[href*=\"gallery.php?gid=\"]")
            .filter { it.selectFirst("img") == null && it.text().isNotBlank() }
            .mapNotNull { link ->
                val gid = galleryId(link.absUrl("href").toHttpUrl()) ?: return@mapNotNull null
                if (!seen.add(gid)) return@mapNotNull null
                SManga.create().apply {
                    url = gid
                    title = link.text().trim()
                    thumbnail_url = link.findThumbnail()
                }
            }
        val hasNext = document.selectFirst("a:matchesOwn(^\\s*:: next ::\\s*$)") != null
        return MangasPage(mangas, hasNext)
    }

    // A result is a row with the title, followed by rows with its thumbnails
    private fun Element.findThumbnail(): String? {
        var row: Element? = closest("tr") ?: return null
        repeat(4) {
            val current = row ?: return null
            current.selectFirst("img[src*=\"/images/mini/\"]")?.let { return it.absUrl("src") }
            row = current.nextElementSibling()
            if (row?.selectFirst("a[href*=\"gallery.php?gid=\"]:not(:has(img))") != null) return null
        }
        return null
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/gallery.php?gid=${manga.url}"

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/gallery.php?gid=${chapter.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!url.host.endsWith("imagefap.com")) return null
        val gid = galleryId(url) ?: return null
        val manga = SManga.create().apply {
            this.url = gid
            title = ""
        }
        return getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    private fun galleryId(url: HttpUrl): String? {
        url.queryParameter("gid")?.takeIf { it.all(Char::isDigit) }?.let { return it }
        val segments = url.pathSegments
        if (segments.size >= 2 && segments[0] in listOf("gallery", "pictures") && segments[1].all(Char::isDigit)) {
            return segments[1]
        }
        return null
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = fetchDocument(getMangaUrl(manga))
        val html = document.outerHtml()

        if (manga.title.isBlank()) {
            manga.title = document.selectFirst("#menubar font[size=4]")?.text()?.trim()
                ?: document.title().trim()
        }
        if (manga.thumbnail_url.isNullOrBlank()) {
            manga.thumbnail_url = document.selectFirst("a[href*=\"/photo/\"] img")?.absUrl("src")
        }
        manga.author = UPLOADER_REGEX.find(html)?.groupValues?.get(1)?.trim()
        manga.genre = (
            document.select("#cnt_cats a").map { it.text().trim() } +
                document.select("#cnt_tags a").map { it.text().trim() }
            ).filter { it.isNotEmpty() }.distinct().joinToString().ifEmpty { null }
        manga.description = (
            document.selectFirst("#cnt_description")?.text()
                ?: document.selectFirst("#gdesc_text")?.text()
            )?.trim()
        manga.status = SManga.COMPLETED
        manga.update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        manga.initialized = true

        val chapter = SChapter.create().apply {
            url = manga.url
            name = "Galerie"
            chapter_number = 1f
        }
        return SMangaUpdate(manga, listOf(chapter))
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val gid = chapter.url
        val document = fetchDocument(getChapterUrl(chapter))
        val firstImageId = document.selectFirst("[id^=img_ed_]")?.id()?.removePrefix("img_ed_")
            ?: document.selectFirst("a[href*=\"/photo/\"][name]")?.attr("name")
            ?: throw Exception("Keine Bilder in dieser Galerie gefunden")

        // The photo viewer loads the gallery's images in batches of 24
        val photoUrl = "$baseUrl/photo/$firstImageId/"
        val referer = "$photoUrl?pgid=&gid=$gid&page=0"
        val images = LinkedHashSet<String>()
        var idx = 0
        while (true) {
            val batch = fetchDocument("$photoUrl?gid=$gid&idx=$idx&partial=true", Fetch.XHR.from(referer))
            if (batch.selectFirst("input#is_empty[value=\"1\"]") != null) break
            val links = batch.select("ul.thumbs li a").ifEmpty { batch.select("a[href]") }
            val urls = links.mapNotNull { it.attrOrNull("original") ?: it.absUrl("href").takeIf(String::isNotEmpty) }
            val added = urls.count(images::add)
            if (urls.size < BATCH_SIZE || added == 0) break
            idx += urls.size
        }

        if (images.isEmpty()) throw Exception("Keine Bilder in dieser Galerie gefunden")
        return images.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    private suspend fun fetchDocument(url: String, fetch: Fetch = Fetch.DOCUMENT): Document {
        val response = client.get(url, fetch)
        // Too many requests send visitors to a captcha page that only a browser can solve
        if (response.request.url.encodedPath.contains("human-verification")) {
            response.close()
            throw Exception("ImageFap verlangt eine Bestätigung. Öffne die Quelle einmal in WebView und löse sie.")
        }
        return response.asJsoup()
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("Sortierung gilt nur für die Suche"),
        SortFilter(),
    )

    private class SortFilter :
        Filter.Select<String>(
            "Sortieren nach",
            SORT_OPTIONS.map { it.first }.toTypedArray(),
        ) {
        val selected get() = SORT_OPTIONS[state].second
    }

    companion object {
        private const val BATCH_SIZE = 24
        private val UPLOADER_REGEX = Regex("""picture gallery by (.+?) to see hottest""")
        private val SORT_OPTIONS = listOf(
            "Neueste" to "date",
            "Meiste Bilder" to "pics",
            "Beste Qualität" to "quality",
        )
    }
}
