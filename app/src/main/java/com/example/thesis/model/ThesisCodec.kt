package com.example.thesis.model

import com.example.ai.providers.Json

/**
 * JSON encoding of thesis content (snapshots stored with each revision, element payloads and
 * change operations). Pure JVM; numbers come back as Double from the parser.
 */
object ThesisCodec {

    private fun s(v: Any?): String = v as? String ?: ""
    private fun i(v: Any?, d: Int = 0): Int = (v as? Number)?.toInt() ?: d
    private fun b(v: Any?): Boolean = v as? Boolean ?: false
    private fun strings(v: Any?): List<String> = Json.arr(v)?.map { it?.toString() ?: "" } ?: emptyList()

    fun dataToMap(d: ElementData): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        if (d.headingLevel != 0) put("h", d.headingLevel)
        if (d.ordered) put("ord", true)
        if (d.marker.isNotEmpty()) put("mk", d.marker)
        if (d.assetId != null) put("asset", d.assetId)
        if (d.caption.isNotEmpty()) put("cap", d.caption)
        if (d.source.isNotEmpty()) put("src", d.source)
        if (d.widthPercent != 80) put("w", d.widthPercent)
        if (d.align != "center") put("al", d.align)
        if (d.tableHeader.isNotEmpty()) put("th", d.tableHeader)
        if (d.tableRows.isNotEmpty()) put("tr", d.tableRows)
        if (d.verified) put("ver", true)
    }

    fun dataFromMap(m: Map<String, Any?>?): ElementData {
        if (m == null || m.isEmpty()) return ElementData.EMPTY
        return ElementData(
            headingLevel = i(m["h"]),
            ordered = b(m["ord"]),
            marker = s(m["mk"]),
            assetId = m["asset"] as? String,
            caption = s(m["cap"]),
            source = s(m["src"]),
            widthPercent = i(m["w"], 80),
            align = (m["al"] as? String) ?: "center",
            tableHeader = strings(m["th"]),
            tableRows = Json.arr(m["tr"])?.map { strings(it) } ?: emptyList(),
            verified = b(m["ver"])
        )
    }

    fun encodeData(d: ElementData): String = dataToMap(d).let { if (it.isEmpty()) "" else Json.write(it) }
    fun decodeData(text: String): ElementData = if (text.isBlank()) ElementData.EMPTY else dataFromMap(Json.obj(Json.parse(text)))

    fun metaToMap(m: ThesisMeta): Map<String, Any?> = linkedMapOf(
        "title" to m.title, "level" to m.level.name, "language" to m.language, "student" to m.studentName,
        "university" to m.university, "faculty" to m.faculty, "department" to m.department,
        "specialization" to m.specialization, "supervisor" to m.supervisor, "coSupervisor" to m.coSupervisor,
        "year" to m.academicYear, "jury" to m.juryMembers, "extra" to m.extraMetadata,
        "logo" to m.logoAssetId, "template" to m.templateId
    )

    fun metaFromMap(m: Map<String, Any?>?): ThesisMeta {
        m ?: return ThesisMeta("")
        return ThesisMeta(
            title = s(m["title"]),
            level = AcademicLevel.values().firstOrNull { it.name == m["level"] } ?: AcademicLevel.MASTER,
            language = (m["language"] as? String) ?: "en",
            studentName = s(m["student"]), university = s(m["university"]), faculty = s(m["faculty"]),
            department = s(m["department"]), specialization = s(m["specialization"]), supervisor = s(m["supervisor"]),
            coSupervisor = s(m["coSupervisor"]), academicYear = s(m["year"]), juryMembers = s(m["jury"]),
            extraMetadata = s(m["extra"]), logoAssetId = m["logo"] as? String, templateId = m["template"] as? String
        )
    }

    fun sectionToMap(x: ThesisSection): Map<String, Any?> = linkedMapOf(
        "id" to x.id, "parent" to x.parentId, "kind" to x.kind.name, "title" to x.title, "pos" to x.position, "on" to x.enabled
    )

    fun sectionFromMap(m: Map<String, Any?>): ThesisSection = ThesisSection(
        id = s(m["id"]), parentId = m["parent"] as? String, kind = SectionKind.parse(m["kind"] as? String),
        title = s(m["title"]), position = i(m["pos"]), enabled = (m["on"] as? Boolean) ?: true
    )

    fun elementToMap(e: ThesisElement): Map<String, Any?> = linkedMapOf(
        "id" to e.id, "sec" to e.sectionId, "type" to e.type.name, "pos" to e.position, "text" to e.text, "data" to dataToMap(e.data)
    )

    fun elementFromMap(m: Map<String, Any?>): ThesisElement = ThesisElement(
        id = s(m["id"]), sectionId = s(m["sec"]), type = ElementType.parse(m["type"] as? String),
        position = i(m["pos"]), text = s(m["text"]), data = dataFromMap(Json.obj(m["data"]))
    )

    fun encodeSnapshot(snapshot: ThesisSnapshot): String = Json.write(
        linkedMapOf(
            "format" to 1,
            "meta" to metaToMap(snapshot.meta),
            "sections" to snapshot.sections.map { sectionToMap(it) },
            "elements" to snapshot.elements.map { elementToMap(it) }
        )
    )

    fun decodeSnapshot(text: String): ThesisSnapshot {
        val root = Json.obj(Json.parse(text)) ?: throw IllegalArgumentException("Invalid thesis snapshot")
        return ThesisSnapshot(
            meta = metaFromMap(Json.obj(root["meta"])),
            sections = Json.arr(root["sections"])?.mapNotNull { Json.obj(it)?.let(::sectionFromMap) } ?: emptyList(),
            elements = Json.arr(root["elements"])?.mapNotNull { Json.obj(it)?.let(::elementFromMap) } ?: emptyList()
        )
    }
}
