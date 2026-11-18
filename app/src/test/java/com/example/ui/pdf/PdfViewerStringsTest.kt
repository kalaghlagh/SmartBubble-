package com.example.ui.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.R
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The PDF viewer's and the Smart Templates section's texts exist in English, French and Arabic
 * (not left in English in the other two) and the page counters take their numbers.
 */
@RunWith(RobolectricTestRunner::class)
class PdfViewerStringsTest {

    private val keys = listOf(
        R.string.dx_pdf_viewer_title, R.string.dx_pdf_hint, R.string.dx_pdf_loading, R.string.dx_pdf_open_error,
        R.string.dx_pdf_page_error, R.string.dx_pdf_selected, R.string.dx_pdf_extracted, R.string.dx_pdf_needs_review,
        R.string.st_settings_subtitle, R.string.st_group_automatic, R.string.st_group_new_documents,
        R.string.st_settings_expand, R.string.st_settings_collapse, R.string.st_settings_expanded, R.string.st_settings_collapsed
    )
    private val counters = listOf(R.string.dx_pdf_selected_of, R.string.dx_pdf_page_desc, R.string.dx_pdf_page_selected_desc)

    private fun texts(): List<String> {
        val c: Context = ApplicationProvider.getApplicationContext()
        return keys.map { c.getString(it) } + counters.map { c.getString(it, 3, 12) } + c.getString(R.string.dx_pdf_extract_page, 3)
    }

    private fun english(): List<String> {
        val c: Context = ApplicationProvider.getApplicationContext()
        val conf = android.content.res.Configuration(c.resources.configuration).apply { setLocale(java.util.Locale.ENGLISH) }
        val en = c.createConfigurationContext(conf)
        return keys.map { en.getString(it) } + counters.map { en.getString(it, 3, 12) } + en.getString(R.string.dx_pdf_extract_page, 3)
    }

    private fun check(arabicScript: Boolean) {
        val t = texts(); val en = english()
        t.forEach { assertTrue("empty text", it.isNotBlank()) }
        // Numbers in the locale's digits (Arabic-Indic in Arabic), as String.format writes them.
        val loc = (ApplicationProvider.getApplicationContext() as Context).resources.configuration.locales[0]
        val three = String.format(loc, "%d", 3); val twelve = String.format(loc, "%d", 12)
        counters.indices.forEach { i -> val s = t[keys.size + i]; assertTrue(s, s.contains(three) && s.contains(twelve)) }
        assertTrue(t.last(), t.last().contains(three))
        t.zip(en).forEach { (local, english) -> assertNotEquals("not translated: $english", english, local) }
        if (arabicScript) t.forEach { s -> assertTrue("not Arabic: $s", s.any { it in '؀'..'ۿ' }) }
    }

    @Test @Config(qualifiers = "fr") fun french() = check(arabicScript = false)

    @Test @Config(qualifiers = "ar") fun arabic() = check(arabicScript = true)

    @Test @Config(qualifiers = "en") fun english_counters() {
        val t = texts()
        assertTrue(t.joinToString(), t[keys.size].contains("3") && t[keys.size].contains("12"))
    }
}
