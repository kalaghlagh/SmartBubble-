package com.example.template.pdf

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Builds small but valid PDF files for tests (real PDF syntax with a correct cross-reference
 * table): text with standard and embedded-style fonts, colors, ruled tables, filled shapes,
 * images, compressed streams, object streams and encryption markers.
 */
class TestPdf(private val compress: Boolean = false) {

    private val objects = ArrayList<ByteArray?>()
    private val pageRefs = ArrayList<Int>()
    var encrypt = false
    var useObjectStream = false

    fun reserve(): Int { objects.add(null); return objects.size }
    fun set(num: Int, body: String) { objects[num - 1] = body.toByteArray(Charsets.ISO_8859_1) }
    fun set(num: Int, body: ByteArray) { objects[num - 1] = body }
    fun add(body: String): Int { objects.add(body.toByteArray(Charsets.ISO_8859_1)); return objects.size }

    fun stream(dict: String, data: ByteArray, compressIt: Boolean = compress): Int {
        val payload = if (compressIt) deflate(data) else data
        val filter = if (compressIt) "/Filter /FlateDecode " else ""
        val out = ByteArrayOutputStream()
        out.write("<< $dict $filter/Length ${payload.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        out.write("\nendstream".toByteArray(Charsets.ISO_8859_1))
        objects.add(out.toByteArray())
        return objects.size
    }

    private val pagesNum by lazy { reserve() }
    private val fontRegular by lazy { add("<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman >>") }
    private val fontBold by lazy { add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>") }
    private val fontHelvetica by lazy { add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>") }

    /** A Type0 font with a ToUnicode map, as used for Arabic text. Glyph code i -> [chars][i-1]. */
    fun arabicFont(chars: String): Int {
        val cmap = buildString {
            append("/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n")
            append("1 begincodespacerange <0000> <FFFF> endcodespacerange\n")
            append("${chars.length} beginbfchar\n")
            chars.forEachIndexed { i, c -> append(String.format("<%04X> <%04X>\n", i + 1, c.code)) }
            append("endbfchar\nendcmap CMapName currentdict /CMap defineresource pop end end\n")
        }
        val toUnicode = stream("", cmap.toByteArray(Charsets.ISO_8859_1))
        val descendant = add("<< /Type /Font /Subtype /CIDFontType2 /BaseFont /ABCDEF+NotoNaskhArabic-Regular /DW 500 /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> >>")
        return add("<< /Type /Font /Subtype /Type0 /BaseFont /ABCDEF+NotoNaskhArabic-Regular /Encoding /Identity-H /DescendantFonts [$descendant 0 R] /ToUnicode $toUnicode 0 R >>")
    }

    /** Hex string of glyph codes 1..n for [text] drawn with [arabicFont]. */
    fun arabicCodes(text: String, chars: String): String =
        "<" + text.map { String.format("%04X", chars.indexOf(it) + 1) }.joinToString("") + ">"

    fun page(content: String, width: Int = 595, height: Int = 842, extraFonts: Map<String, Int> = emptyMap(), xobjects: Map<String, Int> = emptyMap()) {
        val contentNum = stream("", content.toByteArray(Charsets.ISO_8859_1))
        val fonts = (mapOf("F1" to fontRegular, "F2" to fontBold, "F3" to fontHelvetica) + extraFonts)
            .entries.joinToString(" ") { "/${it.key} ${it.value} 0 R" }
        val xo = if (xobjects.isEmpty()) "" else "/XObject << " + xobjects.entries.joinToString(" ") { "/${it.key} ${it.value} 0 R" } + " >>"
        pagesNum
        val page = add("<< /Type /Page /Parent $pagesNum 0 R /MediaBox [0 0 $width $height] /Resources << /Font << $fonts >> $xo >> /Contents $contentNum 0 R >>")
        pageRefs.add(page)
    }

    /** An 8x8 gray image XObject. */
    fun image(): Int = stream("/Type /XObject /Subtype /Image /Width 8 /Height 8 /ColorSpace /DeviceGray /BitsPerComponent 8", ByteArray(64) { 0x80.toByte() }, compressIt = false)

    fun build(): ByteArray {
        set(pagesNum, "<< /Type /Pages /Kids [${pageRefs.joinToString(" ") { "$it 0 R" }}] /Count ${pageRefs.size} >>")
        val catalog = add("<< /Type /Catalog /Pages $pagesNum 0 R >>")
        val encryptNum = if (encrypt) add("<< /Filter /Standard /V 2 /R 3 /Length 128 /O <00> /U <00> /P -4 >>") else null

        // Optionally move the page dictionaries into a compressed object stream.
        val inObjStm = if (useObjectStream) pageRefs.toSet() else emptySet()
        if (useObjectStream) {
            val header = StringBuilder()
            val body = ByteArrayOutputStream()
            for (num in inObjStm) {
                header.append("$num ${body.size()} ")
                body.write(objects[num - 1]!!)
                body.write(' '.code)
            }
            val headerBytes = header.toString().toByteArray(Charsets.ISO_8859_1)
            val data = headerBytes + body.toByteArray()
            stream("/Type /ObjStm /N ${inObjStm.size} /First ${headerBytes.size}", data, compressIt = true)
        }

        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        val offsets = IntArray(objects.size)
        for ((i, body) in objects.withIndex()) {
            val num = i + 1
            if (num in inObjStm) continue
            offsets[i] = out.size()
            out.write("$num 0 obj\n".toByteArray(Charsets.ISO_8859_1))
            out.write(body ?: "null".toByteArray())
            out.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        val xref = out.size()
        out.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".toByteArray(Charsets.ISO_8859_1))
        for (i in objects.indices) {
            val line = if (i + 1 in inObjStm) "0000000000 00000 f \n" else String.format("%010d 00000 n \n", offsets[i])
            out.write(line.toByteArray(Charsets.ISO_8859_1))
        }
        val enc = encryptNum?.let { " /Encrypt $it 0 R /ID [<0102> <0102>]" } ?: ""
        out.write("trailer\n<< /Size ${objects.size + 1} /Root $catalog 0 R$enc >>\nstartxref\n$xref\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    companion object {
        fun deflate(data: ByteArray): ByteArray {
            val d = Deflater()
            d.setInput(data)
            d.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            d.end()
            return out.toByteArray()
        }

        /** PDF literal string with escapes. */
        fun lit(text: String): String = "(" + text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")"

        /**
         * A business-letter style template: header and footer with a page number, a blue bold
         * title, section headings, justified body text, a bullet list and a ruled table with a
         * colored header row.
         */
        fun corporateTemplate(compress: Boolean = false, pages: Int = 1, landscape: Boolean = false): ByteArray {
            val pdf = TestPdf(compress)
            val w = if (landscape) 842 else 595
            val h = if (landscape) 595 else 842
            repeat(pages) { p ->
                val c = StringBuilder()
                // Colored band at the top and a footer rule.
                c.append("0.122 0.306 0.475 rg 0 ${h - 20} $w 20 re f\n")
                c.append("0.6 0.6 0.6 RG 0.5 w 72 60 m ${w - 72} 60 l S\n")
                // Header (repeats on every page) and footer page number.
                c.append("BT /F3 9 Tf 0.4 0.4 0.4 rg 72 ${h - 45} Td ${lit("ACME Consulting Group")} Tj ET\n")
                c.append("BT /F3 9 Tf 0.4 0.4 0.4 rg ${w / 2 - 5} 40 Td ${lit("${p + 1}")} Tj ET\n")
                // Title and headings: Helvetica-Bold, dark blue.
                c.append("BT /F2 20 Tf 0.122 0.306 0.475 rg 72 ${h - 100} Td ${lit("Quarterly Review")} Tj ET\n")
                c.append("BT /F2 14 Tf 0.122 0.306 0.475 rg 72 ${h - 140} Td ${lit("Executive Summary")} Tj ET\n")
                // Body: Times 11 pt, black, lines of equal length (justified) and a short last line.
                var y = h - 160
                val full = "x".repeat(78) // 78 glyphs * 0.52 * 11 pt = 446 pt (72..518)
                repeat(5) {
                    c.append("BT /F1 11 Tf 0 0 0 rg 72 $y Td ${lit(full)} Tj ET\n"); y -= 14
                }
                c.append("BT /F1 11 Tf 0 0 0 rg 72 $y Td ${lit("Short closing line.")} Tj ET\n"); y -= 24
                repeat(4) {
                    c.append("BT /F1 11 Tf 0 0 0 rg 72 $y Td ${lit(full)} Tj ET\n"); y -= 14
                }
                c.append("BT /F1 11 Tf 0 0 0 rg 72 $y Td ${lit("End of the paragraph.")} Tj ET\n"); y -= 30
                c.append("BT /F2 14 Tf 0.122 0.306 0.475 rg 72 $y Td ${lit("Key Points")} Tj ET\n"); y -= 20
                for (item in listOf("First point", "Second point", "Third point")) {
                    c.append("BT /F1 11 Tf 0 0 0 rg 90 $y Td ${lit("\u0095 $item")} Tj ET\n"); y -= 14
                }
                // Table: header fill + 4 horizontal rules + 4 vertical rules.
                y -= 20
                val top = y
                c.append("0.851 0.886 0.953 rg 72 ${top - 20} 446 20 re f\n")
                c.append("0.5 0.5 0.5 RG 0.75 w\n")
                for (r in 0..3) c.append("72 ${top - r * 20} m 518 ${top - r * 20} l S\n")
                for (x in listOf(72, 220, 370, 518)) c.append("$x $top m $x ${top - 60} l S\n")
                for (r in 0..2) {
                    for ((k, x) in listOf(80, 228, 378).withIndex()) {
                        val font = if (r == 0) "/F2" else "/F1"
                        c.append("BT $font 10 Tf 0 0 0 rg $x ${top - r * 20 - 14} Td ${lit("Cell $r$k")} Tj ET\n")
                    }
                }
                pdf.page(c.toString(), w, h)
            }
            return pdf.build()
        }

        /** A scanned page: one full-page image and no text. */
        fun scannedPage(): ByteArray {
            val pdf = TestPdf()
            val img = pdf.image()
            pdf.page("q 595 0 0 842 0 0 cm /Im1 Do Q\n", xobjects = mapOf("Im1" to img))
            return pdf.build()
        }

        /** An Arabic right-to-left template using an embedded font with ToUnicode. */
        fun arabicTemplate(): ByteArray {
            val chars = "تقريرالشهريحسبونفذمكطةخ "
            val pdf = TestPdf(compress = true)
            val font = pdf.arabicFont(chars)
            val c = StringBuilder()
            c.append("BT /F9 18 Tf 0 0.4 0.2 rg 380 760 Td ${pdf.arabicCodes("التقرير الشهري", chars)} Tj ET\n")
            var y = 720
            repeat(6) {
                val text = "نفذ الفريق الخطة حسب المطلوب"
                // Right-aligned lines: right edge 523; glyph width DW 500 at 11 pt.
                val width = text.length * 0.5 * 11
                c.append("BT /F9 11 Tf 0 0 0 rg ${523 - width} $y Td ${pdf.arabicCodes(text, chars)} Tj ET\n")
                y -= 16
            }
            c.append("BT /F9 11 Tf 0 0 0 rg 400 $y Td ${pdf.arabicCodes("تقرير", chars)} Tj ET\n")
            pdf.page(c.toString(), extraFonts = mapOf("F9" to font))
            return pdf.build()
        }
    }
}
