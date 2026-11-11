package com.example.ai.orchestrator

import com.example.ai.providers.Json
import com.example.document.model.PresentationModel
import com.example.document.model.SheetModel
import com.example.document.model.SlideModel
import com.example.document.model.SpreadsheetModel
import com.example.document.model.WordDocumentModel
import com.example.document.model.WordSection
import com.example.document.model.WordTable

/** Document formats the orchestrator can produce. */
enum class DocumentFormat(val extension: String) {
    DOCX("docx"), PPTX("pptx"), XLSX("xlsx"), PDF("pdf");

    companion object {
        fun parse(value: String?): DocumentFormat? =
            values().firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

/** Structured content produced by the AI, already converted to the engines' models. */
sealed class ParsedDocument {
    abstract val title: String
    abstract val plainText: String

    data class Word(val model: WordDocumentModel, override val plainText: String) : ParsedDocument() {
        override val title get() = model.title
    }

    data class Presentation(val model: PresentationModel, override val plainText: String) : ParsedDocument() {
        override val title get() = model.title
    }

    data class Spreadsheet(val model: SpreadsheetModel, override val plainText: String) : ParsedDocument() {
        override val title get() = model.workbookTitle
    }
}

class DocumentSchemaException(message: String) : Exception(message)

/**
 * Converts the AI's JSON into document models and validates it. Pure Kotlin (unit-testable).
 * Anything that has to be adjusted or dropped is reported in [Result.warnings] so the user is
 * told about it instead of it being silently discarded.
 */
object DocumentSchemaParser {

    data class Result(val document: ParsedDocument, val warnings: List<String>)

    private const val MAX_SHEET_NAME = 31
    private val INVALID_SHEET_CHARS = Regex("""[\[\]:*?/\\]""")
    private val SAFE_FORMULA = Regex("""^(SUM|AVERAGE|MIN|MAX|COUNT|COUNTA)\(([A-Z]{1,3}[0-9]{1,7})(:[A-Z]{1,3}[0-9]{1,7})?\)$""")

    fun parse(rawText: String, format: DocumentFormat, documentDate: String = ""): Result {
        val root = Json.extractObject(rawText)
            ?: throw DocumentSchemaException("The response is not a JSON object.")
        return when (format) {
            DocumentFormat.DOCX, DocumentFormat.PDF -> parseWord(root, documentDate)
            DocumentFormat.PPTX -> parsePresentation(root)
            DocumentFormat.XLSX -> parseSpreadsheet(root)
        }
    }

    private fun text(value: Any?): String = when (value) {
        null -> ""
        is String -> value.trim()
        is Double -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
        is Boolean -> value.toString()
        else -> value.toString().trim()
    }

    private fun strings(value: Any?): List<String> =
        (Json.arr(value) ?: emptyList()).map { text(it) }.filter { it.isNotEmpty() }

    private fun table(value: Any?, warnings: MutableList<String>, where: String): WordTable? {
        val obj = Json.obj(value) ?: return null
        val headers = strings(obj["headers"])
        if (headers.isEmpty()) return null
        val rows = (Json.arr(obj["rows"]) ?: emptyList()).mapNotNull { Json.arr(it) }.map { row ->
            val cells = row.map { text(it) }
            if (cells.size > headers.size) warnings += "$where: extra table cells beyond the header columns were dropped."
            (cells + List((headers.size - cells.size).coerceAtLeast(0)) { "" }).take(headers.size)
        }
        return WordTable(headers, rows)
    }

    private fun parseWord(root: Map<String, Any?>, date: String): Result {
        val warnings = mutableListOf<String>()
        val title = text(root["title"]).ifEmpty { throw DocumentSchemaException("Missing \"title\".") }
        val sections = (Json.arr(root["sections"]) ?: emptyList()).mapNotNull { Json.obj(it) }.mapIndexed { i, s ->
            WordSection(
                heading = text(s["heading"]),
                paragraphs = strings(s["paragraphs"]),
                bulletPoints = strings(s["bullets"]),
                table = table(s["table"], warnings, "Section ${i + 1}")
            )
        }.filter { it.heading.isNotEmpty() || it.paragraphs.isNotEmpty() || it.bulletPoints.isNotEmpty() || it.table != null }
        if (sections.isEmpty()) throw DocumentSchemaException("\"sections\" must contain at least one section with content.")
        val model = WordDocumentModel(
            title = title,
            subtitle = text(root["subtitle"]),
            organization = "",
            date = date,
            sections = sections
        )
        return Result(ParsedDocument.Word(model, plainTextOf(model)), warnings.distinct())
    }

    private fun parsePresentation(root: Map<String, Any?>): Result {
        val title = text(root["title"]).ifEmpty { throw DocumentSchemaException("Missing \"title\".") }
        val slides = (Json.arr(root["slides"]) ?: emptyList()).mapNotNull { Json.obj(it) }.map { s ->
            SlideModel(
                title = text(s["title"]),
                subtitle = text(s["subtitle"]),
                bulletPoints = strings(s["bullets"]),
                calloutMetric = text(s["metric"]).ifEmpty { null },
                calloutLabel = text(s["metric_label"]).ifEmpty { null }
            )
        }.filter { it.title.isNotEmpty() || it.bulletPoints.isNotEmpty() }
        if (slides.isEmpty()) throw DocumentSchemaException("\"slides\" must contain at least one slide.")
        val model = PresentationModel(title = title, subtitle = text(root["subtitle"]), presenter = "", slides = slides)
        val plain = buildString {
            appendLine(model.title)
            if (model.subtitle.isNotBlank()) appendLine(model.subtitle)
            model.slides.forEachIndexed { i, s ->
                appendLine()
                appendLine("${i + 1}. ${s.title}")
                if (s.subtitle.isNotBlank()) appendLine(s.subtitle)
                s.bulletPoints.forEach { appendLine("• $it") }
                if (s.calloutMetric != null) appendLine("${s.calloutMetric} ${s.calloutLabel.orEmpty()}".trim())
            }
        }.trim()
        return Result(ParsedDocument.Presentation(model, plain), emptyList())
    }

    private fun parseSpreadsheet(root: Map<String, Any?>): Result {
        val warnings = mutableListOf<String>()
        val title = text(root["title"]).ifEmpty { throw DocumentSchemaException("Missing \"title\".") }
        val usedNames = mutableSetOf<String>()
        val sheets = (Json.arr(root["sheets"]) ?: emptyList()).mapNotNull { Json.obj(it) }.mapIndexed { i, s ->
            val headers = strings(s["headers"])
            if (headers.isEmpty()) throw DocumentSchemaException("Sheet ${i + 1} has no \"headers\".")
            var name = text(s["name"]).replace(INVALID_SHEET_CHARS, " ").trim().take(MAX_SHEET_NAME)
                .trim('\'').ifEmpty { "Sheet${i + 1}" }
            if (!usedNames.add(name.lowercase())) {
                name = (name.take(MAX_SHEET_NAME - 3) + " ${i + 1}").take(MAX_SHEET_NAME)
                usedNames.add(name.lowercase())
            }
            val rows: List<List<Any>> = (Json.arr(s["rows"]) ?: emptyList()).mapNotNull { Json.arr(it) }.map { row ->
                if (row.size > headers.size) warnings += "Sheet \"$name\": extra cells beyond the header columns were dropped."
                val cells: List<Any> = row.take(headers.size).map { cell ->
                    when (cell) {
                        is Double -> if (cell % 1.0 == 0.0 && kotlin.math.abs(cell) < 1e15) cell.toLong() else cell
                        null -> ""
                        else -> text(cell)
                    }
                }
                cells + List((headers.size - cells.size).coerceAtLeast(0)) { "" }
            }
            var formula = text(s["total_formula"]).removePrefix("=").uppercase().replace(" ", "").ifEmpty { null }
            if (formula != null && !SAFE_FORMULA.matches(formula)) {
                warnings += "Sheet \"$name\": the total formula \"$formula\" is not supported and was left out."
                formula = null
            }
            SheetModel(
                name = name,
                headers = headers,
                rows = rows,
                summaryFormulaLabel = if (formula != null) text(s["total_label"]).ifEmpty { "Total" } else null,
                summaryFormula = formula
            )
        }
        if (sheets.isEmpty()) throw DocumentSchemaException("\"sheets\" must contain at least one sheet.")
        val model = SpreadsheetModel(workbookTitle = title, sheets = sheets)
        val plain = buildString {
            appendLine(model.workbookTitle)
            model.sheets.forEach { sh ->
                appendLine()
                appendLine("[${sh.name}]")
                appendLine(sh.headers.joinToString("\t"))
                sh.rows.forEach { appendLine(it.joinToString("\t")) }
                if (sh.summaryFormula != null) appendLine("${sh.summaryFormulaLabel}: =${sh.summaryFormula}")
            }
        }.trim()
        return Result(ParsedDocument.Spreadsheet(model, plain), warnings.distinct())
    }

    fun plainTextOf(model: WordDocumentModel): String = buildString {
        appendLine(model.title)
        if (model.subtitle.isNotBlank()) appendLine(model.subtitle)
        for (section in model.sections) {
            appendLine()
            if (section.heading.isNotBlank()) appendLine(section.heading)
            section.paragraphs.forEach { appendLine(it) }
            section.bulletPoints.forEach { appendLine("• $it") }
            section.table?.let { t ->
                appendLine(t.headers.joinToString(" | "))
                t.rows.forEach { appendLine(it.joinToString(" | ")) }
            }
        }
    }.trim()
}
