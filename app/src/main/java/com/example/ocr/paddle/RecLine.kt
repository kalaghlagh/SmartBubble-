package com.example.ocr.paddle

/**
 * One character (or dictionary token) read by a recognition model, with its horizontal position
 * in the line crop as a fraction of the crop's width (0 = left edge, 1 = right edge) and the
 * model's probability for it. Positions are in visual order: CTC reads the crop left to right.
 */
data class RecChar(val text: String, val x: Float, val confidence: Float)

/** A line read by one recognition model: raw CTC output in visual (left-to-right) order. */
data class RecLine(
    val text: String,
    val confidence: Float,
    val chars: List<RecChar> = emptyList(),
    /** Width of one CTC time step as a fraction of the crop (character pitch upper bound). */
    val step: Float = 0f,
    /** Which model read it ("arabic", "latin", "merged"). */
    val model: String = ""
)

/** Greedy CTC decoding of PaddleOCR recognition output (blank = class 0). */
object CtcDecoder {

    /**
     * Decodes [logits] laid out as [steps] x [classes] (probabilities or raw scores). [vocab]
     * maps class index to text, index 0 being the blank. [fill] is the share of the input width
     * the crop really occupies (the rest is white padding): positions are reported relative to
     * the crop, not to the padded input.
     */
    fun decode(logits: FloatArray, steps: Int, classes: Int, vocab: List<String>, fill: Float = 1f, model: String = ""): RecLine {
        if (steps <= 0 || classes <= 0) return RecLine("", 0f, model = model)
        require(logits.size >= steps * classes) { "logits too short" }
        val share = fill.coerceIn(0.01f, 1f)
        val softmaxed = isSoftmaxed(logits, steps, classes)
        val sb = StringBuilder()
        val chars = ArrayList<RecChar>()
        var confSum = 0.0
        var prev = -1
        for (t in 0 until steps) {
            val base = t * classes
            var best = 0
            var bestVal = logits[base]
            for (c in 1 until classes) {
                val v = logits[base + c]
                if (v > bestVal) { bestVal = v; best = c }
            }
            if (best != 0 && best != prev) {
                val text = vocab.getOrElse(best) { "" }
                val p = if (softmaxed) bestVal else probability(logits, base, classes, bestVal)
                if (text.isNotEmpty()) {
                    sb.append(text)
                    chars += RecChar(text, ((t + 0.5f) / steps / share).coerceAtMost(1f), p)
                    confSum += p
                }
            }
            prev = best
        }
        return RecLine(
            text = sb.toString(),
            confidence = if (chars.isEmpty()) 0f else (confSum / chars.size).toFloat(),
            chars = chars,
            step = (1f / steps / share).coerceAtMost(1f),
            model = model
        )
    }

    private fun probability(logits: FloatArray, base: Int, classes: Int, max: Float): Float {
        var sum = 0.0
        for (c in 0 until classes) sum += Math.exp((logits[base + c] - max).toDouble())
        return (1.0 / sum).toFloat()
    }

    /** True when the rows already sum to 1 (the exported models end with a softmax). */
    private fun isSoftmaxed(logits: FloatArray, steps: Int, classes: Int): Boolean {
        val rows = minOf(steps, 4)
        for (t in 0 until rows) {
            var sum = 0.0
            for (c in 0 until classes) {
                val v = logits[t * classes + c]
                if (v < -1e-4f || v > 1.0001f) return false
                sum += v
            }
            if (sum < 0.98 || sum > 1.02) return false
        }
        return true
    }

    /**
     * The vocabulary of a recognition model from its dictionary file: blank first, then one
     * token per non-empty line. When the model has one more class than that (the space token of
     * PaddleOCR exports whose dictionary does not list it), a space is appended.
     * @throws IllegalStateException when the dictionary does not match the model
     */
    fun vocabulary(dictLines: List<String>, modelClasses: Int): List<String> {
        val v = ArrayList<String>(dictLines.size + 2)
        v += ""
        for (l in dictLines) if (l.isNotEmpty()) v += l
        if (modelClasses == v.size + 1) v += " "
        check(modelClasses == v.size) { "Dictionary has ${v.size} classes, model has $modelClasses" }
        return v
    }
}
