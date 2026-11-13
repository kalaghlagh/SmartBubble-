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

    private val literal = Regex("(?:Regex\\(|Pattern\\.compile\\()\\s*(\"\"\"([\\s\\S]*?)\"\"\"|\"((?:[^\"\\\\]|\\\\.)*)\")|(\"\"\"([\\s\\S]*?)\"\"\"|\"((?:[^\"\\\\]|\\\\.)*)\")\\.toRegex\\(")
    private val quantifier = Regex("^\\{\\d+(,\\d*)?\\}")

    /** Kotlin source text of a string literal to its runtime value (only "\\" and "\"" matter here). */
    private fun unescapeKotlin(s: String) = s.replace("\\\\", "\u0000").replace("\\\"", "\"").replace("\u0000", "\\")

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

    @Test
    fun everyRegexInTheAppIsValidOnAndroid() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val problems = mutableListOf<String>()
        var checked = 0
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val src = file.readText()
            literal.findAll(src).forEach { m ->
                val g = m.groupValues
                val raw = when {
                    g[2].isNotEmpty() || g[1].startsWith("\"\"\"") -> g[2]
                    g[1].isNotEmpty() -> unescapeKotlin(g[3])
                    g[5].isNotEmpty() || g[4].startsWith("\"\"\"") -> g[5]
                    else -> unescapeKotlin(g[6])
                }
                if ('$' in raw && Regex("\\$\\{?[A-Za-z_]").containsMatchIn(raw)) return@forEach // string template: not a constant pattern
                checked++
                if (badBraces(raw).isNotEmpty()) problems += "${file.path}:${src.substring(0, m.range.first).count { it == '\n' } + 1}: $raw"
            }
        }
        assertTrue("no regex literals found", checked > 20)
        assertTrue("Regex with a bare { or } (crashes on Android):\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
