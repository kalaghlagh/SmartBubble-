package com.example.template.docx

import com.example.template.domain.TemplateProfile
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads the formatting of a Word (DOCX) template into a [TemplateProfile]: page size and
 * orientation, the four margins and the gutter, header/footer distances, first-page and odd/even
 * header settings, the text and alignment of headers and footers (page-number fields included),
 * an image in the header (a university logo), page borders, page-number format, the body font,
 * size, alignment, line spacing, paragraph spacing and first-line indent, heading 1-3 styles,
 * table borders and header shading, right-to-left settings and the elements the template body
 * holds (tables, images, page and section breaks, footnotes, lists).
 *
 * What cannot be reproduced everywhere is listed in [TemplateProfile.extractionWarnings] (codes
 * explained to the user) instead of being dropped silently. The Word export keeps the template's
 * own package (styles, headers, footers, borders, section settings), so these values mainly
 * drive the PDF export and the page preview, which are drawn by the app.
 */
object DocxTemplateAnalyzer {

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val V = "urn:schemas-microsoft-com:vml"
    private const val MAX_PART_BYTES = 8L * 1024 * 1024

    /** Codes stored in [TemplateProfile.extractionWarnings]. */
    object Warn {
        const val ODD_EVEN = "odd_even_headers"
        const val HEADER_SHAPES = "header_shapes"
        const val SECTIONS = "multiple_sections"
        const val BODY_CONTENT = "body_content"
        const val BORDER_ART = "page_border_art"
        const val FOOTNOTES = "footnotes"
        const val NOT_READABLE = "not_readable"
    }

    fun analyze(file: File, templateId: String, name: String, sourceFileName: String = file.name): TemplateProfile {
        val parts = HashMap<String, ByteArray>()
        val names = ArrayList<String>()
        try {
            ZipFile(file).use { zip ->
                for (e in zip.entries()) {
                    names.add(e.name)
                    if (e.isDirectory || !e.name.endsWith(".xml") && !e.name.endsWith(".rels")) continue
                    if (e.size > MAX_PART_BYTES) continue
                    zip.getInputStream(e).use { input ->
                        val bytes = input.readNBytesCompat(MAX_PART_BYTES)
                        if (bytes != null) parts[e.name] = bytes
                    }
                }
            }
        } catch (e: Exception) {
            return fallback(templateId, name, sourceFileName)
        }
        return try {
            analyze(parts, names.toSet(), templateId, name, sourceFileName)
        } catch (e: Exception) {
            fallback(templateId, name, sourceFileName)
        }
    }

    private fun fallback(templateId: String, name: String, sourceFileName: String) = TemplateProfile.defaultProfile().copy(
        templateId = templateId, name = name, originalFileType = "DOCX", headerText = "", footerText = "",
        layoutSource = "DOCX", sourceFileName = sourceFileName, extractionWarnings = Warn.NOT_READABLE,
        extractedPropertiesSummary = "The template's formatting could not be read; default formatting is used."
    )

    private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ XML helpers

    private fun parse(bytes: ByteArray?): Element? {
        if (bytes == null) return null
        // Word never writes a DOCTYPE. Refused before parsing: Android's parser ignores the
        // features below, so this is what keeps entity tricks out on the device.
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        require(!head.contains("<!DOCTYPE", ignoreCase = true) && !head.contains("<!ENTITY", ignoreCase = true)) { "DOCTYPE in a template part" }
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        // The template is a file from outside the app: no DTDs, no external entities.
        for ((feature, value) in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false
        )) runCatching { f.setFeature(feature, value) }
        runCatching { f.isExpandEntityReferences = false }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
    }

    private fun Element.children(ns: String = W, local: String? = null): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = firstChild
        while (n != null) {
            if (n is Element && n.namespaceURI == ns && (local == null || n.localName == local)) out.add(n)
            n = n.nextSibling
        }
        return out
    }

    private fun Element.child(local: String, ns: String = W): Element? = children(ns, local).firstOrNull()

    private fun Element.all(local: String, ns: String = W): List<Element> {
        val list = getElementsByTagNameNS(ns, local)
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun Element.wAttr(name: String): String? = getAttributeNS(W, name).takeIf { it.isNotEmpty() }

    private fun Element?.onOff(): Boolean = this != null && wAttr("val").let { it == null || it == "1" || it == "true" || it == "on" }

    private fun twipsToPt(v: String?): Int? = v?.toDoubleOrNull()?.let { Math.round(Math.abs(it) / 20.0).toInt() }

    private fun colorHex(v: String?): String? = v?.takeIf { Regex("^[0-9A-Fa-f]{6}$").matches(it) }?.let { "#" + it.uppercase() }

    private fun align(jc: String?): String? = when (jc) {
        "center" -> "center"
        "both", "distribute", "lowKashida", "mediumKashida", "highKashida", "thaiDistribute" -> "justify"
        "right", "end" -> "end"
        "left", "start" -> "start"
        else -> null
    }

    private fun isArabic(c: Char) = c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ﭐ'..'﷿' || c in 'ﹰ'..'ﻼ'

    /** Target of relationship [id] in a rels part, as a package path relative to "word/". */
    private fun relTarget(rels: Element?, id: String?): String? {
        if (rels == null || id.isNullOrEmpty()) return null
        val ns = "http://schemas.openxmlformats.org/package/2006/relationships"
        val list = rels.getElementsByTagNameNS(ns, "Relationship")
        for (i in 0 until list.length) {
            val r = list.item(i) as Element
            if (r.getAttribute("Id") == id) {
                if (r.getAttribute("TargetMode") == "External") return null
                val t = r.getAttribute("Target")
                return if (t.startsWith("/")) t.removePrefix("/") else "word/" + t.removePrefix("./")
            }
        }
        return null
    }

    // ------------------------------------------------------------------ headers and footers

    /** Text of a header/footer part ("{PAGE}" / "{NUMPAGES}" where those fields are), its alignment, image and shapes. */
    internal class HeaderPart(val text: String, val alignment: String, val hasPageField: Boolean, val image: String, val shapes: Boolean)

    /** "PAGE" for " PAGE \\* MERGEFORMAT " (PAGEREF stays PAGEREF). */
    private fun fieldName(instr: String) = instr.trim().substringBefore(' ').uppercase()

    private fun headerPart(path: String, parts: Map<String, ByteArray>): HeaderPart? {
        val root = parse(parts[path]) ?: return null
        val rels = parse(parts[path.substringBeforeLast('/') + "/_rels/" + path.substringAfterLast('/') + ".rels"])
        val lines = ArrayList<String>()
        var alignment: String? = null
        var page = false
        for (p in root.all("p")) {
            val sb = StringBuilder()
            var inField = 0
            var fieldInstr = StringBuilder()
            var skipResult = false
            fun walk(e: Element) {
                var n: Node? = e.firstChild
                while (n != null) {
                    if (n is Element && n.namespaceURI == W) when (n.localName) {
                        "fldChar" -> when (n.wAttr("fldCharType")) {
                            "begin" -> { inField++; fieldInstr = StringBuilder(); skipResult = false }
                            "separate" -> {
                                when (fieldName(fieldInstr.toString())) {
                                    "PAGE" -> { sb.append("{PAGE}"); page = true; skipResult = true }
                                    "NUMPAGES", "SECTIONPAGES" -> { sb.append("{NUMPAGES}"); skipResult = true }
                                }
                            }
                            "end" -> { inField = (inField - 1).coerceAtLeast(0); skipResult = false }
                        }
                        "instrText" -> fieldInstr.append(n.textContent)
                        "fldSimple" -> {
                            when (fieldName(n.wAttr("instr").orEmpty())) {
                                "PAGE" -> { sb.append("{PAGE}"); page = true }
                                "NUMPAGES", "SECTIONPAGES" -> sb.append("{NUMPAGES}")
                                else -> walk(n)
                            }
                        }
                        "t" -> if (!skipResult) sb.append(n.textContent)
                        "tab" -> if (n.parentNode?.localName == "r") sb.append("    ")
                        "pPr", "rPr", "txbxContent" -> Unit // text boxes are reported as shapes
                        else -> walk(n)
                    }
                    else if (n is Element) walk(n)
                    n = n.nextSibling
                }
            }
            // Text inside text boxes belongs to a shape, not to the header line.
            if (generateSequence(p.parentNode) { it.parentNode }.any { it.localName == "txbxContent" }) continue
            walk(p)
            val line = sb.toString().trim()
            if (line.isNotEmpty()) {
                lines.add(line)
                if (alignment == null) alignment = align(p.child("pPr")?.child("jc")?.wAttr("val")) ?: "start"
            }
        }
        var image = ""
        val blip = root.all("blip", A).firstOrNull()?.getAttributeNS(R, "embed")
            ?: root.all("imagedata", V).firstOrNull()?.getAttributeNS(R, "id")
        val target = relTarget(rels, blip)
        if (target != null) {
            val extent = root.all("extent", WP).firstOrNull()
            val wPt = extent?.getAttribute("cx")?.toLongOrNull()?.let { (it / 12700).toInt() } ?: 0
            val hPt = extent?.getAttribute("cy")?.toLongOrNull()?.let { (it / 12700).toInt() } ?: 0
            val drawingPara = (root.all("blip", A).firstOrNull() ?: root.all("imagedata", V).firstOrNull())
                ?.let { generateSequence<Node>(it) { n -> n.parentNode }.firstOrNull { n -> n.localName == "p" && n.namespaceURI == W } as? Element }
            val anchorAlign = root.all("positionH", WP).firstOrNull()?.child("align", WP)?.textContent?.trim()
            val imgAlign = align(drawingPara?.child("pPr")?.child("jc")?.wAttr("val"))
                ?: when (anchorAlign) { "center" -> "center"; "right", "outside" -> "end"; "left", "inside" -> "start"; else -> "start" }
            image = "$target|$wPt|$hPt|$imgAlign"
        }
        val shapes = root.all("txbxContent").isNotEmpty() ||
            root.all("wsp", "http://schemas.microsoft.com/office/word/2010/wordprocessingShape").isNotEmpty() ||
            root.all("shape", V).any { it.all("imagedata", V).isEmpty() } || root.all("rect", V).isNotEmpty() || root.all("line", V).isNotEmpty()
        return HeaderPart(lines.joinToString("\n"), alignment ?: "start", page, image, shapes)
    }

    // ------------------------------------------------------------------ styles

    private class RunStyle(val font: String?, val csFont: String?, val sizePt: Int?, val bold: Boolean?, val color: String?)
    private class ParaStyle(
        val run: RunStyle, val align: String?, val before: Int?, val after: Int?, val line: Float?, val firstLine: Int?, val bidi: Boolean?
    )

    private class Theme(val major: String?, val minor: String?, val majorCs: String?, val minorCs: String?, val accent: String? = null)

    private fun theme(parts: Map<String, ByteArray>): Theme {
        val root = parse(parts["word/theme/theme1.xml"]) ?: return Theme(null, null, null, null)
        fun face(kind: String, script: String) = root.all(kind, A).firstOrNull()?.child(script, A)?.getAttribute("typeface")?.takeIf { it.isNotBlank() }
        val accent = colorHex(root.all("accent1", A).firstOrNull()?.child("srgbClr", A)?.getAttribute("val"))
        return Theme(face("majorFont", "latin"), face("minorFont", "latin"), face("majorFont", "cs"), face("minorFont", "cs"), accent)
    }

    private fun runStyle(rPr: Element?, theme: Theme): RunStyle {
        val fonts = rPr?.child("rFonts")
        fun themed(v: String?) = when {
            v == null -> null
            v.startsWith("major") -> theme.major
            v.startsWith("minor") -> theme.minor
            else -> null
        }
        fun themedCs(v: String?) = when {
            v == null -> null
            v.startsWith("major") -> theme.majorCs
            v.startsWith("minor") -> theme.minorCs
            else -> null
        }
        val font = fonts?.wAttr("ascii") ?: fonts?.wAttr("hAnsi") ?: themed(fonts?.wAttr("asciiTheme"))
        val cs = fonts?.wAttr("cs") ?: themedCs(fonts?.wAttr("cstheme"))
        val size = (rPr?.child("sz") ?: rPr?.child("szCs"))?.wAttr("val")?.toIntOrNull()?.let { (it + 1) / 2 }
        val bold = rPr?.child("b")?.let { it.onOff() }
        val color = colorHex(rPr?.child("color")?.wAttr("val"))
        return RunStyle(font, cs, size, bold, color)
    }

    private fun paraStyle(style: Element?, theme: Theme): ParaStyle {
        val pPr = style?.child("pPr")
        val spacing = pPr?.child("spacing")
        val line = spacing?.wAttr("line")?.toFloatOrNull()?.let { v ->
            if (spacing.wAttr("lineRule").let { it == null || it == "auto" }) v / 240f else null
        }
        val ind = pPr?.child("ind")
        val firstLine = ind?.wAttr("firstLine")?.let(::twipsToPt)
        return ParaStyle(
            runStyle(style?.child("rPr"), theme), align(pPr?.child("jc")?.wAttr("val")),
            twipsToPt(spacing?.wAttr("before")), twipsToPt(spacing?.wAttr("after")), line, firstLine,
            pPr?.child("bidi")?.onOff()
        )
    }

    /** A style with its basedOn chain resolved (nearest value wins). */
    private fun resolved(id: String?, styles: Map<String, Element>, defaults: ParaStyle, theme: Theme): ParaStyle {
        val chain = ArrayList<ParaStyle>()
        var cur = id
        var guard = 0
        while (cur != null && guard++ < 10) {
            val s = styles[cur] ?: break
            chain.add(paraStyle(s, theme))
            cur = s.child("basedOn")?.wAttr("val")
        }
        chain.add(defaults)
        fun <T> pick(f: (ParaStyle) -> T?): T? = chain.firstNotNullOfOrNull(f)
        return ParaStyle(
            RunStyle(pick { it.run.font }, pick { it.run.csFont }, pick { it.run.sizePt }, pick { it.run.bold }, pick { it.run.color }),
            pick { it.align }, pick { it.before }, pick { it.after }, pick { it.line }, pick { it.firstLine }, pick { it.bidi }
        )
    }

    // ------------------------------------------------------------------ the whole template

    internal fun analyze(parts: Map<String, ByteArray>, names: Set<String>, templateId: String, name: String, sourceFileName: String): TemplateProfile {
        val doc = parse(parts["word/document.xml"]) ?: return fallback(templateId, name, sourceFileName)
        val rels = parse(parts["word/_rels/document.xml.rels"])
        val settings = parse(parts["word/settings.xml"])
        val stylesRoot = parse(parts["word/styles.xml"])
        val theme = theme(parts)
        val warnings = LinkedHashSet<String>()
        val body = doc.child("body")
        val sect = body?.children(W, "sectPr")?.lastOrNull()
        val sectionCount = 1 + (body?.all("pPr")?.count { it.child("sectPr") != null } ?: 0)
        if (sectionCount > 1) warnings.add("${Warn.SECTIONS}:$sectionCount")

        // Page setup.
        val pgSz = sect?.child("pgSz")
        var pageW = twipsToPt(pgSz?.wAttr("w")) ?: 595
        var pageH = twipsToPt(pgSz?.wAttr("h")) ?: 842
        val landscape = pgSz?.wAttr("orient") == "landscape"
        if (landscape && pageW < pageH || !landscape && pgSz?.wAttr("orient") == "portrait" && pageW > pageH) { val t = pageW; pageW = pageH; pageH = t }
        val mar = sect?.child("pgMar")
        val top = twipsToPt(mar?.wAttr("top")) ?: 72
        val bottom = twipsToPt(mar?.wAttr("bottom")) ?: 72
        val left = twipsToPt(mar?.wAttr("left")) ?: 72
        val right = twipsToPt(mar?.wAttr("right")) ?: 72
        val gutter = twipsToPt(mar?.wAttr("gutter")) ?: 0
        val headerDist = twipsToPt(mar?.wAttr("header")) ?: 36
        val footerDist = twipsToPt(mar?.wAttr("footer")) ?: 36
        val titlePg = sect?.child("titlePg").onOff()
        val oddEven = settings?.child("evenAndOddHeaders").onOff()
        if (oddEven) warnings.add(Warn.ODD_EVEN)
        val pgNum = sect?.child("pgNumType")
        val sectBidi = sect?.child("bidi").onOff()

        // Page borders: "side:style:widthPt:spacePt:#color;..." and where they are measured from.
        val borders = sect?.child("pgBorders")
        val borderSpec = borders?.children()?.mapNotNull { b ->
            val style = b.wAttr("val") ?: return@mapNotNull null
            if (style == "nil" || style == "none") return@mapNotNull null
            val lineStyles = setOf("single", "thick", "double", "dotted", "dashed", "dotDash", "dotDotDash", "triple", "thinThickSmallGap",
                "thickThinSmallGap", "thinThickMediumGap", "thickThinMediumGap", "thinThickLargeGap", "thickThinLargeGap", "wave", "doubleWave",
                "dashSmallGap", "dashDotStroked", "threeDEmboss", "threeDEngrave", "outset", "inset", "thinThickThinSmallGap",
                "thinThickThinMediumGap", "thinThickThinLargeGap")
            if (style !in lineStyles) warnings.add(Warn.BORDER_ART)
            val widthPt = (b.wAttr("sz")?.toFloatOrNull() ?: 4f) / 8f
            val space = b.wAttr("space")?.toIntOrNull() ?: 0
            val color = colorHex(b.wAttr("color")) ?: "#000000"
            "${b.localName}:${if (style in lineStyles) style else "single"}:$widthPt:$space:$color"
        }?.joinToString(";").orEmpty()
        val borderFrom = borders?.wAttr("offsetFrom") ?: "text"
        val background = colorHex(doc.child("background")?.wAttr("color"))

        // Headers and footers.
        fun ref(kind: String, type: String) = sect?.children(W, kind)?.firstOrNull { (it.wAttr("type") ?: "default") == type }
            ?.let { relTarget(rels, it.getAttributeNS(R, "id")) }?.let { headerPart(it, parts) }
        val header = ref("headerReference", "default")
        val footer = ref("footerReference", "default")
        val firstHeader = if (titlePg) ref("headerReference", "first") else null
        val firstFooter = if (titlePg) ref("footerReference", "first") else null
        if (listOfNotNull(header, footer, firstHeader, firstFooter).any { it.shapes }) warnings.add(Warn.HEADER_SHAPES)
        val pagePos = when {
            footer?.hasPageField == true -> "footer-${footer.alignment}"
            header?.hasPageField == true -> "header-${header.alignment}"
            else -> ""
        }

        // Styles.
        val styleMap = HashMap<String, Element>()
        var normalId: String? = null
        val nameToId = HashMap<String, String>()
        stylesRoot?.children(W, "style")?.forEach { s ->
            val id = s.wAttr("styleId") ?: return@forEach
            styleMap[id] = s
            s.child("name")?.wAttr("val")?.lowercase()?.let { nameToId[it] = id }
            if (s.wAttr("type") == "paragraph" && s.wAttr("default").let { it == "1" || it == "true" }) normalId = id
        }
        val docDefaults = stylesRoot?.child("docDefaults")
        val defaults = ParaStyle(
            runStyle(docDefaults?.child("rPrDefault")?.child("rPr"), theme),
            null, null,
            twipsToPt(docDefaults?.child("pPrDefault")?.child("pPr")?.child("spacing")?.wAttr("after")),
            docDefaults?.child("pPrDefault")?.child("pPr")?.child("spacing")?.let { sp ->
                sp.wAttr("line")?.toFloatOrNull()?.takeIf { sp.wAttr("lineRule").let { r -> r == null || r == "auto" } }?.div(240f)
            },
            null, null
        )
        val normal = resolved(normalId ?: "Normal", styleMap, defaults, theme)
        fun heading(level: Int) = (nameToId["heading $level"] ?: "Heading$level".takeIf { it in styleMap })?.let { resolved(it, styleMap, defaults, theme) }
        val h1 = heading(1)
        val h2 = heading(2)
        val h3 = heading(3)
        val hasCaption = "caption" in nameToId

        // Body elements.
        val tables = body?.all("tbl").orEmpty()
        val images = (body?.all("inline", WP).orEmpty().size) + (body?.all("anchor", WP).orEmpty().size)
        val pageBreaks = body?.all("br")?.count { it.wAttr("type") == "page" } ?: 0
        val bodyText = body?.all("t")?.joinToString("") { it.textContent }.orEmpty()
        val footnotes = (parse(parts["word/footnotes.xml"])?.children(W, "footnote")?.count { (it.wAttr("id")?.toIntOrNull() ?: 0) > 0 } ?: 0)
        if (footnotes > 0) warnings.add(Warn.FOOTNOTES)
        val lists = "word/numbering.xml" in names
        if (bodyText.isNotBlank() || tables.isNotEmpty() || images > 0) warnings.add(Warn.BODY_CONTENT)
        val arabicShare = bodyText.count(::isArabic).toFloat() / bodyText.count { it.isLetter() }.coerceAtLeast(1)
        val rtl = sectBidi || normal.bidi == true || arabicShare > 0.3f ||
            (header?.text.orEmpty() + footer?.text.orEmpty()).let { t -> t.count(::isArabic) > t.count { it.isLetter() } / 2 && t.isNotEmpty() }

        // Table look (first table of the template, else defaults).
        val tbl = tables.firstOrNull()
        val tblBorder = colorHex(tbl?.child("tblPr")?.child("tblBorders")?.children()?.firstNotNullOfOrNull { it.wAttr("color") })
        val headerFill = colorHex(tbl?.child("tr")?.all("shd")?.firstNotNullOfOrNull { it.wAttr("fill") })

        val bodyFont = normal.run.font ?: "Calibri"
        val bodySize = normal.run.sizePt ?: 11
        val headingFont = h1?.run?.font ?: bodyFont
        val headingColor = h1?.run?.color ?: normal.run.color ?: "#000000"
        val headerImage = (header?.image ?: "").ifEmpty { firstHeader?.image.orEmpty() }

        val summary = buildString {
            append("Page ${pageW}x$pageH pt ${if (pageW > pageH) "landscape" else "portrait"}; margins T$top B$bottom L$left R$right pt")
            if (gutter > 0) append(", gutter $gutter pt")
            append("; body $bodyFont $bodySize pt")
            normal.align?.let { append(", $it") }
            normal.line?.let { append(", line ${"%.2f".format(java.util.Locale.ROOT, it)}") }
            h1?.let { append("; heading 1 ${it.run.font ?: bodyFont} ${it.run.sizePt ?: bodySize + 4} pt") }
            if (header != null) append("; header")
            if (footer != null) append("; footer")
            if (pagePos.isNotEmpty()) append("; page numbers $pagePos")
            if (borderSpec.isNotEmpty()) append("; page border")
            if (headerImage.isNotEmpty()) append("; header image")
            if (titlePg) append("; different first page")
            if (tables.isNotEmpty()) append("; ${tables.size} table(s)")
            if (images > 0) append("; $images image(s)")
            if (rtl) append("; right-to-left")
        }

        return TemplateProfile(
            templateId = templateId,
            name = name,
            originalFileType = "DOCX",
            primaryColorHex = h1?.run?.color ?: theme.accent ?: "#1F3A5F",
            secondaryColorHex = headerFill ?: "#D9E2F3",
            textColorHex = normal.run.color ?: "#000000",
            backgroundColorHex = background ?: "#FFFFFF",
            headingFont = headingFont,
            bodyFont = bodyFont,
            baseFontSizePt = bodySize,
            heading1SizePt = h1?.run?.sizePt ?: (bodySize + 4),
            heading2SizePt = h2?.run?.sizePt ?: (bodySize + 2),
            heading3SizePt = h3?.run?.sizePt ?: (bodySize + 1),
            marginTopPt = top,
            marginBottomPt = bottom,
            marginLeftPt = left + gutter,
            marginRightPt = right,
            headerText = header?.text.orEmpty(),
            footerText = footer?.text.orEmpty(),
            showPageNumbers = pagePos.isNotEmpty(),
            tableHeaderBgHex = headerFill ?: "#D9E2F3",
            tableBorderHex = tblBorder ?: "#808080",
            isAnalysisComplete = true,
            extractedPropertiesSummary = summary,
            layoutSource = "DOCX",
            pageWidthPt = pageW,
            pageHeightPt = pageH,
            headingColorHex = headingColor,
            headingBold = h1?.run?.bold ?: true,
            bodyAlignment = normal.align ?: "start",
            headingAlignment = h1?.align ?: "start",
            lineSpacing = normal.line ?: 0f,
            paragraphSpacingPt = normal.after ?: -1,
            headerFontSizePt = 0,
            footerFontSizePt = 0,
            headerAlignment = header?.alignment ?: "start",
            footerAlignment = footer?.alignment ?: "start",
            // Baselines: the header/footer distance plus about one line.
            headerOffsetPt = headerDist + 9,
            footerOffsetPt = (footerDist + 3).coerceAtLeast(12),
            pageNumberPosition = pagePos,
            tableDetected = tables.isNotEmpty(),
            rtl = rtl,
            sourceFileName = sourceFileName,
            gutterPt = gutter,
            differentFirstPage = titlePg,
            differentOddEven = oddEven,
            firstHeaderText = firstHeader?.text.orEmpty(),
            firstFooterText = firstFooter?.text.orEmpty(),
            headerImage = headerImage,
            firstPageHasHeaderImage = firstHeader?.image?.isNotEmpty() == true,
            pageBorders = borderSpec,
            pageBorderOffsetFrom = borderFrom,
            complexScriptFont = normal.run.csFont.orEmpty(),
            firstLineIndentPt = normal.firstLine ?: 0,
            spacingBeforePt = normal.before ?: 0,
            pageNumberFormat = pgNum?.wAttr("fmt").orEmpty(),
            pageNumberStart = pgNum?.wAttr("start")?.toIntOrNull() ?: 0,
            templateTables = tables.size,
            templateImages = images,
            templatePageBreaks = pageBreaks,
            templateSections = sectionCount,
            templateFootnotes = footnotes,
            templateHasLists = lists,
            templateHasCaptionStyle = hasCaption,
            extractionWarnings = warnings.joinToString(";")
        )
    }

    /** Bytes of a part of the template (the header image), or null. */
    fun partBytes(file: File, path: String, limit: Long = MAX_PART_BYTES): ByteArray? = try {
        ZipFile(file).use { zip ->
            val e = zip.getEntry(path) ?: return null
            if (e.size > limit) return null
            zip.getInputStream(e).use { it.readNBytesCompat(limit) }
        }
    } catch (e: Exception) {
        null
    }
}
