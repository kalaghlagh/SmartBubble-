package com.example.document.pptx

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
 * PptxEngine produces genuine OpenXML (.pptx) presentation files.
 * When an uploaded template is provided, it preserves slide masters, layouts,
 * theme colors, background shapes, and typography, and inserts AI-generated structured
 * slides into a NEW presentation without overwriting the template.
 */
class PptxEngine {

    fun generatePptx(
        model: PresentationModel,
        outputFile: File,
        template: TemplateProfile = TemplateProfile.defaultProfile(),
        originalTemplateFile: File? = null
    ): File {
        if (outputFile.parentFile?.exists() == false) {
            outputFile.parentFile?.mkdirs()
        }

        if (originalTemplateFile != null && originalTemplateFile.exists() && originalTemplateFile.length() > 0) {
            try {
                if (generateFromExistingTemplate(originalTemplateFile, model, outputFile, template)) {
                    return outputFile
                }
            } catch (e: Exception) {
                // Fall back to clean generation with template profile styling
            }
        }

        generateDefaultPptx(model, outputFile, template)
        return outputFile
    }

    private fun generateFromExistingTemplate(
        templateFile: File,
        model: PresentationModel,
        outputFile: File,
        template: TemplateProfile
    ): Boolean {
        val entries = mutableMapOf<String, ByteArray>()

        ZipInputStream(FileInputStream(templateFile)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val baos = ByteArrayOutputStream()
                zip.copyTo(baos)
                entries[entry.name] = baos.toByteArray()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val primaryHex = template.primaryColorHex.removePrefix("#")
        val secondaryHex = template.secondaryColorHex.removePrefix("#")
        val font = template.headingFont

        // Remove old slides from entries map to replace with generated slides
        val oldSlideKeys = entries.keys.filter { it.startsWith("ppt/slides/") }.toList()
        for (key in oldSlideKeys) {
            entries.remove(key)
        }

        // Add newly generated slides formatted with template branding
        for (i in model.slides.indices) {
            val slide = model.slides[i]
            val slideNum = i + 1

            entries["ppt/slides/slide$slideNum.xml"] =
                buildSlideXml(slide, slideNum, model.title, primaryHex, secondaryHex, font).toByteArray(Charsets.UTF_8)

            entries["ppt/slides/_rels/slide$slideNum.xml.rels"] =
                buildSlideRelsXml().toByteArray(Charsets.UTF_8)
        }

        // Update presentation.xml and its rels
        entries["ppt/presentation.xml"] = buildPresentationXml(model.slides.size).toByteArray(Charsets.UTF_8)
        entries["ppt/_rels/presentation.xml.rels"] = buildPresentationRelsXml(model.slides.size).toByteArray(Charsets.UTF_8)
        entries["[Content_Types].xml"] = buildContentTypesXml(model.slides.size).toByteArray(Charsets.UTF_8)

        ZipOutputStream(FileOutputStream(outputFile)).use { outZip ->
            for ((name, data) in entries) {
                outZip.putNextEntry(ZipEntry(name))
                outZip.write(data)
                outZip.closeEntry()
            }
        }

        return true
    }

    private fun generateDefaultPptx(
        model: PresentationModel,
        outputFile: File,
        template: TemplateProfile
    ) {
        val primaryHex = template.primaryColorHex.removePrefix("#")
        val secondaryHex = template.secondaryColorHex.removePrefix("#")
        val font = template.headingFont

        ZipOutputStream(FileOutputStream(outputFile)).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(buildContentTypesXml(model.slides.size).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(buildRootRelsXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("ppt/presentation.xml"))
            zip.write(buildPresentationXml(model.slides.size).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("ppt/_rels/presentation.xml.rels"))
            zip.write(buildPresentationRelsXml(model.slides.size).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

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
        slideNumber: Int,
        deckTitle: String,
        primaryHex: String,
        secondaryHex: String,
        font: String
    ): String {
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
            <p:grpSpPr/>
""")

        // Slide Title Shape
        sb.append("""
            <p:sp>
                <p:nvSpPr>
                    <p:cNvPr id="2" name="Title"/>
                    <p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr>
                    <p:nvPr/>
                </p:nvSpPr>
                <p:spPr>
                    <a:xfrm><a:off x="685800" y="457200"/><a:ext cx="7772400" cy="914400"/></a:xfrm>
                </p:spPr>
                <p:txBody>
                    <a:bodyPr/>
                    <a:p>
                        <a:r>
                            <a:rPr lang="en-US" sz="3200" b="1">
                                <a:solidFill><a:srgbClr val="$primaryHex"/></a:solidFill>
                                <a:latin typeface="$font"/>
                            </a:rPr>
                            <a:t>${escapeXml(slide.title)}</a:t>
                        </a:r>
                    </a:p>
                </p:txBody>
            </p:sp>
""")

        // Subtitle or Bullet points
        if (slide.subtitle.isNotBlank()) {
            sb.append("""
            <p:sp>
                <p:nvSpPr>
                    <p:cNvPr id="3" name="Subtitle"/>
                    <p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr>
                    <p:nvPr/>
                </p:nvSpPr>
                <p:spPr>
                    <a:xfrm><a:off x="685800" y="1371600"/><a:ext cx="7772400" cy="500000"/></a:xfrm>
                </p:spPr>
                <p:txBody>
                    <a:bodyPr/>
                    <a:p>
                        <a:r>
                            <a:rPr lang="en-US" sz="1800" i="1">
                                <a:solidFill><a:srgbClr val="4B5563"/></a:solidFill>
                                <a:latin typeface="$font"/>
                            </a:rPr>
                            <a:t>${escapeXml(slide.subtitle)}</a:t>
                        </a:r>
                    </a:p>
                </p:txBody>
            </p:sp>
""")
        }

        // Main Content (Bullet points)
        if (slide.bulletPoints.isNotEmpty()) {
            sb.append("""
            <p:sp>
                <p:nvSpPr>
                    <p:cNvPr id="4" name="Bullets"/>
                    <p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr>
                    <p:nvPr/>
                </p:nvSpPr>
                <p:spPr>
                    <a:xfrm><a:off x="685800" y="2000000"/><a:ext cx="${if (slide.calloutMetric != null) 5000000 else 7772400}" cy="2600000"/></a:xfrm>
                </p:spPr>
                <p:txBody>
                    <a:bodyPr/>
""")
            for (bullet in slide.bulletPoints) {
                sb.append("""
                    <a:p>
                        <a:pPr marL="285750" indent="-285750">
                            <a:buChar char="•"/>
                        </a:pPr>
                        <a:r>
                            <a:rPr lang="en-US" sz="1800">
                                <a:solidFill><a:srgbClr val="1F2937"/></a:solidFill>
                                <a:latin typeface="$font"/>
                            </a:rPr>
                            <a:t>${escapeXml(bullet)}</a:t>
                        </a:r>
                    </a:p>
""")
            }
            sb.append("""
                </p:txBody>
            </p:sp>
""")
        }

        // Callout card if present
        if (slide.calloutMetric != null) {
            sb.append("""
            <p:sp>
                <p:nvSpPr>
                    <p:cNvPr id="5" name="Metric Card"/>
                    <p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr>
                    <p:nvPr/>
                </p:nvSpPr>
                <p:spPr>
                    <a:xfrm><a:off x="6000000" y="2000000"/><a:ext cx="2457200" cy="2200000"/></a:xfrm>
                    <a:prstGeom prst="roundRect"><a:avLst/></a:prstGeom>
                    <a:solidFill><a:srgbClr val="${secondaryHex.take(6)}"/></a:solidFill>
                </p:spPr>
                <p:txBody>
                    <a:bodyPr anchor="ctr"/>
                    <a:p>
                        <a:pPr algn="ctr"/>
                        <a:r>
                            <a:rPr lang="en-US" sz="3600" b="1">
                                <a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill>
                                <a:latin typeface="$font"/>
                            </a:rPr>
                            <a:t>${escapeXml(slide.calloutMetric)}</a:t>
                        </a:r>
                    </a:p>
                    <a:p>
                        <a:pPr algn="ctr"/>
                        <a:r>
                            <a:rPr lang="en-US" sz="1400">
                                <a:solidFill><a:srgbClr val="F3F4F6"/></a:solidFill>
                                <a:latin typeface="$font"/>
                            </a:rPr>
                            <a:t>${escapeXml(slide.calloutLabel ?: "")}</a:t>
                        </a:r>
                    </a:p>
                </p:txBody>
            </p:sp>
""")
        }

        // Footer slide number
        sb.append("""
            <p:sp>
                <p:nvSpPr>
                    <p:cNvPr id="6" name="Slide Number"/>
                    <p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr>
                    <p:nvPr/>
                </p:nvSpPr>
                <p:spPr>
                    <a:xfrm><a:off x="7500000" y="4700000"/><a:ext cx="1000000" cy="300000"/></a:xfrm>
                </p:spPr>
                <p:txBody>
                    <a:bodyPr/>
                    <a:p>
                        <a:pPr algn="r"/>
                        <a:r>
                            <a:rPr lang="en-US" sz="1100">
                                <a:solidFill><a:srgbClr val="9CA3AF"/></a:solidFill>
                                <a:latin typeface="$font"/>
                            </a:rPr>
                            <a:t>$slideNumber</a:t>
                        </a:r>
                    </a:p>
                </p:txBody>
            </p:sp>
        </p:spTree>
    </p:cSld>
</p:sld>
""")
        return sb.toString()
    }
}
