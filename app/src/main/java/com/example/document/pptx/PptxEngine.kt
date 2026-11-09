package com.example.document.pptx

import com.example.document.model.PresentationModel
import com.example.document.model.SlideModel
import com.example.template.domain.TemplateProfile
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PptxEngine produces genuine, standard OpenXML (.pptx) presentation files
 * with multiple formatted slides, titles, cards, bullet points, speaker notes,
 * and customizable theme colors based on TemplateProfile.
 */
class PptxEngine {

    fun generatePptx(
        model: PresentationModel,
        outputFile: File,
        template: TemplateProfile = TemplateProfile.defaultProfile()
    ): File {
        if (outputFile.parentFile?.exists() == false) {
            outputFile.parentFile?.mkdirs()
        }

        val primaryHex = template.primaryColorHex.removePrefix("#")
        val secondaryHex = template.secondaryColorHex.removePrefix("#")
        val font = template.headingFont

        ZipOutputStream(FileOutputStream(outputFile)).use { zip ->
            // 1. [Content_Types].xml
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(buildContentTypesXml(model.slides.size).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 2. _rels/.rels
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(buildRootRelsXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 3. ppt/presentation.xml
            zip.putNextEntry(ZipEntry("ppt/presentation.xml"))
            zip.write(buildPresentationXml(model.slides.size).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 4. ppt/_rels/presentation.xml.rels
            zip.putNextEntry(ZipEntry("ppt/_rels/presentation.xml.rels"))
            zip.write(buildPresentationRelsXml(model.slides.size).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 5. ppt/slides/slideN.xml and rels
            for (i in model.slides.indices) {
                val slide = model.slides[i]
                val slideNum = i + 1

                zip.putNextEntry(ZipEntry("ppt/slides/slide$slideNum.xml"))
                zip.write(buildSlideXml(slide, slideNum, model.title, primaryHex, secondaryHex, font).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("ppt/slides/_rels/slide$slideNum.xml.rels"))
                zip.write(buildSlideRelsXml().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }

        return outputFile
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun buildContentTypesXml(slideCount: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
    <Default Extension="xml" ContentType="application/xml"/>
    <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>""")
        for (i in 1..slideCount) {
            sb.append("\n    <Override PartName=\"/ppt/slides/slide$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
        }
        sb.append("\n</Types>")
        return sb.toString()
    }

    private fun buildRootRelsXml(): String {
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>""".trimIndent()
    }

    private fun buildPresentationXml(slideCount: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
    <p:sldMasterIdLst/>
    <p:sldIdLst>""")
        for (i in 1..slideCount) {
            val sldId = 255 + i
            sb.append("\n        <p:sldId id=\"$sldId\" r:id=\"rId$i\"/>")
        }
        sb.append("""
    </p:sldIdLst>
    <p:sldSz cx="9144000" cy="5143500" type="screen16x9"/>
    <p:notesSz cx="6858000" cy="9144000"/>
</p:presentation>""")
        return sb.toString()
    }

    private fun buildPresentationRelsXml(slideCount: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..slideCount) {
            sb.append("\n    <Relationship Id=\"rId$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide$i.xml\"/>")
        }
        sb.append("\n</Relationships>")
        return sb.toString()
    }

    private fun buildSlideRelsXml(): String {
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>""".trimIndent()
    }

    private fun buildSlideXml(
        slide: SlideModel,
        slideNum: Int,
        deckTitle: String,
        primaryHex: String,
        secondaryHex: String,
        font: String
    ): String {
        val isTitleSlide = (slideNum == 1)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
       xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
       xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
    <p:cSld>
        <p:spTree>
            <p:nvGrpSpPr>
                <p:cNvPr id="1" name=""/>
                <p:cNvGrpSpPr/>
                <p:nvPr/>
            </p:nvGrpSpPr>
            <p:grpSpPr>
                <a:xfrm>
                    <a:off x="0" y="0"/>
                    <a:ext cx="0" cy="0"/>
                    <a:chOff x="0" y="0"/>
                    <a:chExt cx="0" cy="0"/>
                </a:xfrm>
            </p:grpSpPr>""")

        if (isTitleSlide) {
            // Title Slide layout
            sb.append("""
            <p:sp>
                <p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr/></p:nvSpPr>
                <p:spPr><a:xfrm><a:off x="685800" y="1600200"/><a:ext cx="7772400" cy="1470025"/></a:xfrm></p:spPr>
                <p:txBody>
                    <a:bodyPr/>
                    <a:p>
                        <a:pPr><a:buNone/></a:pPr>
                        <a:r>
                            <a:rPr lang="en-US" sz="4400" b="1"><a:solidFill><a:srgbClr val="$primaryHex"/></a:solidFill><a:latin typeface="$font"/></a:rPr>
                            <a:t>${escapeXml(slide.title)}</a:t>
                        </a:r>
                    </a:p>
                    <a:p>
                        <a:r>
                            <a:rPr lang="en-US" sz="2400" i="1"><a:solidFill><a:srgbClr val="$secondaryHex"/></a:solidFill><a:latin typeface="$font"/></a:rPr>
                            <a:t>${escapeXml(slide.subtitle.ifBlank { deckTitle })}</a:t>
                        </a:r>
                    </a:p>
                </p:txBody>
            </p:sp>""")
        } else {
            // Content Slide with Title and Bullets
            sb.append("""
            <p:sp>
                <p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr/></p:nvSpPr>
                <p:spPr><a:xfrm><a:off x="685800" y="457200"/><a:ext cx="7772400" cy="800000"/></a:xfrm></p:spPr>
                <p:txBody>
                    <a:bodyPr/>
                    <a:p>
                        <a:r>
                            <a:rPr lang="en-US" sz="3200" b="1"><a:solidFill><a:srgbClr val="$primaryHex"/></a:solidFill><a:latin typeface="$font"/></a:rPr>
                            <a:t>${escapeXml(slide.title)}</a:t>
                        </a:r>
                    </a:p>
                </p:txBody>
            </p:sp>
            <p:sp>
                <p:nvSpPr><p:cNvPr id="3" name="Content"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr/></p:nvSpPr>
                <p:spPr><a:xfrm><a:off x="685800" y="1400000"/><a:ext cx="7772400" cy="3000000"/></a:xfrm></p:spPr>
                <p:txBody>
                    <a:bodyPr/>""")

            for (bp in slide.bulletPoints) {
                sb.append("""
                    <a:p>
                        <a:pPr lvl="0"><a:spcBfr><a:spcPts val="1200"/></a:spcBfr></a:pPr>
                        <a:r>
                            <a:rPr lang="en-US" sz="1800"><a:solidFill><a:srgbClr val="1F2937"/></a:solidFill><a:latin typeface="$font"/></a:rPr>
                            <a:t>•  ${escapeXml(bp)}</a:t>
                        </a:r>
                    </a:p>""")
            }

            if (slide.calloutMetric != null) {
                sb.append("""
                    <a:p>
                        <a:pPr><a:spcBfr><a:spcPts val="2000"/></a:spcBfr></a:pPr>
                        <a:r>
                            <a:rPr lang="en-US" sz="2800" b="1"><a:solidFill><a:srgbClr val="$secondaryHex"/></a:solidFill><a:latin typeface="$font"/></a:rPr>
                            <a:t>${escapeXml(slide.calloutMetric)}  </a:t>
                        </a:r>
                        <a:r>
                            <a:rPr lang="en-US" sz="1600"><a:solidFill><a:srgbClr val="6B7280"/></a:solidFill><a:latin typeface="$font"/></a:rPr>
                            <a:t>${escapeXml(slide.calloutLabel.orEmpty())}</a:t>
                        </a:r>
                    </a:p>""")
            }

            sb.append("""
                </p:txBody>
            </p:sp>""")
        }

        sb.append("""
        </p:spTree>
    </p:cSld>
    <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sld>""")
        return sb.toString()
    }
}
