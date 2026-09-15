package com.geminireader.importer

import com.geminireader.data.Book
import com.geminireader.data.Chapter
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.zip.ZipInputStream

data class Imported(val book: Book, val cover: ByteArray? = null)

fun InputStream.readLimited(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

object TextImporters {
    private fun Element.local(name: String) = getAllElements().filter { it.tagName().substringAfter(':').equals(name, true) }
    fun zip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var size = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                if (!entry.isDirectory) {
                    require(entries.size < 10000) { "Archive contains too many entries" }
                    val data = stream.readLimited(32 * 1024 * 1024 + 1)
                    size += data.size
                    require(data.size <= 32 * 1024 * 1024 && size <= 256 * 1024 * 1024) { "Book archive is too large" }
                    entries[entry.name] = data
                }
            }
        }
        return entries
    }
    private fun decode(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> bytes.toString(Charsets.UTF_16LE).removePrefix("\uFEFF")
        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> bytes.toString(Charsets.UTF_16BE).removePrefix("\uFEFF")
        else -> bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
    }
    fun html(text: String): List<String> {
        val doc = Jsoup.parse(text)
        return htmlBlocks(doc).map { it.text().trim() }.filter { it.isNotBlank() }
            .ifEmpty { listOf(doc.body().text()).filter { it.isNotBlank() } }
    }
    private fun htmlBlocks(doc: org.jsoup.nodes.Document): List<Element> {
        doc.select("script,style,nav,noscript,svg").remove()
        val blocks = "p,h1,h2,h3,h4,h5,h6,li,blockquote,pre,td,div"
        return doc.body().select(blocks).filter { it.select(blocks).none { child -> child !== it } }
            .filter { it.text().isNotBlank() }
    }
    fun reflow(text: String): List<String> = text.replace("\r\n", "\n").replace('\r', '\n')
        .replace(Regex("(?<=\\p{L})-\\n(?=\\p{Ll})"), "")
        .split(Regex("\\n\\s*\\n")).map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() }

    fun parse(name: String, bytes: ByteArray): Imported {
        val format = name.substringAfterLast('.', "txt").lowercase()
        val title = name.substringBeforeLast('.').ifBlank { "Untitled" }
        val result = when (format) {
            "epub" -> epub(bytes)
            "docx" -> {
                val doc = Jsoup.parse(decode(zip(bytes)["word/document.xml"] ?: error("DOCX has no document.xml")), "", Parser.xmlParser())
                val paragraphs = doc.local("p").map { p -> p.local("t").joinToString("") { it.wholeText() } }.filter { it.isNotBlank() }
                Imported(Book(title = title, format = format, chapters = listOf(Chapter(title, paragraphs))))
            }
            "fb2" -> {
                val doc = Jsoup.parse(decode(bytes), "", Parser.xmlParser())
                val bookTitle = doc.local("book-title").firstOrNull()?.text().orEmpty().ifBlank { title }
                val author = doc.local("author").firstOrNull()?.text().orEmpty()
                val bodies = doc.local("body")
                val sections = bodies.flatMap { body -> body.children().filter { it.tagName().substringAfter(':') == "section" } }
                val chapters = (sections.ifEmpty { bodies }).mapIndexed { i, section ->
                    Chapter(section.local("title").firstOrNull()?.text().orEmpty().ifBlank { "Chapter ${i + 1}" }, section.getAllElements().filter { it.tagName().substringAfter(':') in listOf("p", "v", "subtitle") }.map { it.text() }.filter { it.isNotBlank() })
                }
                Imported(Book(title = bookTitle, author = author, format = format, chapters = chapters))
            }
            "html", "htm", "xhtml" -> {
                val text = decode(bytes)
                Imported(Book(title = Jsoup.parse(text).title().ifBlank { title }, format = format, chapters = listOf(Chapter(title, html(text)))))
            }
            "txt", "md", "markdown" -> {
                var text = decode(bytes)
                if (format != "txt") text = text.replace(Regex("(?m)^#{1,6}\\s+"), "").replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1").replace(Regex("[*_`]{1,3}"), "")
                val paragraphs = reflow(text)
                val chapters = mutableListOf<Chapter>()
                var heading = title
                var current = mutableListOf<String>()
                for (p in paragraphs) {
                    if (p.length < 100 && Regex("(?i)^(chapter|part|book)\\s+([IVXLCDM]+|\\d+)\\b.*").matches(p)) {
                        if (current.isNotEmpty()) chapters += Chapter(heading, current)
                        heading = p; current = mutableListOf()
                    }
                    current += p
                }
                if (current.isNotEmpty()) chapters += Chapter(heading, current)
                Imported(Book(title = title, format = format, chapters = chapters))
            }
            else -> error("Unsupported format .$format. Use EPUB, PDF, TXT, HTML, Markdown, FB2 or DOCX. MOBI/AZW3 and DRM-protected books are not supported.")
        }
        require(result.book.chapters.any { it.paragraphs.isNotEmpty() }) { "No readable text found. Scanned PDFs require OCR; protected books cannot be imported." }
        return result.copy(book = result.book.copy(chapters = result.book.chapters.filter { it.paragraphs.isNotEmpty() }))
    }

    fun epub(bytes: ByteArray): Imported {
        val files = zip(bytes)
        fun xml(path: String) = Jsoup.parse(decode(files[path] ?: error("EPUB is missing $path")), "", Parser.xmlParser())
        fun resolve(base: String, path: String): String = URI(base).resolve(path.substringBefore('#')).normalize().path.removePrefix("/")
        fun reference(base: String, href: String): String = resolve(base, href) + if ('#' in href) "#${href.substringAfter('#')}" else ""
        val opfPath = xml("META-INF/container.xml").local("rootfile").firstOrNull()?.attr("full-path") ?: error("EPUB has no rootfile")
        val opf = xml(opfPath)
        val manifest = opf.local("item").associateBy { it.attr("id") }
        val title = opf.local("title").firstOrNull()?.text().orEmpty().ifBlank { "Untitled" }
        val author = opf.local("creator").joinToString(", ") { it.text() }
        val navTitles = mutableMapOf<String, String>()
        manifest.values.filter { it.attr("properties").split(' ').contains("nav") || it.attr("media-type").contains("ncx") }.forEach { item ->
            val path = resolve(opfPath, item.attr("href"))
            runCatching {
                val nav = xml(path)
                val toc = nav.local("nav").firstOrNull { it.attr("epub:type").split(' ').contains("toc") } ?: nav
                toc.local("a").forEach { navTitles.putIfAbsent(reference(path, it.attr("href")), it.text()) }
                nav.local("navPoint").forEach { point -> point.local("content").firstOrNull()?.let { navTitles.putIfAbsent(reference(path, it.attr("src")), point.local("navLabel").firstOrNull()?.text().orEmpty()) } }
            }
        }
        val chapters = opf.local("itemref").filter { it.attr("linear") != "no" }.flatMap { ref ->
            val item = manifest[ref.attr("idref")] ?: return@flatMap emptyList()
            val path = resolve(opfPath, item.attr("href"))
            val text = decode(files[path] ?: error("EPUB chapter is missing: $path"))
            val doc = Jsoup.parse(text)
            val blocks = htmlBlocks(doc)
            val elements = doc.getAllElements().withIndex().associate { it.value to it.index }
            val boundaries = sortedMapOf<Int, String>()
            navTitles.filterKeys { it.substringBefore('#') == path }.forEach { (link, heading) ->
                if ('#' !in link) boundaries.putIfAbsent(0, heading)
                else doc.getElementById(link.substringAfter('#'))?.let { target ->
                    val index = blocks.indexOfFirst { block -> target in block.getAllElements() || elements.getValue(block) >= elements.getValue(target) }
                    if (index >= 0) boundaries.putIfAbsent(index, heading)
                }
            }
            if (blocks.isEmpty()) emptyList() else {
                boundaries.putIfAbsent(0, doc.selectFirst("h1,h2,h3")?.text().orEmpty().ifBlank { "Section ${ref.elementSiblingIndex() + 1}" })
                val starts = boundaries.entries.toList()
                starts.mapIndexed { index, entry -> Chapter(entry.value, blocks.subList(entry.key, starts.getOrNull(index + 1)?.key ?: blocks.size).map { it.text() }) }
            }
        }
        val coverId = opf.local("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
        val coverItem = manifest.values.firstOrNull { it.attr("properties").contains("cover-image") } ?: manifest[coverId]
        val cover = coverItem?.let { files[resolve(opfPath, it.attr("href"))] }
        return Imported(Book(title = title, author = author, format = "epub", chapters = chapters), cover)
    }
}
