package com.example.ocr.paddle

import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min

/** A detected text region in input-map pixels (axis-aligned), with its mean probability. */
data class DetBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val score: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Turns the probability map of the PP-OCRv5 detection model (DB, "differentiable binarization")
 * into text boxes: threshold, connected components, a box per plausible component, enlarged
 * ("unclipped") by area x ratio / perimeter as the model shrinks text regions when it is trained.
 * Pages are straightened before detection, so boxes are axis-aligned.
 *
 * Adapted from MakeACopy's DbPostProcessor (Apache-2.0, Copyright 2026 Christian Kierdorf),
 * axis-aligned path only, thresholds kept: 0.3 / 0.6 / 1.5.
 */
object DbPostProcess {

    const val THRESHOLD = 0.3f
    const val BOX_THRESHOLD = 0.6f
    const val UNCLIP_RATIO = 1.5f
    private const val MIN_AREA = 16
    private const val MIN_SIDE = 3

    fun boxes(prob: FloatArray, width: Int, height: Int, threshold: Float = THRESHOLD, boxThreshold: Float = BOX_THRESHOLD,
              unclipRatio: Float = UNCLIP_RATIO): List<DetBox> {
        require(prob.size >= width * height) { "probability map too small" }
        val visited = BooleanArray(width * height)
        val out = ArrayList<DetBox>()
        val queue = ArrayDeque<Int>()
        for (start in 0 until width * height) {
            if (visited[start] || prob[start] < threshold) continue
            var minX = width; var maxX = -1; var minY = height; var maxY = -1
            var count = 0; var sum = 0.0
            // Thickness of the component: its vertical extent in each column.
            val colTop = HashMap<Int, Int>(); val colBottom = HashMap<Int, Int>()
            visited[start] = true
            queue.add(start)
            while (queue.isNotEmpty()) {
                val i = queue.poll()
                val x = i % width; val y = i / width
                count++; sum += prob[i]
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                colTop[x] = min(colTop[x] ?: y, y); colBottom[x] = max(colBottom[x] ?: y, y)
                if (x + 1 < width) push(i + 1, prob, visited, threshold, queue)
                if (x > 0) push(i - 1, prob, visited, threshold, queue)
                if (y + 1 < height) push(i + width, prob, visited, threshold, queue)
                if (y > 0) push(i - width, prob, visited, threshold, queue)
            }
            val w = maxX - minX + 1
            val h = maxY - minY + 1
            val mean = (sum / count).toFloat()
            if (mean < boxThreshold || count < MIN_AREA || w < MIN_SIDE || h < MIN_SIDE) continue
            // Unclip by the text thickness (90th percentile of the column heights), not the box
            // height, so that a slightly slanted line does not grow too much.
            val spans = colTop.keys.map { colBottom[it]!! - colTop[it]!! + 1 }.sorted()
            val thickness = max(1, spans[min(spans.size - 1, (0.9 * (spans.size - 1)).toInt())]).toFloat()
            val length = max(w, 1).toFloat()
            val d = length * thickness * unclipRatio / (2f * (length + thickness))
            out += DetBox(minX - d, minY - d, maxX + 1 + d, maxY + 1 + d, mean)
        }
        return out
    }

    private fun push(i: Int, prob: FloatArray, visited: BooleanArray, threshold: Float, queue: ArrayDeque<Int>) {
        if (!visited[i] && prob[i] >= threshold) { visited[i] = true; queue.add(i) }
    }
}
