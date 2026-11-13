package com.example

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android compiles regular expressions with ICU, which rejects a bare "{" or "}" that the desktop
 * JVM (and so Robolectric) accepts. One such pattern in EditorOps made every section editor button
 * and Paste Text crash on phones while every JVM test passed. This test reads every regex literal
 * in the app sources and fails on a brace that is neither escaped, a quantifier ({n}, {n,}, {n,m})
 * nor part of \p{..} / \P{..}.
 */
class RegexPortabilityTest {

    private val quantifier = Regex("^\\{\\d+(,\\d*)?\\}")

    /** Positions of braces that ICU would reject. */
    fun badBraces(rx: String): List<Int> {
        val bad = mutableListOf<Int>()
        var i = 0
        var inClass = false
        while (i < rx.length) {
            val c = rx[i]
            when {
                c == '\\' -> { i += if (i + 1 < rx.length && (rx[i + 1] == 'p' || rx[i + 1] == 'P') && rx.getOrNull(i + 2) == '{') rx.indexOf('}', i).let { if (it < 0) 2 else it - i + 1 } else 2; continue }
                inClass -> if (c == ']') inClass = false
                c == '[' -> { inClass = true; if (rx.getOrNull(i + 1) == '^') i++; if (rx.getOrNull(i + 1) == ']') i++ }
                c == '{' -> { val q = quantifier.find(rx.substring(i)); if (q != null) { i += q.value.length; continue } else bad += i }
                c == '}' -> bad += i
            }
            i++
        }
        return bad
    }

    @Test
    fun checkerFindsTheBraceThatCrashedTheEditor() {
        assertTrue(badBraces("^\\{(center|end|justify)}\\s?").isNotEmpty())
        assertTrue(badBraces("^\\{(center|end|justify)\\}\\s?").isEmpty())
        assertTrue(badBraces("^#{1,6}\\s+\\d{2}x{3,}\\p{L}\\P{Cf}[{}]").isEmpty())
        assertTrue(badBraces("a{b").isNotEmpty())
    }

    /**
     * String literals that are compiled as regexes: the first argument of Regex( / Pattern.compile(,
     * or the receiver of .toRegex(). A hand-written scanner: a JVM regex over whole source files
     * recursed once per character and overflowed the stack (run #35).
     */
    fun regexLiterals(src: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        // Returns (value, endIndexExclusive) of the string literal starting at i, or null.
        fun literalAt(i: Int): Pair<String, Int>? {
            if (src.startsWith("\"\"\"", i)) {
                val close = src.indexOf("\"\"\"", i + 3)
                if (close < 0) return null
                var e = close + 3
                while (e < src.length && src[e] == '"') e++ // a raw string may end with extra quotes
                return src.substring(i + 3, e - 3) to e
            }
            if (i >= src.length || src[i] != '"') return null
            val sb = StringBuilder()
            var j = i + 1
            while (j < src.length && src[j] != '"' && src[j] != '\n') {
                if (src[j] == '\\' && j + 1 < src.length) { sb.append(if (src[j + 1] == '\\' || src[j + 1] == '"') src[j + 1] else "\\" + src[j + 1]); j += 2 } else { sb.append(src[j]); j++ }
            }
            return if (j < src.length && src[j] == '"') sb.toString() to j + 1 else null
        }
        fun line(i: Int) = src.substring(0, i).count { it == '\n' } + 1
        for (opener in listOf("Regex(", "Pattern.compile(")) {
            var at = src.indexOf(opener)
            while (at >= 0) {
                var k = at + opener.length
                while (k < src.length && src[k].isWhitespace()) k++
                literalAt(k)?.let { (v, _) -> out += line(at) to v }
                at = src.indexOf(opener, at + 1)
            }
        }
        // "...".toRegex(): find the literal that ends right before ".toRegex(".
        var at = src.indexOf(".toRegex(")
        while (at >= 0) {
            if (at > 0 && src[at - 1] == '"') {
                val raw = src.lastIndexOf("\"\"\"", at - 4).takeIf { it >= 0 && src.startsWith("\"\"\"", at - 3) }
                val startQuote = raw ?: run {
                    var q = at - 2
                    while (q >= 0 && !(src[q] == '"' && (q == 0 || src[q - 1] != '\\'))) q--
                    q
                }
                if (startQuote >= 0) literalAt(startQuote)?.let { (v, e) -> if (e == at) out += line(at) to v }
            }
            at = src.indexOf(".toRegex(", at + 1)
        }
        return out
    }

    @Test
    fun scannerReadsBothKindsOfLiteral() {
        val src = "val a = Regex(\"\"\"^\\{x}\"\"\")\nval b = Regex(\"a\\\\s{2,}\\\"q\")\nval c = \"\\\\d+\".toRegex()\n"
        val found = regexLiterals(src).map { it.second }
        assertTrue(found.toString(), found == listOf("^\\{x}", "a\\s{2,}\"q", "\\d+"))
    }

    @Test
    fun everyRegexInTheAppIsValidOnAndroid() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val problems = mutableListOf<String>()
        var checked = 0
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            for ((line, raw) in regexLiterals(file.readText())) {
                if (Regex("\\$\\{?[A-Za-z_]").containsMatchIn(raw)) continue // string template: not a constant pattern
                checked++
                if (badBraces(raw).isNotEmpty()) problems += "${file.path}:$line: $raw"
            }
        }
        assertTrue("only $checked regex literals found", checked > 100)
        assertTrue("Regex with a bare { or } (crashes on Android):\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
