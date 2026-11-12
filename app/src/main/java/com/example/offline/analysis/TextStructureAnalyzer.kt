package com.example.offline.analysis

import com.example.offline.SourceSpan
import com.example.offline.StructureBlock
import com.example.offline.StructureOrigin
import com.example.offline.TextNormalizationEngine

/** Blocks found in a normalized text plus the structure markers that are not content. */
data class StructureAnalysis(val blocks: List<StructureBlock>, val removedMarkup: List<SourceSpan>)

/**
 * Splits normalized text into headings, paragraphs, lists and tables with deterministic rules.
 * Explicit structure (Markdown, bullets, pipes, tabs) is used first; headings are inferred only
 * above [HeadingDetectionEngine.ACCEPT]. Everything else stays a paragraph: blank lines always
 * separate paragraphs, and lines inside a block are joined only when they look like one wrapped
 * sentence (the line does not end a sentence). Block texts are copied from the source.
 */
class TextStructureAnalyzer {

    /**
     * @param checkCancelled called regularly; throws to stop the analysis (coroutine cancellation)
     */
    fun analyze(text: String, checkCancelled: () -> Unit = {}): StructureAnalysis {
        val lines = SourceLines.split(text)
        val blocks = ArrayList<StructureBlock>()
        val markup = ArrayList<SourceSpan>()
        var i = 0
        while (i < lines.size) {
            if (i % 64 == 0) checkCancelled()
            val line = lines[i]
            if (line.isBlank) { i++; continue }

            val markdown = HeadingDetectionEngine.markdown(line)
            if (markdown != null) {
                blocks.add(
                    StructureBlock.Heading(
                        markdown.text, markdown.level, SourceSpan(line.contentStart, line.contentEnd),
                        markdown.confidence, markdown.origin
                    )
                )
                markup.addAll(markdown.markup)
                i++
                continue
            }

            val underline = lines.getOrNull(i + 1)
            if (underline != null && underline.start > line.start) {
                val h = HeadingDetectionEngine.setext(line, underline)
                if (h != null && ListDetectionEngine.match(line) == null) {
                    blocks.add(StructureBlock.Heading(h.text, h.level, SourceSpan(line.contentStart, underline.contentEnd), h.confidence, h.origin))
                    markup.addAll(h.markup)
                    i += 2
                    continue
                }
            }

            val table = TableDetectionEngine.detect(lines, i)
            if (table != null) {
                val span = SourceSpan(lines[table.startLine].contentStart, lines[table.endLine - 1].contentEnd)
                blocks.add(StructureBlock.Table(table.header, table.rows, table.kind, span, table.confidence, StructureOrigin.EXPLICIT))
                markup.addAll(table.markup)
                i = table.endLine
                continue
            }

            if (ListDetectionEngine.match(line) != null) {
                val run = ListDetectionEngine.collect(lines, i, text)
                if (run != null) {
                    val span = SourceSpan(run.items.first().span.start, run.items.last().span.end)
                    blocks.add(StructureBlock.ListBlock(run.ordered, run.items, span, ListDetectionEngine.confidence(run), StructureOrigin.EXPLICIT))
                    markup.addAll(run.bulletMarkers)
                    i = run.endLine
                    continue
                }
            }

            // Prose block: lines up to a blank line or the start of another structure.
            var j = i + 1
            while (j < lines.size && !lines[j].isBlank && !startsStructure(lines, j, text)) j++
            val blockLines = lines.subList(i, j)
            val followedByContent = blockLines.size > 1 || (j until lines.size).any { !lines[it].isBlank }
            val candidate = HeadingDetectionEngine.evaluate(
                line = line,
                standalone = blockLines.size == 1,
                nextLine = blockLines.getOrNull(1),
                followedByContent = followedByContent,
                isFirstBlock = blocks.isEmpty()
            )
            val proseLines = if (candidate != null && candidate.confidence >= HeadingDetectionEngine.ACCEPT) {
                blocks.add(
                    StructureBlock.Heading(
                        candidate.text, candidate.level, SourceSpan(line.contentStart, line.contentEnd),
                        candidate.confidence, candidate.origin
                    )
                )
                blockLines.drop(1)
            } else blockLines
            paragraphsOf(proseLines).forEach { blocks.add(it) }
            i = j
        }
        return StructureAnalysis(markTitle(blocks, text), markup)
    }

    /** True when line [j] begins a heading, table or list (ending the current prose block). */
    private fun startsStructure(lines: List<SourceLine>, j: Int, text: String): Boolean {
        val line = lines[j]
        if (HeadingDetectionEngine.markdown(line) != null) return true
        if (TableDetectionEngine.detect(lines, j) != null) return true
        return ListDetectionEngine.match(line) != null && ListDetectionEngine.collect(lines, j, text) != null
    }

    /** Joins wrapped lines into paragraphs; a line that ends a sentence ends its paragraph. */
    private fun paragraphsOf(lines: List<SourceLine>): List<StructureBlock.Paragraph> {
        if (lines.isEmpty()) return emptyList()
        val out = ArrayList<StructureBlock.Paragraph>()
        var start = 0
        for (k in 1..lines.size) {
            val breakHere = k == lines.size || !continues(lines[k - 1], lines[k])
            if (breakHere) {
                val group = lines.subList(start, k)
                val text = TextNormalizationEngine.collapseSpaces(group.joinToString(" ") { it.trimmed })
                out.add(StructureBlock.Paragraph(text, SourceSpan(group.first().contentStart, group.last().contentEnd)))
                start = k
            }
        }
        return out
    }

    private fun continues(previous: SourceLine, next: SourceLine): Boolean {
        if (TextTraits.startsLowercaseLatin(next.trimmed)) return true
        if (TextTraits.endsWithTerminal(previous.trimmed) || previous.trimmed.endsWith(":")) return false
        return previous.trimmed.length >= 30
    }

    /** The first heading is the document title when it opens the text and is not a numbered section. */
    private fun markTitle(blocks: List<StructureBlock>, text: String): List<StructureBlock> {
        val first = blocks.firstOrNull() as? StructureBlock.Heading ?: return blocks
        if (blocks.size < 2) return blocks
        if (first.confidence < HeadingDetectionEngine.ACCEPT) return blocks
        if (first.level != null && first.level > 1) return blocks
        if (Regex("""^(\d|[IVXLC]+[.)])""").containsMatchIn(first.text)) return blocks
        if (text.substring(0, first.span.start).isNotBlank()) return blocks
        return listOf(first.copy(isTitle = true)) + blocks.drop(1)
    }
}
