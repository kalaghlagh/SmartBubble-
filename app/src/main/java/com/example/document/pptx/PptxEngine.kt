package com.example.document.pptx

import com.example.document.EngineResult
import com.example.document.model.PresentationModel
import com.example.document.model.SlideModel
import com.example.template.domain.TemplateProfile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * PptxEngine produces OpenXML (.pptx) presentations.
 *
 * - Without a template: a complete package (presentation, slide master, slide layout, theme and
 *   slides) using SmartBubble's slide design and the template profile's colors/fonts.
 * - With an uploaded template: the template's presentation, slide masters, layouts and theme are
 *   kept and its slides are replaced by new slides that use the template layouts' placeholders,
 *   so the template's design applies. The template file itself is never modified.
 */
class PptxEngine {

    fun generatePptx(
        model: PresentationModel,
        outputFile: File,
        template: TemplateProfile = TemplateProfile.defaultProfile(),
        originalTemplateFile: File? = null
    ): File = generatePptxDetailed(model, outputFile, template, originalTemplateFile).file

    fun generatePptxDetailed(
        model: PresentationModel,
        outputFile: File,
        template: TemplateProfile = TemplateProfile.defaultProfile(),
        originalTemplateFile: File? = null
    ): EngineResult {
        if (outputFile.parentFile?.exists() == false) {
            outputFile.parentFile?.mkdirs()
        }

        if (template.useTemplateLayout && originalTemplateFile != null && originalTemplateFile.exists() && originalTemplateFile.length() > 0) {
            val reason = try {
                if (generateFromExistingTemplate(originalTemplateFile, model, outputFile, template.keepTemplateContent)) {
                    return EngineResult(outputFile, templateApplied = true)
                }
                "the template is not a PowerPoint (PPTX) presentation with slide layouts"
            } catch (e: Exception) {
                "the template file could not be read"
            }
            generateDefaultPptx(model, outputFile, template)
            return EngineResult(
                outputFile,
                templateApplied = false,
                notes = listOf("The template layout could not be used ($reason); only its colors and fonts were applied.")
            )
        }

        generateDefaultPptx(model, outputFile, template)
        return EngineResult(outputFile, templateApplied = false)
    }

    // ------------------------------------------------------------------ template mode

    private data class LayoutInfo(val path: String, val type: String?, val placeholders: List<Placeholder>)
    private data class Placeholder(val type: String?, val idx: String?)

    private fun generateFromExistingTemplate(
        templateFile: File,
        model: PresentationModel,
        outputFile: File,
        keepExistingSlides: Boolean
    ): Boolean {
        val entries = readZip(templateFile)
        var presentationXml = entries["ppt/presentation.xml"]?.let { String(it, Charsets.UTF_8) } ?: return false
        var presentationRels = entries["ppt/_rels/presentation.xml.rels"]?.let { String(it, Charsets.UTF_8) } ?: return false
        var contentTypes = entries["[Content_Types].xml"]?.let { String(it, Charsets.UTF_8) } ?: return false

        val layouts = entries.keys
            .filter { Regex("""ppt/slideLayouts/slideLayout\d+\.xml""").matches(it) }
            .sortedBy { Regex("""\d+""").find(it.substringAfterLast('/'))?.value?.toIntOrNull() ?: 0 }
            .map { path -> parseLayout(path, String(entries.getValue(path), Charsets.UTF_8)) }
        if (layouts.isEmpty()) return false

        val titleLayout = layouts.firstOrNull { it.type == "title" } ?: layouts.first()
        val contentLayout = layouts.firstOrNull { it.type == "obj" }
            ?: layouts.firstOrNull { it.type == "tx" || it.type == "txAndObj" }
            ?: layouts.firstOrNull { l -> l.placeholders.any { isBodyPlaceholder(it) } && l.placeholders.any { isTitlePlaceholder(it) } }
            ?: layouts.first()

        // 1. Unless the user chose to keep them, remove the template's own slides (and the parts
        //    that only belong to them). New slides are numbered after any slides that stay.
        var firstSlideNumber = 1
        var nextSlideId = 256
        if (keepExistingSlides) {
            firstSlideNumber = 1 + (entries.keys.mapNotNull {
                Regex("""ppt/slides/slide(\d+)\.xml""").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull()
            }.maxOrNull() ?: 0)
            nextSlideId = 1 + maxOf(
                255,
                Regex("""<p:sldId\b[^>]*\bid="(\d+)"""").findAll(presentationXml)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 255
            )
        } else {
            val removedPrefixes = listOf("ppt/slides/", "ppt/notesSlides/", "ppt/comments/")
            entries.keys.filter { key -> removedPrefixes.any { key.startsWith(it) } }.forEach { entries.remove(it) }
            contentTypes = contentTypes.replace(
                Regex("""<Override[^>]*PartName="/ppt/(slides|notesSlides|comments)/[^"]*"[^>]*/>"""), ""
            )
            presentationRels = presentationRels.replace(
                Regex("""<Relationship[^>]*Type="[^"]*/relationships/slide"[^>]*/>"""), ""
            )
            // Custom shows and slide sections reference the removed slides.
            presentationXml = presentationXml
                .replace(Regex("""<p:custShowLst>[\s\S]*?</p:custShowLst>"""), "")
                .replace(Regex("""<p:ext uri="\{521415D9-36F7-43E2-AB2F-B90AF26B5E84\}">[\s\S]*?</p:ext>"""), "")
        }

        // 2. Add the new slides.
        val usedIds = Regex("""Id="([^"]+)"""").findAll(presentationRels).map { it.groupValues[1] }.toSet()
        val slides = buildList {
            add(SlideSpec(model.title, model.subtitle, emptyList(), null, null, isTitleSlide = true))
            model.slides.forEach { add(SlideSpec(it.title, it.subtitle, it.bulletPoints, it.calloutMetric, it.calloutLabel, false)) }
        }
        val sldIds = StringBuilder()
        val newRels = StringBuilder()
        val newOverrides = StringBuilder()
        slides.forEachIndexed { i, spec ->
            val n = firstSlideNumber + i
            var relId = "rIdSb$n"
            var suffix = 0
            while (relId in usedIds) relId = "rIdSb${n}_${++suffix}"
            val layout = if (spec.isTitleSlide) titleLayout else contentLayout
            entries["ppt/slides/slide$n.xml"] = buildPlaceholderSlideXml(spec, layout).toByteArray(Charsets.UTF_8)
            entries["ppt/slides/_rels/slide$n.xml.rels"] = (
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/${layout.path.substringAfterLast('/')}"/>""" +
                    """</Relationships>"""
                ).toByteArray(Charsets.UTF_8)
            sldIds.append("""<p:sldId id="${nextSlideId + i}" r:id="$relId"/>""")
            newRels.append("""<Relationship Id="$relId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide$n.xml"/>""")
            newOverrides.append("""<Override PartName="/ppt/slides/slide$n.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""")
        }

        val sldIdLst = "<p:sldIdLst>$sldIds</p:sldIdLst>"
        presentationXml = when {
            keepExistingSlides && presentationXml.contains("</p:sldIdLst>") ->
                presentationXml.replace("</p:sldIdLst>", "$sldIds</p:sldIdLst>")
            Regex("""<p:sldIdLst\s*/>""").containsMatchIn(presentationXml) ->
                presentationXml.replace(Regex("""<p:sldIdLst\s*/>"""), sldIdLst)
            presentationXml.contains("<p:sldIdLst>") ->
                presentationXml.replace(Regex("""<p:sldIdLst>[\s\S]*?</p:sldIdLst>"""), sldIdLst)
            presentationXml.contains("<p:sldSz") -> presentationXml.replaceFirst("<p:sldSz", "$sldIdLst<p:sldSz")
            else -> return false
        }
        if (!presentationXml.contains("r:id=") && !presentationXml.contains("xmlns:r=")) return false
        presentationRels = presentationRels.replace("</Relationships>", "$newRels</Relationships>")
        contentTypes = contentTypes
            .replace("</Types>", "$newOverrides</Types>")
            // A .potx declares a template main part; the output must be a normal presentation.
            .replace(
                "application/vnd.openxmlformats-officedocument.presentationml.template.main+xml",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
            )

        entries["ppt/presentation.xml"] = presentationXml.toByteArray(Charsets.UTF_8)
        entries["ppt/_rels/presentation.xml.rels"] = presentationRels.toByteArray(Charsets.UTF_8)
        entries["[Content_Types].xml"] = contentTypes.toByteArray(Charsets.UTF_8)

        writeZip(outputFile, entries)
        return true
    }

    private data class SlideSpec(
        val title: String,
        val subtitle: String,
        val bullets: List<String>,
        val metric: String?,
        val metricLabel: String?,
        val isTitleSlide: Boolean
    )

    private fun parseLayout(path: String, xml: String): LayoutInfo {
        val type = Regex("""<p:sldLayout\b[^>]*\btype="([^"]+)"""").find(xml)?.groupValues?.get(1)
        val phs = Regex("""<p:ph\b([^>]*)/?>""").findAll(xml).map { m ->
            val attrs = m.groupValues[1]
            Placeholder(
                type = Regex("""\btype="([^"]+)"""").find(attrs)?.groupValues?.get(1),
                idx = Regex("""\bidx="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            )
        }.toList()
        return LayoutInfo(path, type, phs)
    }

    private fun isTitlePlaceholder(p: Placeholder) = p.type == "title" || p.type == "ctrTitle"
    private fun isBodyPlaceholder(p: Placeholder) =
        p.type == null || p.type == "body" || p.type == "obj" || p.type == "subTitle"

    private fun buildPlaceholderSlideXml(spec: SlideSpec, layout: LayoutInfo): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">""")
        sb.append("""<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>""")

        val titlePh = layout.placeholders.firstOrNull { isTitlePlaceholder(it) }
        val titleType = titlePh?.type ?: if (spec.isTitleSlide) "ctrTitle" else "title"
        sb.append(placeholderShape(2, "Title", """<p:ph type="$titleType"/>""", listOf(textParagraph(spec.title, bullet = false))))

        val bodyPh = layout.placeholders.firstOrNull { isBodyPlaceholder(it) && it.type != "subTitle" }
            ?: layout.placeholders.firstOrNull { isBodyPlaceholder(it) }
        val paragraphs = buildList {
            if (spec.subtitle.isNotBlank()) add(textParagraph(spec.subtitle, bullet = false))
            spec.bullets.forEach { add(textParagraph(it, bullet = true)) }
            if (!spec.metric.isNullOrBlank()) {
                add(textParagraph(listOfNotNull(spec.metric, spec.metricLabel).joinToString(" — "), bullet = false, bold = true))
            }
        }
        if (paragraphs.isNotEmpty()) {
            if (bodyPh != null) {
                val phXml = buildString {
                    append("<p:ph")
                    if (bodyPh.type != null) append(""" type="${bodyPh.type}"""")
                    append(""" idx="${bodyPh.idx ?: "1"}"/>""")
                }
                sb.append(placeholderShape(3, "Content", phXml, paragraphs))
            } else {
                // Layout without a body placeholder: an explicit text box below the title.
                sb.append("""<p:sp><p:nvSpPr><p:cNvPr id="3" name="Content"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>""")
                sb.append("""<p:spPr><a:xfrm><a:off x="685800" y="1600200"/><a:ext cx="7772400" cy="3200400"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>""")
                sb.append("""<p:txBody><a:bodyPr wrap="square"><a:normAutofit/></a:bodyPr><a:lstStyle/>${paragraphs.joinToString("")}</p:txBody></p:sp>""")
            }
        }
        sb.append("""</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>""")
        return sb.toString()
    }

    private fun placeholderShape(id: Int, name: String, phXml: String, paragraphs: List<String>): String =
        """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr>$phXml</p:nvPr></p:nvSpPr><p:spPr/>""" +
            """<p:txBody><a:bodyPr/><a:lstStyle/>${paragraphs.joinToString("")}</p:txBody></p:sp>"""

    /** A paragraph that inherits the layout's formatting; bullets come from the layout style. */
    private fun textParagraph(text: String, bullet: Boolean, bold: Boolean = false): String {
        val rtl = containsArabic(text)
        val pPrAttrs = if (rtl) """ rtl="1" algn="r"""" else ""
        val pPr = when {
            !bullet -> """<a:pPr marL="0" indent="0"$pPrAttrs><a:buNone/></a:pPr>"""
            rtl -> "<a:pPr$pPrAttrs/>"
            else -> ""
        }
        val lang = if (rtl) "ar-SA" else "en-US"
        val b = if (bold) """ b="1"""" else ""
        return """<a:p>$pPr<a:r><a:rPr lang="$lang"$b dirty="0"/><a:t>${escapeXml(text)}</a:t></a:r></a:p>"""
    }

    // ------------------------------------------------------------------ default mode

    private fun generateDefaultPptx(model: PresentationModel, outputFile: File, template: TemplateProfile) {
        val primaryHex = cleanHex(template.primaryColorHex, "24D6E8")
        val secondaryHex = cleanHex(template.secondaryColorHex, "8665F7")
        val font = escapeXml(template.headingFont)
        val slides = listOf(SlideModel(title = model.title, subtitle = model.subtitle)) + model.slides
        val count = slides.size

        val entries = linkedMapOf<String, ByteArray>()
        fun put(name: String, xml: String) { entries[name] = xml.toByteArray(Charsets.UTF_8) }

        put("[Content_Types].xml", buildContentTypesXml(count))
        put("_rels/.rels", ROOT_RELS)
        put("ppt/presentation.xml", buildPresentationXml(count))
        put("ppt/_rels/presentation.xml.rels", buildPresentationRelsXml(count))
        put("ppt/slideMasters/slideMaster1.xml", SLIDE_MASTER)
        put("ppt/slideMasters/_rels/slideMaster1.xml.rels", SLIDE_MASTER_RELS)
        put("ppt/slideLayouts/slideLayout1.xml", SLIDE_LAYOUT)
        put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", SLIDE_LAYOUT_RELS)
        put("ppt/theme/theme1.xml", buildThemeXml(primaryHex, secondaryHex, font))
        slides.forEachIndexed { i, slide ->
            val n = i + 1
            val xml = if (i == 0) buildTitleSlideXml(slide, primaryHex, font) else buildSlideXml(slide, n, primaryHex, secondaryHex, font)
            put("ppt/slides/slide$n.xml", xml)
            put("ppt/slides/_rels/slide$n.xml.rels", SLIDE_RELS)
        }
        writeZip(outputFile, entries)
    }

    private fun cleanHex(value: String, fallback: String): String {
        val hex = value.removePrefix("#").takeLast(6)
        return if (Regex("""[0-9A-Fa-f]{6}""").matches(hex)) hex.uppercase() else fallback
    }

    private fun buildContentTypesXml(slideCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>""")
        append("""<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>""")
        append("""<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>""")
        append("""<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>""")
        for (i in 1..slideCount) {
            append("""<Override PartName="/ppt/slides/slide$i.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""")
        }
        append("</Types>")
    }

    private fun buildPresentationXml(slideCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" saveSubsetFonts="1">""")
        append("""<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rIdMaster1"/></p:sldMasterIdLst>""")
        append("<p:sldIdLst>")
        for (i in 1..slideCount) append("""<p:sldId id="${255 + i}" r:id="rId$i"/>""")
        append("</p:sldIdLst>")
        append("""<p:sldSz cx="9144000" cy="5143500" type="screen16x9"/><p:notesSz cx="6858000" cy="9144000"/>""")
        append("</p:presentation>")
    }

    private fun buildPresentationRelsXml(slideCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        append("""<Relationship Id="rIdMaster1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>""")
        append("""<Relationship Id="rIdTheme1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="theme/theme1.xml"/>""")
        for (i in 1..slideCount) {
            append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide$i.xml"/>""")
        }
        append("</Relationships>")
    }

    private fun buildThemeXml(primaryHex: String, secondaryHex: String, font: String): String {
        val fill = """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>"""
        val line = """<a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>"""
        val effect = "<a:effectStyle><a:effectLst/></a:effectStyle>"
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="SmartBubble"><a:themeElements>""" +
            """<a:clrScheme name="SmartBubble">""" +
            """<a:dk1><a:srgbClr val="000000"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1>""" +
            """<a:dk2><a:srgbClr val="1F2937"/></a:dk2><a:lt2><a:srgbClr val="F3F4F6"/></a:lt2>""" +
            """<a:accent1><a:srgbClr val="$primaryHex"/></a:accent1><a:accent2><a:srgbClr val="$secondaryHex"/></a:accent2>""" +
            """<a:accent3><a:srgbClr val="10B981"/></a:accent3><a:accent4><a:srgbClr val="F59E0B"/></a:accent4>""" +
            """<a:accent5><a:srgbClr val="EF4444"/></a:accent5><a:accent6><a:srgbClr val="6366F1"/></a:accent6>""" +
            """<a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink>""" +
            """</a:clrScheme>""" +
            """<a:fontScheme name="SmartBubble">""" +
            """<a:majorFont><a:latin typeface="$font"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont>""" +
            """<a:minorFont><a:latin typeface="$font"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont>""" +
            """</a:fontScheme>""" +
            """<a:fmtScheme name="SmartBubble">""" +
            """<a:fillStyleLst>$fill$fill$fill</a:fillStyleLst>""" +
            """<a:lnStyleLst>$line$line$line</a:lnStyleLst>""" +
            """<a:effectStyleLst>$effect$effect$effect</a:effectStyleLst>""" +
            """<a:bgFillStyleLst>$fill$fill$fill</a:bgFillStyleLst>""" +
            """</a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"""
    }

    private fun runProps(text: String, size: Int, colorHex: String, font: String, extra: String = ""): String {
        val lang = if (containsArabic(text)) "ar-SA" else "en-US"
        return """<a:rPr lang="$lang" sz="$size"$extra><a:solidFill><a:srgbClr val="$colorHex"/></a:solidFill><a:latin typeface="$font"/><a:cs typeface="$font"/></a:rPr>"""
    }

    private fun alignedPPr(text: String, align: String? = null, extra: String = "", inner: String = ""): String {
        val rtl = containsArabic(text)
        val attrs = buildString {
            append(extra)
            when {
                rtl -> append(""" rtl="1" algn="${if (align == "ctr") "ctr" else "r"}"""")
                align != null -> append(""" algn="$align"""")
            }
        }
        return if (attrs.isEmpty() && inner.isEmpty()) "" else if (inner.isEmpty()) "<a:pPr$attrs/>" else "<a:pPr$attrs>$inner</a:pPr>"
    }

    private fun textBox(id: Int, name: String, x: Long, y: Long, cx: Long, cy: Long, body: String, geometry: String = "", anchor: String = ""): String =
        """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>""" +
            """<p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>${geometry.ifEmpty { """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom>""" }}</p:spPr>""" +
            """<p:txBody><a:bodyPr wrap="square"$anchor><a:normAutofit/></a:bodyPr><a:lstStyle/>$body</p:txBody></p:sp>"""

    private fun slideOpen(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">""" +
            """<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>"""

    private fun slideClose(): String = """</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""

    private fun buildTitleSlideXml(slide: SlideModel, primaryHex: String, font: String): String = buildString {
        append(slideOpen())
        // Accent bar
        append("""<p:sp><p:nvSpPr><p:cNvPr id="2" name="Accent"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="685800" y="1600200"/><a:ext cx="1200000" cy="60000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="$primaryHex"/></a:solidFill></p:spPr></p:sp>""")
        append(textBox(3, "Title", 685800, 1750000, 7772400, 1100000,
            """<a:p>${alignedPPr(slide.title)}<a:r>${runProps(slide.title, 4000, primaryHex, font, """ b="1"""")}<a:t>${escapeXml(slide.title)}</a:t></a:r></a:p>"""))
        if (slide.subtitle.isNotBlank()) {
            append(textBox(4, "Subtitle", 685800, 2950000, 7772400, 700000,
                """<a:p>${alignedPPr(slide.subtitle)}<a:r>${runProps(slide.subtitle, 2000, "4B5563", font)}<a:t>${escapeXml(slide.subtitle)}</a:t></a:r></a:p>"""))
        }
        append(slideClose())
    }

    private fun buildSlideXml(slide: SlideModel, slideNumber: Int, primaryHex: String, secondaryHex: String, font: String): String = buildString {
        append(slideOpen())
        append(textBox(2, "Title", 685800, 457200, 7772400, 914400,
            """<a:p>${alignedPPr(slide.title)}<a:r>${runProps(slide.title, 3200, primaryHex, font, """ b="1"""")}<a:t>${escapeXml(slide.title)}</a:t></a:r></a:p>"""))
        if (slide.subtitle.isNotBlank()) {
            append(textBox(3, "Subtitle", 685800, 1371600, 7772400, 500000,
                """<a:p>${alignedPPr(slide.subtitle)}<a:r>${runProps(slide.subtitle, 1800, "4B5563", font, """ i="1"""")}<a:t>${escapeXml(slide.subtitle)}</a:t></a:r></a:p>"""))
        }
        if (slide.bulletPoints.isNotEmpty()) {
            val width = if (slide.calloutMetric != null) 5000000L else 7772400L
            val body = slide.bulletPoints.joinToString("") { bullet ->
                """<a:p>${alignedPPr(bullet, extra = """ marL="285750" indent="-285750"""", inner = """<a:buFont typeface="Arial"/><a:buChar char="•"/>""")}""" +
                    """<a:r>${runProps(bullet, 1800, "1F2937", font)}<a:t>${escapeXml(bullet)}</a:t></a:r></a:p>"""
            }
            append(textBox(4, "Bullets", 685800, 2000000, width, 2600000, body))
        }
        if (slide.calloutMetric != null) {
            val metric = slide.calloutMetric
            val label = slide.calloutLabel ?: ""
            val body = """<a:p><a:pPr algn="ctr"/><a:r>${runProps(metric, 3600, "FFFFFF", font, """ b="1"""")}<a:t>${escapeXml(metric)}</a:t></a:r></a:p>""" +
                """<a:p><a:pPr algn="ctr"${if (containsArabic(label)) """ rtl="1"""" else ""}/><a:r>${runProps(label, 1400, "F3F4F6", font)}<a:t>${escapeXml(label)}</a:t></a:r></a:p>"""
            append(textBox(5, "Metric Card", 6000000, 2000000, 2457200, 2200000, body,
                geometry = """<a:prstGeom prst="roundRect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="$secondaryHex"/></a:solidFill>""",
                anchor = """ anchor="ctr""""))
        }
        append(textBox(6, "Slide Number", 7500000, 4700000, 1000000, 300000,
            """<a:p><a:pPr algn="r"/><a:r>${runProps("$slideNumber", 1100, "9CA3AF", font)}<a:t>$slideNumber</a:t></a:r></a:p>"""))
        append(slideClose())
    }

    // ------------------------------------------------------------------ helpers

    private fun readZip(file: File): LinkedHashMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val baos = ByteArrayOutputStream()
                    zip.copyTo(baos)
                    entries[entry.name] = baos.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private fun writeZip(outputFile: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(outputFile)).use { out ->
            // [Content_Types].xml first, as Office expects.
            val ordered = entries.entries.sortedBy { if (it.key == "[Content_Types].xml") 0 else 1 }
            for ((name, data) in ordered) {
                out.putNextEntry(ZipEntry(name))
                out.write(data)
                out.closeEntry()
            }
        }
    }

    private fun containsArabic(text: String) = text.any { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' }

    private fun escapeXml(str: String): String = str
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private companion object {
        const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/></Relationships>"""

        const val GROUP = """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>"""

        const val SLIDE_MASTER = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg><p:spTree>$GROUP</p:spTree></p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/><p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz="3200"/></a:lvl1pPr></p:titleStyle><p:bodyStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:bodyStyle><p:otherStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>"""

        const val SLIDE_MASTER_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/></Relationships>"""

        const val SLIDE_LAYOUT = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" type="blank" preserve="1"><p:cSld name="Blank"><p:spTree>$GROUP</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""

        const val SLIDE_LAYOUT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>"""

        const val SLIDE_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/></Relationships>"""
    }
}
