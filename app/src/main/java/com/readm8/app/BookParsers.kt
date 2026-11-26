package com.readm8.app

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.zip.ZipFile

data class BookChapter(
    val title: String,
    val htmlContent: String,
    val baseUrl: String
)

data class ParsedBook(
    val title: String,
    val chapters: List<BookChapter>
)

object BookParsers {

    val SUPPORTED_EXTS = setOf(
        "epub", "pdf", "mobi", "prc", "azw3", "txt", "md", "html", "htm", "fb2", "cbz"
    )

    fun isPageBased(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext == "pdf" || ext == "cbz"
    }

    fun parseTextBook(context: Context, file: File): ParsedBook {
        return when (file.extension.lowercase()) {
            "epub" -> parseEpub(context, file)
            "mobi", "prc", "azw3" -> parseMobi(file)
            "fb2" -> parseFb2(file)
            "html", "htm" -> parseHtml(file)
            "md" -> parseMarkdown(file)
            else -> parseTxt(file)
        }
    }

    // ==================== 1. EPUB ====================
    private fun parseEpub(context: Context, file: File): ParsedBook {
        val key = "${file.name.hashCode()}_${file.length()}_${file.lastModified()}"
        val outDir = File(context.cacheDir, "epub_$key")
        val doneMarker = File(outDir, ".extracted_ok")

        if (!doneMarker.exists()) {
            outDir.deleteRecursively()
            outDir.mkdirs()
            val rootCanonical = outDir.canonicalPath
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val dest = File(outDir, entry.name)
                    if (!dest.canonicalPath.startsWith(rootCanonical)) continue
                    if (entry.isDirectory) {
                        dest.mkdirs()
                    } else {
                        dest.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(dest).use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
            doneMarker.createNewFile()
        }

        // Đọc META-INF/container.xml tìm đường dẫn file .opf
        var opfRelPath: String? = null
        val containerFile = File(outDir, "META-INF/container.xml")
        if (containerFile.exists()) {
            runCatching {
                val parser = Xml.newPullParser()
                containerFile.inputStream().use {
                    parser.setInput(it, "UTF-8")
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.START_TAG &&
                            parser.name.substringAfter(':').equals("rootfile", true)
                        ) {
                            opfRelPath = parser.getAttributeValue(null, "full-path")
                            break
                        }
                        event = parser.next()
                    }
                }
            }
        }

        val opfFile = opfRelPath?.let { File(outDir, it) }?.takeIf { it.exists() }
            ?: outDir.walkTopDown().firstOrNull { it.extension.equals("opf", true) }

        var bookTitle = file.nameWithoutExtension
        val manifest = mutableMapOf<String, String>()
        val spineIds = mutableListOf<String>()
        var ncxRelPath: String? = null
        val opfDir = opfFile?.parentFile ?: outDir

        if (opfFile != null && opfFile.exists()) {
            runCatching {
                val parser = Xml.newPullParser()
                opfFile.inputStream().use {
                    parser.setInput(it, "UTF-8")
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.START_TAG) {
                            val tag = parser.name.substringAfter(':').lowercase()
                            when (tag) {
                                "title" -> {
                                    val t = runCatching { parser.nextText().trim() }.getOrNull()
                                    if (!t.isNullOrBlank()) bookTitle = t
                                }
                                "item" -> {
                                    val id = parser.getAttributeValue(null, "id")
                                    val href = parser.getAttributeValue(null, "href")
                                    val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                                    if (id != null && href != null) {
                                        val decoded = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
                                        manifest[id] = decoded
                                        if (mediaType.contains("dtbncx", true) || decoded.endsWith(".ncx", true)) {
                                            ncxRelPath = decoded
                                        }
                                    }
                                }
                                "itemref" -> {
                                    val idref = parser.getAttributeValue(null, "idref")
                                    if (idref != null) spineIds.add(idref)
                                }
                            }
                        }
                        event = parser.next()
                    }
                }
            }
        }

        // Lấy tên chương từ toc.ncx nếu có
        val tocMap = mutableMapOf<String, String>()
        val ncxFile = ncxRelPath?.let { File(opfDir, it) }?.takeIf { it.exists() }
        if (ncxFile != null) {
            runCatching {
                val parser = Xml.newPullParser()
                ncxFile.inputStream().use {
                    parser.setInput(it, "UTF-8")
                    var currentLabel = ""
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.START_TAG) {
                            val tag = parser.name.substringAfter(':').lowercase()
                            if (tag == "text") {
                                currentLabel = runCatching { parser.nextText().trim() }.getOrDefault("")
                            } else if (tag == "content") {
                                val src = parser.getAttributeValue(null, "src") ?: ""
                                val cleanSrc = runCatching {
                                    URLDecoder.decode(src.substringBefore('#'), "UTF-8")
                                }.getOrDefault(src.substringBefore('#'))
                                if (cleanSrc.isNotBlank() && currentLabel.isNotBlank()) {
                                    val resolved = File(ncxFile.parentFile, cleanSrc).canonicalPath
                                    tocMap.putIfAbsent(resolved, currentLabel)
                                }
                            }
                        }
                        event = parser.next()
                    }
                }
            }
        }

        val chapterFiles = spineIds.mapNotNull { id ->
            manifest[id]?.let { File(opfDir, it) }?.takeIf { it.exists() }
        }.ifEmpty {
            outDir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in setOf("xhtml", "html", "htm") }
                .sortedWith(compareBy { naturalSortKey(it.relativeTo(outDir).path) })
                .toList()
        }

        val chapters = chapterFiles.mapIndexed { idx, chFile ->
            val raw = runCatching { chFile.readText(Charsets.UTF_8) }.getOrDefault("")
            val title = tocMap[chFile.canonicalPath]
                ?: Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(raw)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() && it.length < 80 }
                ?: "Chương ${idx + 1}"
            val base = (chFile.parentFile ?: outDir).toURI().toString()
            BookChapter(title, raw, base)
        }

        return ParsedBook(
            title = bookTitle,
            chapters = chapters.ifEmpty {
                listOf(BookChapter("Nội dung", "<p>Không tìm thấy nội dung trong file EPUB này.</p>", "file:///"))
            }
        )
    }

    // ==================== 2. MOBI / PRC / AZW3 ====================
    private fun parseMobi(file: File): ParsedBook {
        if (file.length() > 35L * 1024 * 1024) {
            return ParsedBook(file.nameWithoutExtension, listOf(
                BookChapter("Thông báo", "<p>File vượt quá 35MB, nên chuyển sang EPUB để đọc mượt hơn.</p>", "file:///")
            ))
        }
        val bytes = file.readBytes()
        if (bytes.size < 86) {
            return ParsedBook(file.nameWithoutExtension, listOf(
                BookChapter("Lỗi", "<p>File MOBI/PRC không đúng định dạng.</p>", "file:///")
            ))
        }

        val numRecords = u16(bytes, 76)
        if (numRecords < 2 || 78 + numRecords * 8 > bytes.size) {
            return ParsedBook(file.nameWithoutExtension, listOf(
                BookChapter("Lỗi", "<p>Không đọc được cấu trúc bản ghi PalmDB.</p>", "file:///")
            ))
        }

        val offsets = IntArray(numRecords) { i -> u32(bytes, 78 + i * 8).toInt() }
        val rec0Start = offsets[0]
        val rec0End = offsets[1].coerceAtMost(bytes.size)
        val rec0 = bytes.copyOfRange(rec0Start, rec0End)

        val compression = u16(rec0, 0)
        val textRecordCount = u16(rec0, 8).coerceAtMost(numRecords - 1)
        val encryption = if (rec0.size >= 14) u16(rec0, 12) else 0

        if (encryption != 0) {
            return ParsedBook(file.nameWithoutExtension, listOf(
                BookChapter("Có bản quyền DRM", "<p>File này bị khóa DRM nên không thể giải mã trực tiếp.</p>", "file:///")
            ))
        }
        if (compression != 1 && compression != 2) {
            return ParsedBook(file.nameWithoutExtension, listOf(
                BookChapter("Không hỗ trợ nén HUFF/CDIC",
                    "<p>File sử dụng chuẩn nén đặc biệt (mã $compression). Bạn đổi sang file .epub hoặc .prc chuẩn để đọc nhé.</p>",
                    "file:///")
            ))
        }

        var charset: Charset = Charsets.UTF_8
        var extraFlags = 0
        if (rec0.size >= 36 && String(rec0, 16, 4, Charsets.US_ASCII) == "MOBI") {
            val mobiHeaderLen = u32(rec0, 20).toInt()
            val encoding = u32(rec0, 28).toInt()
            if (encoding == 1252) charset = Charset.forName("windows-1252")
            if (mobiHeaderLen >= 228 && rec0.size >= 244) {
                extraFlags = u16(rec0, 242)
            }
        }

        val out = ByteArrayOutputStream()
        for (i in 1..textRecordCount) {
            val start = offsets[i]
            val end = if (i + 1 < numRecords) offsets[i + 1] else bytes.size
            if (start < 0 || end <= start || end > bytes.size) continue
            val rawEnd = trimTrailingEntries(bytes, start, end, extraFlags)
            if (compression == 1) {
                out.write(bytes, start, rawEnd - start)
            } else {
                decompressPalmDoc(bytes, start, rawEnd, out)
            }
        }

        val rawText = String(out.toByteArray(), charset)
        val isHtml = rawText.contains("<p", true) || rawText.contains("<br", true) || rawText.contains("<div", true)
        val cleanBody = if (isHtml) {
            rawText
                .replace(Regex("<head[\\s\\S]*?</head>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("</?(html|body)[^>]*>", RegexOption.IGNORE_CASE), "")
        } else {
            plainTextToHtml(rawText)
        }

        val chunks = splitLargeHtml(cleanBody, 35_000)
        val chapters = chunks.mapIndexed { idx, chunk ->
            BookChapter("Phần ${idx + 1} / ${chunks.size}", chunk, "file:///")
        }
        return ParsedBook(file.nameWithoutExtension, chapters)
    }

    private fun trimTrailingEntries(buf: ByteArray, start: Int, end: Int, flags: Int): Int {
        var curEnd = end
        if (flags == 0 || curEnd <= start) return curEnd
        var f = flags shr 1
        while (f > 0 && curEnd > start) {
            if ((f and 1) != 0) {
                var size = 0
                var shift = 0
                var pos = curEnd - 1
                while (pos >= start && shift < 28) {
                    val b = buf[pos].toInt() and 0xFF
                    size = size or ((b and 0x7F) shl shift)
                    pos--
                    shift += 7
                    if ((b and 0x80) != 0) break
                }
                if (size in 1..(curEnd - start)) {
                    curEnd -= size
                }
            }
            f = f shr 1
        }
        if ((flags and 1) != 0 && curEnd > start) {
            val overlap = (buf[curEnd - 1].toInt() and 0x03) + 1
            if (overlap < curEnd - start) curEnd -= overlap
        }
        return curEnd
    }

    private fun decompressPalmDoc(src: ByteArray, start: Int, end: Int, out: ByteArrayOutputStream) {
        val temp = ByteArrayOutputStream(4096)
        var i = start
        while (i < end) {
            val b = src[i++].toInt() and 0xFF
            when {
                b == 0 || b in 9..0x7F -> temp.write(b)
                b in 1..8 -> {
                    val count = minOf(b, end - i)
                    temp.write(src, i, count)
                    i += count
                }
                b in 0x80..0xBF -> {
                    if (i >= end) break
                    val next = src[i++].toInt() and 0xFF
                    val pair = (b shl 8) or next
                    val dist = (pair shr 3) and 0x7FF
                    val len = (pair and 0x07) + 3
                    val current = temp.toByteArray()
                    val pos = current.size - dist
                    if (dist > 0 && pos >= 0) {
                        for (k in 0 until len) {
                            val idx = pos + k
                            if (idx < current.size) {
                                temp.write(current[idx].toInt())
                            } else {
                                val updated = temp.toByteArray()
                                val uIdx = updated.size - dist
                                if (uIdx >= 0) temp.write(updated[uIdx].toInt())
                            }
                        }
                    }
                }
                else -> {
                    temp.write(0x20)
                    temp.write(b xor 0x80)
                }
            }
        }
        out.write(temp.toByteArray())
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)

    // ==================== 3. TXT / MD / HTML / FB2 ====================
    private fun parseTxt(file: File): ParsedBook {
        val text = readSmartText(file)
        val html = plainTextToHtml(text)
        val chunks = splitLargeHtml(html, 35_000)
        return ParsedBook(
            file.nameWithoutExtension,
            chunks.mapIndexed { i, c -> BookChapter("Phần ${i + 1} / ${chunks.size}", c, "file:///") }
        )
    }

    private fun parseMarkdown(file: File): ParsedBook {
        val text = readSmartText(file)
        val sb = StringBuilder()
        text.lines().forEach { line ->
            val trimmed = line.trim()
            val esc = escapeHtml(trimmed)
                .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
                .replace(Regex("\\*(.+?)\\*"), "<i>$1</i>")
            when {
                trimmed.startsWith("### ") -> sb.append("<h3>").append(esc.removePrefix("### ")).append("</h3>")
                trimmed.startsWith("## ") -> sb.append("<h2>").append(esc.removePrefix("## ")).append("</h2>")
                trimmed.startsWith("# ") -> sb.append("<h1>").append(esc.removePrefix("# ")).append("</h1>")
                trimmed.isEmpty() -> sb.append("<br/>")
                else -> sb.append("<p>").append(esc).append("</p>")
            }
        }
        val chunks = splitLargeHtml(sb.toString(), 35_000)
        return ParsedBook(
            file.nameWithoutExtension,
            chunks.mapIndexed { i, c -> BookChapter("Phần ${i + 1} / ${chunks.size}", c, "file:///") }
        )
    }

    private fun parseHtml(file: File): ParsedBook {
        val raw = readSmartText(file)
        val base = (file.parentFile ?: file).toURI().toString()
        return ParsedBook(file.nameWithoutExtension, listOf(BookChapter(file.nameWithoutExtension, raw, base)))
    }

    private fun parseFb2(file: File): ParsedBook {
        val raw = readSmartText(file)
        val body = Regex("<body[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1) ?: raw
        val cleaned = body
            .replace(Regex("<title>", RegexOption.IGNORE_CASE), "<h2>")
            .replace(Regex("</title>", RegexOption.IGNORE_CASE), "</h2>")
            .replace(Regex("<subtitle>", RegexOption.IGNORE_CASE), "<h3>")
            .replace(Regex("</subtitle>", RegexOption.IGNORE_CASE), "</h3>")
            .replace(Regex("<empty-line\\s*/>", RegexOption.IGNORE_CASE), "<br/>")
        val chunks = splitLargeHtml(cleaned, 35_000)
        return ParsedBook(
            file.nameWithoutExtension,
            chunks.mapIndexed { i, c -> BookChapter("Phần ${i + 1} / ${chunks.size}", c, "file:///") }
        )
    }

    private fun readSmartText(file: File): String {
        val bytes = file.readBytes()
        return when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            else -> String(bytes, Charsets.UTF_8)
        }
    }

    private fun plainTextToHtml(text: String): String {
        val sb = StringBuilder(text.length + 1024)
        text.replace("\r\n", "\n").split("\n").forEach { line ->
            val t = line.trim()
            if (t.isNotEmpty()) {
                sb.append("<p>").append(escapeHtml(t)).append("</p>\n")
            }
        }
        return sb.toString()
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&")
        .replace("<", "<")
        .replace(">", ">")

    private fun splitLargeHtml(html: String, targetSize: Int): List<String> {
        if (html.length <= targetSize) return listOf(html)
        val result = mutableListOf<String>()
        var start = 0
        while (start < html.length) {
            val end = (start + targetSize).coerceAtMost(html.length)
            if (end >= html.length) {
                result.add(html.substring(start))
                break
            }
            val cutP = html.indexOf("</p>", end, ignoreCase = true)
            val cutBr = html.indexOf("<br", end, ignoreCase = true)
            val cut = when {
                cutP in end..(end + 4000) -> cutP + 4
                cutBr in end..(end + 4000) -> cutBr
                else -> end
            }
            result.add(html.substring(start, cut))
            start = cut
        }
        return result
    }

    // ==================== 4. CBZ (Truyện tranh) ====================
    fun listCbzEntries(file: File): List<String> {
        val imageExts = setOf("jpg", "jpeg", "png", "webp", "bmp")
        return ZipFile(file).use { zip ->
            zip.entries().toList()
                .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in imageExts }
                .map { it.name }
                .sortedWith(compareBy { naturalSortKey(it) })
        }
    }

    private fun naturalSortKey(s: String): String =
        s.lowercase().replace(Regex("\\d+")) { it.value.padStart(8, '0') }
}
