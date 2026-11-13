package com.example.thesis.core

import com.example.ai.providers.Json
import com.example.thesis.model.ElementData
import com.example.thesis.model.ElementType
import com.example.thesis.model.ThesisCodec
import com.example.thesis.model.ThesisElement
import com.example.thesis.model.ThesisMeta
import com.example.thesis.model.ThesisSection
import com.example.thesis.model.ThesisSnapshot

/** An element to insert; its id is fixed when the proposal is created so previews and history match. */
data class NewElement(val id: String, val type: ElementType, val text: String, val data: ElementData = ElementData.EMPTY)

/**
 * One change to a thesis. Operations reference stable ids; [ThesisOperations.apply] produces a new
 * snapshot and never modifies its input. [AFTER_START] inserts before the first item.
 */
sealed class ThesisOp {
    data class AddElements(val sectionId: String, val afterElementId: String?, val elements: List<NewElement>) : ThesisOp()
    data class ReplaceText(val elementId: String, val newText: String) : ThesisOp()
    data class UpdateData(val elementId: String, val data: ElementData) : ThesisOp()
    data class DeleteElement(val elementId: String) : ThesisOp()
    data class MoveElement(val elementId: String, val targetSectionId: String, val afterElementId: String?) : ThesisOp()
    data class AddSection(val section: ThesisSection, val afterSectionId: String?) : ThesisOp()
    data class UpdateSection(val sectionId: String, val title: String? = null, val enabled: Boolean? = null) : ThesisOp()
    data class MoveSection(val sectionId: String, val newParentId: String?, val afterSectionId: String?) : ThesisOp()
    data class DeleteSection(val sectionId: String) : ThesisOp()
    data class UpdateMeta(val meta: ThesisMeta) : ThesisOp()
}

class ThesisOpException(message: String) : Exception(message)

object ThesisOperations {

    const val AFTER_START = "^"

    /** Sections an operation touches (used to detect edits made after a proposal was created). */
    fun touchedSections(ops: List<ThesisOp>, snapshot: ThesisSnapshot): Set<String> = ops.flatMap { op ->
        when (op) {
            is ThesisOp.AddElements -> listOf(op.sectionId)
            is ThesisOp.ReplaceText -> listOfNotNull(snapshot.element(op.elementId)?.sectionId)
            is ThesisOp.UpdateData -> listOfNotNull(snapshot.element(op.elementId)?.sectionId)
            is ThesisOp.DeleteElement -> listOfNotNull(snapshot.element(op.elementId)?.sectionId)
            is ThesisOp.MoveElement -> listOfNotNull(snapshot.element(op.elementId)?.sectionId, op.targetSectionId)
            is ThesisOp.AddSection -> listOfNotNull(op.section.parentId)
            is ThesisOp.UpdateSection -> listOf(op.sectionId)
            is ThesisOp.MoveSection -> listOfNotNull(op.sectionId, op.newParentId)
            is ThesisOp.DeleteSection -> listOf(op.sectionId)
            is ThesisOp.UpdateMeta -> emptyList()
        }
    }.toSet()

    fun apply(snapshot: ThesisSnapshot, ops: List<ThesisOp>): ThesisSnapshot = ops.fold(snapshot) { s, op -> applyOne(s, op) }

    private fun insertAt(order: MutableList<String>, id: String, after: String?) {
        when {
            after == AFTER_START -> order.add(0, id)
            after == null -> order.add(id)
            else -> {
                val k = order.indexOf(after)
                if (k < 0) throw ThesisOpException("Unknown position reference $after")
                order.add(k + 1, id)
            }
        }
    }

    private fun renumberElements(elements: List<ThesisElement>, sectionId: String, order: List<String>): List<ThesisElement> {
        val pos = order.withIndex().associate { it.value to it.index }
        return elements.map { e -> if (e.sectionId == sectionId) e.copy(position = pos[e.id] ?: e.position) else e }
    }

    private fun renumberSections(sections: List<ThesisSection>, parentId: String?, order: List<String>): List<ThesisSection> {
        val pos = order.withIndex().associate { it.value to it.index }
        return sections.map { x -> if (x.parentId == parentId && x.id in pos) x.copy(position = pos[x.id]!!) else x }
    }

    private fun applyOne(s: ThesisSnapshot, op: ThesisOp): ThesisSnapshot = when (op) {
        is ThesisOp.AddElements -> {
            val section = s.section(op.sectionId) ?: throw ThesisOpException("Unknown section ${op.sectionId}")
            if (!section.kind.writable) throw ThesisOpException("Section ${section.kind} is generated automatically")
            val order = s.elementsOf(op.sectionId).map { it.id }.toMutableList()
            var after = op.afterElementId
            val added = op.elements.map { n ->
                if (s.element(n.id) != null) throw ThesisOpException("Element ${n.id} already exists")
                insertAt(order, n.id, after)
                after = n.id
                ThesisElement(n.id, op.sectionId, n.type, 0, n.text, n.data)
            }
            s.copy(elements = renumberElements(s.elements + added, op.sectionId, order))
        }
        is ThesisOp.ReplaceText -> {
            s.element(op.elementId) ?: throw ThesisOpException("Unknown element ${op.elementId}")
            s.copy(elements = s.elements.map { if (it.id == op.elementId) it.copy(text = op.newText) else it })
        }
        is ThesisOp.UpdateData -> {
            s.element(op.elementId) ?: throw ThesisOpException("Unknown element ${op.elementId}")
            s.copy(elements = s.elements.map { if (it.id == op.elementId) it.copy(data = op.data) else it })
        }
        is ThesisOp.DeleteElement -> {
            val e = s.element(op.elementId) ?: throw ThesisOpException("Unknown element ${op.elementId}")
            val rest = s.elements.filter { it.id != op.elementId }
            s.copy(elements = renumberElements(rest, e.sectionId, rest.filter { it.sectionId == e.sectionId }.sortedBy { it.position }.map { it.id }))
        }
        is ThesisOp.MoveElement -> {
            val e = s.element(op.elementId) ?: throw ThesisOpException("Unknown element ${op.elementId}")
            val target = s.section(op.targetSectionId) ?: throw ThesisOpException("Unknown section ${op.targetSectionId}")
            if (!target.kind.writable) throw ThesisOpException("Section ${target.kind} is generated automatically")
            val without = s.elements.filter { it.id != e.id }
            val fromOrder = without.filter { it.sectionId == e.sectionId }.sortedBy { it.position }.map { it.id }
            var elements = renumberElements(without, e.sectionId, fromOrder)
            val toOrder = elements.filter { it.sectionId == target.id }.sortedBy { it.position }.map { it.id }.toMutableList()
            insertAt(toOrder, e.id, op.afterElementId)
            elements = renumberElements(elements + e.copy(sectionId = target.id), target.id, toOrder)
            s.copy(elements = elements)
        }
        is ThesisOp.AddSection -> {
            if (s.section(op.section.id) != null) throw ThesisOpException("Section ${op.section.id} already exists")
            if (op.section.parentId != null && s.section(op.section.parentId) == null) throw ThesisOpException("Unknown parent")
            val order = s.children(op.section.parentId).map { it.id }.toMutableList()
            insertAt(order, op.section.id, op.afterSectionId)
            s.copy(sections = renumberSections(s.sections + op.section, op.section.parentId, order))
        }
        is ThesisOp.UpdateSection -> {
            s.section(op.sectionId) ?: throw ThesisOpException("Unknown section ${op.sectionId}")
            s.copy(sections = s.sections.map {
                if (it.id == op.sectionId) it.copy(title = op.title ?: it.title, enabled = op.enabled ?: it.enabled) else it
            })
        }
        is ThesisOp.MoveSection -> {
            val x = s.section(op.sectionId) ?: throw ThesisOpException("Unknown section ${op.sectionId}")
            if (op.newParentId != null) {
                // A section cannot move into itself or one of its descendants.
                var p: String? = op.newParentId
                while (p != null) {
                    if (p == x.id) throw ThesisOpException("A section cannot be moved inside itself")
                    p = (s.section(p) ?: throw ThesisOpException("Unknown parent")).parentId
                }
            }
            val without = s.sections.filter { it.id != x.id }
            var sections = renumberSections(without, x.parentId, without.filter { it.parentId == x.parentId }.sortedBy { it.position }.map { it.id })
            val order = sections.filter { it.parentId == op.newParentId }.sortedBy { it.position }.map { it.id }.toMutableList()
            insertAt(order, x.id, op.afterSectionId)
            sections = renumberSections(sections + x.copy(parentId = op.newParentId), op.newParentId, order)
            s.copy(sections = sections)
        }
        is ThesisOp.DeleteSection -> {
            val x = s.section(op.sectionId) ?: throw ThesisOpException("Unknown section ${op.sectionId}")
            val removed = HashSet<String>()
            fun collect(id: String) { removed.add(id); s.children(id).forEach { collect(it.id) } }
            collect(x.id)
            val sections = s.sections.filter { it.id !in removed }
            s.copy(
                sections = renumberSections(sections, x.parentId, sections.filter { it.parentId == x.parentId }.sortedBy { it.position }.map { it.id }),
                elements = s.elements.filter { it.sectionId !in removed }
            )
        }
        is ThesisOp.UpdateMeta -> s.copy(meta = op.meta)
    }

    // ------------------------------------------------------------------ JSON

    private fun newElementToMap(n: NewElement) = linkedMapOf("id" to n.id, "type" to n.type.name, "text" to n.text, "data" to ThesisCodec.dataToMap(n.data))
    private fun newElementFromMap(m: Map<String, Any?>) = NewElement(
        m["id"] as String, ElementType.parse(m["type"] as? String), m["text"] as? String ?: "", ThesisCodec.dataFromMap(Json.obj(m["data"]))
    )

    fun encode(ops: List<ThesisOp>): String = Json.write(ops.map { op ->
        when (op) {
            is ThesisOp.AddElements -> linkedMapOf("op" to "add", "sec" to op.sectionId, "after" to op.afterElementId, "items" to op.elements.map(::newElementToMap))
            is ThesisOp.ReplaceText -> linkedMapOf("op" to "replace", "el" to op.elementId, "text" to op.newText)
            is ThesisOp.UpdateData -> linkedMapOf("op" to "data", "el" to op.elementId, "data" to ThesisCodec.dataToMap(op.data))
            is ThesisOp.DeleteElement -> linkedMapOf("op" to "delete", "el" to op.elementId)
            is ThesisOp.MoveElement -> linkedMapOf("op" to "move", "el" to op.elementId, "sec" to op.targetSectionId, "after" to op.afterElementId)
            is ThesisOp.AddSection -> linkedMapOf("op" to "addSection", "section" to ThesisCodec.sectionToMap(op.section), "after" to op.afterSectionId)
            is ThesisOp.UpdateSection -> linkedMapOf("op" to "updateSection", "sec" to op.sectionId, "title" to op.title, "on" to op.enabled)
            is ThesisOp.MoveSection -> linkedMapOf("op" to "moveSection", "sec" to op.sectionId, "parent" to op.newParentId, "after" to op.afterSectionId)
            is ThesisOp.DeleteSection -> linkedMapOf("op" to "deleteSection", "sec" to op.sectionId)
            is ThesisOp.UpdateMeta -> linkedMapOf("op" to "meta", "meta" to ThesisCodec.metaToMap(op.meta))
        }
    })

    fun decode(text: String): List<ThesisOp> = (Json.arr(Json.parse(text)) ?: emptyList()).mapNotNull { raw ->
        val m = Json.obj(raw) ?: return@mapNotNull null
        when (m["op"]) {
            "add" -> ThesisOp.AddElements(m["sec"] as String, m["after"] as? String, Json.arr(m["items"])!!.mapNotNull { Json.obj(it)?.let(::newElementFromMap) })
            "replace" -> ThesisOp.ReplaceText(m["el"] as String, m["text"] as? String ?: "")
            "data" -> ThesisOp.UpdateData(m["el"] as String, ThesisCodec.dataFromMap(Json.obj(m["data"])))
            "delete" -> ThesisOp.DeleteElement(m["el"] as String)
            "move" -> ThesisOp.MoveElement(m["el"] as String, m["sec"] as String, m["after"] as? String)
            "addSection" -> ThesisOp.AddSection(ThesisCodec.sectionFromMap(Json.obj(m["section"])!!), m["after"] as? String)
            "updateSection" -> ThesisOp.UpdateSection(m["sec"] as String, m["title"] as? String, m["on"] as? Boolean)
            "moveSection" -> ThesisOp.MoveSection(m["sec"] as String, m["parent"] as? String, m["after"] as? String)
            "deleteSection" -> ThesisOp.DeleteSection(m["sec"] as String)
            "meta" -> ThesisOp.UpdateMeta(ThesisCodec.metaFromMap(Json.obj(m["meta"])))
            else -> null
        }
    }
}
