package com.geminireader.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.geminireader.data.Book
import com.geminireader.data.Chapter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.File

class BookImporter(private val context: Context) {
    fun uri(uri: Uri): Imported {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "book.txt"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readLimited(100 * 1024 * 1024 + 1) } ?: error("Cannot open book")
        require(bytes.size <= 100 * 1024 * 1024) { "Books must be under 100 MB" }
        return parse(name, bytes)
    }
    fun file(file: File): Imported { require(file.length() <= 100 * 1024 * 1024) { "Books must be under 100 MB" }; return parse(file.name, file.readBytes()) }
    private fun parse(name: String, bytes: ByteArray): Imported = if (name.endsWith(".pdf", true)) pdf(name, bytes) else TextImporters.parse(name, bytes)
    private fun pdf(name: String, bytes: ByteArray): Imported {
        PDFBoxResourceLoader.init(context)
        val chapters = mutableListOf<Chapter>()
        var title = name.substringBeforeLast('.')
        var author = ""
        PDDocument.load(bytes).use { document ->
            require(!document.isEncrypted || document.currentAccessPermission.canExtractContent()) { "This PDF does not allow text extraction" }
            title = document.documentInformation.title?.takeIf { it.isNotBlank() } ?: title
            author = document.documentInformation.author.orEmpty()
            val starts = mutableListOf<Pair<Int, String>>()
            var item = document.documentCatalog.documentOutline?.firstChild
            while (item != null) {
                val page = runCatching { item?.findDestinationPage(document) }.getOrNull()
                if (page != null) starts += document.pages.indexOf(page) + 1 to item.title.orEmpty()
                item = item.nextSibling
            }
            val ranges = if (starts.isNotEmpty()) (listOf(1 to "Opening") + starts).distinctBy { it.first }.sortedBy { it.first }
                else (1..document.numberOfPages step 20).map { it to "Pages $it–${minOf(it + 19, document.numberOfPages)}" }
            ranges.forEachIndexed { index, (start, heading) ->
                val stripper = PDFTextStripper().apply { startPage = start; endPage = ranges.getOrNull(index + 1)?.first?.minus(1) ?: document.numberOfPages; sortByPosition = true }
                val paragraphs = TextImporters.reflow(stripper.getText(document))
                if (paragraphs.isNotEmpty()) chapters += Chapter(heading, paragraphs)
            }
        }
        require(chapters.isNotEmpty()) { "This PDF has no text layer. OCR scanned pages before importing." }
        val cover = runCatching {
            val temp = File.createTempFile("cover", ".pdf", context.cacheDir)
            try {
                temp.writeBytes(bytes)
                ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer -> renderer.openPage(0).use { page ->
                        val bitmap = Bitmap.createBitmap(300, (300f * page.height / page.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray() }
                    } }
                }
            } finally { temp.delete() }
        }.getOrNull()
        return Imported(Book(title = title, author = author, format = "pdf", chapters = chapters), cover)
    }
}
