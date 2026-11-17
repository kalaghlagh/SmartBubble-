package com.example.extraction

import kotlin.math.abs

/** A drawn straight line on a page (table border), horizontal or vertical. */
data class Ruling(val horizontal: Boolean, val position: Float, val start: Float, val end: Float) {
    val length: Float get() = end - start
}

/** A table found on a page and the lines whose words went into it. */
data class FoundTable(val table: ExtractedBlock.Table, val region: Box, val consumedLines: Set<Int>)

/**
 * Rebuilds tables as rows and columns of cells, from drawn borders when there are any, otherwise
 * from the alignment of words in columns (borderless tables). Cell text is only ever what was
 * read; an empty cell stays empty.
 */
object TableReconstructor {

    // ------------------------------------------------------------------ bordered tables

    private fun cluster(values: List<Float>, tolerance: Float): List<Float> {
        val out = ArrayList<MutableList<Float>>()
        for (v in values.sorted()) {
            val last = out.lastOrNull()
            if (last != null && v - last.last() <= tolerance) last += v else out += mutableListOf(v)
        }
        return out.map { it.average().toFloat() }
    }

    /**
     * Tables drawn with borders: groups of at least two horizontal and two vertical rules that
     * cross. A vertical rule missing over a row merges the cells it would have separated.
     */
    fun fromRulings(rulings: List<Ruling>, lines: List<TextLine>, tolerance: Float = 4f): List<FoundTable> {
        val hs = rulings.filter { it.horizontal && it.length > 0 }
        val vs = rulings.filter { !it.horizontal && it.length > 0 }
        if (hs.size < 2 || vs.size < 2) return emptyList()
        // Connected groups of crossing rules form one table each.
        val all = hs + vs
        val parent = IntArray(all.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) x = parent[x]; return x }
        fun crosses(h: Ruling, v: Ruling) = v.position >= h.start - tolerance && v.position <= h.end + tolerance &&
            h.position >= v.start - tolerance && h.position <= v.end + tolerance
        for (i in hs.indices) for (j in vs.indices) if (crosses(hs[i], vs[j])) {
            val a = find(i); val b = find(hs.size + j); if (a != b) parent[a] = b
        }
        val groups = all.indices.groupBy { find(it) }.values
        val out = ArrayList<FoundTable>()
        for (g in groups) {
            val gh = g.filter { it < hs.size }.map { hs[it] }
            val gv = g.filter { it >= hs.size }.map { vs[it - hs.size] }
            if (gh.size < 2 || gv.size < 2) continue
            val ys = cluster(gh.map { it.position }, tolerance)
            val xs = cluster(gv.map { it.position }, tolerance)
            if (ys.size < 2 || xs.size < 2) continue
            val region = Box(xs.first(), ys.first(), xs.last(), ys.last())
            // Words inside the table, each assigned to the cell that holds its centre.
            val inside = lines.withIndex().filter { (_, l) -> l.box.centerY > region.top && l.box.centerY < region.bottom &&
                l.box.horizontalOverlap(region) > 0.5f }
            val words = inside.flatMap { it.value.words }
            val rtl = ScriptDetector.isRtl(words.joinToString(" ") { it.text })
            val rows = ArrayList<List<TableCell>>()
            for (r in 0 until ys.size - 1) {
                val top = ys[r]; val bottom = ys[r + 1]
                if (bottom - top < tolerance) continue
                val mid = (top + bottom) / 2
                // Column boundaries present in this row (a missing one merges two cells).
                val present = xs.filter { x -> gv.any { abs(it.position - x) <= tolerance && it.start <= mid + tolerance && it.end >= mid - tolerance } }
                val bounds = (listOf(xs.first()) + present + listOf(xs.last())).distinct().sorted()
                val cells = ArrayList<TableCell>()
                for (c in 0 until bounds.size - 1) {
                    val l = bounds[c]; val rgt = bounds[c + 1]
                    val span = xs.count { it > l + tolerance && it < rgt - tolerance } + 1
                    val inCell = words.filter { it.box.centerX in l..rgt && it.box.centerY in top..bottom }
                    cells += TableCell(cellText(inCell, rtl), span)
                }
                rows += if (rtl) cells.reversed() else cells
            }
            if (rows.isEmpty()) continue
            val table = ExtractedBlock.Table(rows, header = rows.size > 1 && looksLikeHeader(rows), rtl = rtl, uncertain = false, detectedBy = "rules")
            out += FoundTable(table, region, inside.map { it.index }.toSet())
        }
        return out
    }

    /** Words of one cell in reading order: by line, then right to left (Arabic) or left to right. */
    private fun cellText(words: List<TextWord>, rtl: Boolean): String {
        if (words.isEmpty()) return ""
        val h = words.map { it.box.height }.sorted()[words.size / 2].coerceAtLeast(1f)
        val lines = ArrayList<MutableList<TextWord>>()
        for (w in words.sortedBy { it.box.centerY }) {
            val line = lines.lastOrNull()
            if (line != null && abs(line.last().box.centerY - w.box.centerY) < h * 0.6f) line += w else lines += mutableListOf(w)
        }
        return lines.joinToString(" ") { l -> (if (rtl) l.sortedByDescending { it.box.left } else l.sortedBy { it.box.left }).joinToString(" ") { it.text } }.trim()
    }

    /** The first row has no numbers while the next rows do, or it is the only text-only row. */
    fun looksLikeHeader(rows: List<List<TableCell>>): Boolean {
        fun numeric(c: TableCell) = c.text.isNotBlank() && c.text.count { ScriptDetector.isDigit(it) } * 2 >= c.text.count { !it.isWhitespace() }
        val first = rows.first()
        if (first.any { numeric(it) }) return false
        return true
    }

    // ------------------------------------------------------------------ borderless tables

    /** Pieces of a line separated by wide gaps (the cells of a table row). */
    fun segments(line: TextLine): List<List<TextWord>> {
        val words = line.words.sortedBy { it.box.left }
        if (words.size < 2) return listOf(words)
        val h = line.box.height.coerceAtLeast(1f)
        val charW = words.sumOf { it.box.width.toDouble() } / words.sumOf { it.text.length.coerceAtLeast(1) }
        val gap = maxOf(h * 1.4f, (charW * 3.5).toFloat())
        val out = ArrayList<MutableList<TextWord>>()
        for (w in words) {
            val cur = out.lastOrNull()
            if (cur != null && w.box.left - cur.last().box.right <= gap) cur += w else out += mutableListOf(w)
        }
        return out
    }

    /**
     * Borderless tables: runs of at least two consecutive lines that split into aligned columns.
     * Two wide columns of running text (a two-column page) are not a table.
     */
    fun fromLayout(lines: List<TextLine>, skip: Set<Int> = emptySet()): List<FoundTable> {
        val segs = lines.map { segments(it) }
        val out = ArrayList<FoundTable>()
        var i = 0
        while (i < lines.size) {
            if (i in skip || segs[i].size < 2) { i++; continue }
            var j = i
            while (j + 1 < lines.size && j + 1 !in skip && segs[j + 1].size >= 2 &&
                lines[j + 1].box.top - lines[j].box.bottom < lines[j].box.height * 2.5f) j++
            if (j > i) build(lines, segs, i..j)?.let { out += it }
            i = j + 1
        }
        return out
    }

    private fun build(lines: List<TextLine>, segs: List<List<List<TextWord>>>, range: IntRange): FoundTable? {
        val rowSegs = range.map { r -> segs[r].map { s -> Box.of(s.map { it.box })!! to s } }
        // Column ranges: overlapping cell spans across all rows merged.
        val spans = rowSegs.flatten().map { it.first.left to it.first.right }.sortedBy { it.first }
        val columns = ArrayList<FloatArray>()
        for ((l, r) in spans) {
            val last = columns.lastOrNull()
            if (last != null && l <= last[1]) last[1] = maxOf(last[1], r) else columns += floatArrayOf(l, r)
        }
        val maxCells = rowSegs.maxOf { it.size }
        if (columns.size < 2) return null
        // Running text in two columns, not a table.
        val wordsPerCell = rowSegs.flatten().map { it.second.size }.average()
        if (columns.size == 2 && wordsPerCell > 6) return null
        val uncertain = columns.size > maxCells || rowSegs.any { it.size < columns.size - 1 }
        val text = rowSegs.flatten().flatMap { it.second }.joinToString(" ") { it.text }
        val rtl = ScriptDetector.isRtl(text)
        val rows = rowSegs.map { segsOfRow ->
            val cells = ArrayList<TableCell>()
            var c = 0
            while (c < columns.size) {
                val col = columns[c]
                val seg = segsOfRow.firstOrNull { (b, _) -> b.left <= col[1] && b.right >= col[0] }
                if (seg == null) { cells += TableCell(""); c++; continue }
                val covered = columns.count { b -> seg.first.left <= b[1] && seg.first.right >= b[0] }.coerceAtLeast(1)
                cells += TableCell(cellText(seg.second, rtl), covered)
                c += covered
            }
            if (rtl) cells.reversed() else cells
        }
        val table = ExtractedBlock.Table(rows, header = rows.size > 1 && looksLikeHeader(rows), rtl = rtl, uncertain = uncertain, detectedBy = "layout")
        val region = Box.of(range.map { lines[it].box })!!
        return FoundTable(table, region, range.toSet())
    }

    // ------------------------------------------------------------------ rules in images

    /**
     * Horizontal and vertical lines drawn in a grey image ([gray] row-major, 0 = black): dark runs
     * much longer than any letter. Thick lines (several pixels) count once.
     */
    fun detectRulings(gray: IntArray, width: Int, height: Int, dark: Int = 128): List<Ruling> {
        val minH = maxOf(60, width / 8)
        val minV = maxOf(40, height / 30)
        val raw = ArrayList<Ruling>()
        for (y in 0 until height) {
            var x = 0
            while (x < width) {
                if (gray[y * width + x] < dark) {
                    val s = x
                    while (x < width && gray[y * width + x] < dark) x++
                    if (x - s >= minH) raw += Ruling(true, y.toFloat(), s.toFloat(), (x - 1).toFloat())
                } else x++
            }
        }
        for (x in 0 until width) {
            var y = 0
            while (y < height) {
                if (gray[y * width + x] < dark) {
                    val s = y
                    while (y < height && gray[y * width + x] < dark) y++
                    if (y - s >= minV) raw += Ruling(false, x.toFloat(), s.toFloat(), (y - 1).toFloat())
                } else y++
            }
        }
        return mergeRulings(raw)
    }

    /** Adjacent parallel rules (the pixel rows of one thick line) merged into one. */
    fun mergeRulings(raw: List<Ruling>, gap: Float = 2f): List<Ruling> {
        val out = ArrayList<Ruling>()
        for (horizontal in listOf(true, false)) {
            val group = raw.filter { it.horizontal == horizontal }.sortedWith(compareBy({ it.position }, { it.start }))
            val merged = ArrayList<MutableList<Ruling>>()
            for (r in group) {
                val m = merged.firstOrNull { list -> list.last().let { l -> r.position - l.position <= gap && r.start <= l.end && r.end >= l.start } }
                if (m != null) m += r else merged += mutableListOf(r)
            }
            for (m in merged) out += Ruling(horizontal, m.map { it.position }.average().toFloat(), m.minOf { it.start }, m.maxOf { it.end })
        }
        return out
    }
}
