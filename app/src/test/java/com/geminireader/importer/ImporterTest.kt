package com.geminireader.importer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImporterTest {
    private fun archive(entries: Map<String, String>): ByteArray = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip -> entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() } } }.toByteArray()
    @Test fun epubUsesSpineNotZipOrder() {
        val bytes = archive(mapOf("META-INF/container.xml" to "<container><rootfile full-path='OPS/book.opf'/></container>", "OPS/book.opf" to "<package><metadata><dc:title>Book</dc:title></metadata><manifest><item id='b' href='b.xhtml'/><item id='a' href='a.xhtml'/></manifest><spine><itemref idref='a'/><itemref idref='b'/></spine></package>", "OPS/b.xhtml" to "<h1>Second</h1><p>Two</p>", "OPS/a.xhtml" to "<h1>First</h1><p>One</p>"))
        val book = TextImporters.parse("book.epub", bytes).book
        assertEquals("Book", book.title)
        assertEquals(listOf("First", "Second"), book.chapters.map { it.title })
        assertEquals(listOf("First", "One"), book.chapters.first().paragraphs)
    }
    @Test fun htmlDoesNotDuplicateNestedBlocks() { assertEquals(listOf("Hello world", "Next"), TextImporters.html("<div><p>Hello <b>world</b></p><p>Next</p></div><script>bad()</script>")) }
    @Test fun htmlImportKeepsTitleAndHandlesTextWithoutBlocks() {
        for (format in listOf("html", "htm", "xhtml")) {
            val book = TextImporters.parse("fallback.$format", "<title>Book title</title><body>Hello <b>reader</b><script>ignored()</script></body>".toByteArray()).book
            assertEquals("Book title", book.title)
            assertEquals(listOf("Hello reader"), book.chapters.single().paragraphs)
            assertEquals("fallback", book.chapters.single().title)
        }
    }
    @Test fun epubSplitsNavAnchorsWithinOneSpineDocument() {
        val bytes = archive(mapOf("META-INF/container.xml" to "<container><rootfile full-path='OPS/book.opf'/></container>", "OPS/book.opf" to "<package><manifest><item id='a' href='a.xhtml'/><item id='nav' href='nav.xhtml' properties='nav'/></manifest><spine><itemref idref='a'/></spine></package>", "OPS/nav.xhtml" to "<nav epub:type='toc'><a href='a.xhtml#one'>First chapter</a><a href='a.xhtml#two'>Second chapter</a></nav>", "OPS/a.xhtml" to "<h1 id='one'>One</h1><p>A</p><h1 id='two'>Two</h1><p>B</p>"))
        val chapters = TextImporters.parse("x.epub", bytes).book.chapters
        assertEquals(listOf("First chapter", "Second chapter"), chapters.map { it.title }); assertEquals(listOf("Two", "B"), chapters[1].paragraphs)
    }
    @Test fun pdfReflowPreservesParagraphsAndDehyphenates() { assertEquals(listOf("The extraordinary line continues.", "New paragraph."), TextImporters.reflow("The extra-\nordinary line\ncontinues.\n\nNew paragraph.")) }
    @Test fun docxPreservesRuns() { val data = archive(mapOf("word/document.xml" to "<w:document><w:p><w:r><w:t>Hello </w:t></w:r><w:r><w:t>world</w:t></w:r></w:p></w:document>")); assertEquals("Hello world", TextImporters.parse("x.docx", data).book.chapters[0].paragraphs[0]) }
    @Test fun fb2ReadsSections() { val book = TextImporters.parse("x.fb2", "<FictionBook><description><book-title>Fable</book-title></description><body><section><title><p>One</p></title><p>A story.</p></section></body></FictionBook>".toByteArray()).book; assertEquals("Fable", book.title); assertEquals("A story.", book.chapters[0].paragraphs.last()) }
    @Test fun markdownReadsLinkLabels() { assertEquals("Hello reader", TextImporters.parse("x.md", "# Hello [reader](https://example.com)".toByteArray()).book.chapters[0].paragraphs[0]) }
    @Test fun textRecognizesChapters() { val book = TextImporters.parse("x.txt", "CHAPTER I.\n\nFirst\n\nCHAPTER II.\n\nSecond".toByteArray()).book; assertEquals(2, book.chapters.size) }
}
