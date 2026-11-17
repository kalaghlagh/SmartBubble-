package com.example.smarttemplates

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.ui.SmartTemplateStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/** Every category, subcategory and built-in template has its own name (and description) in English, French and Arabic. */
@RunWith(RobolectricTestRunner::class)
class SmartTemplatesStringsTest {

    private fun localized(lang: String): Context {
        val base: Context = ApplicationProvider.getApplicationContext()
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale(lang)) }
        return base.createConfigurationContext(config)
    }

    @Test
    fun everyId_hasDistinctNames_inThreeLanguages() {
        val none = R.string.st_none
        for (c in TemplateCatalog.categories) assertNotEquals(c.id, none, SmartTemplateStrings.category(c.id))
        for (s in TemplateCatalog.subcategories) assertNotEquals(s.id, none, SmartTemplateStrings.subcategory(s.id))
        for (b in BuiltInTemplates.all) {
            assertNotEquals(b.id, none, SmartTemplateStrings.builtInName(b.id))
            assertNotEquals(b.id, none, SmartTemplateStrings.builtInDescription(b.id))
        }
        val en = localized("en")
        val fr = localized("fr")
        val ar = localized("ar")
        for (s in TemplateCatalog.subcategories) {
            val res = SmartTemplateStrings.subcategory(s.id)
            val names = listOf(en, fr, ar).map { it.getString(res) }
            assertEquals(s.id, 3, names.toSet().size)
            assertTrue(s.id, names[2].any { it in '؀'..'ۿ' })
        }
        // Subcategory names are unique within each language.
        for (ctx in listOf(en, fr, ar)) {
            val names = TemplateCatalog.subcategories.map { ctx.getString(SmartTemplateStrings.subcategory(it.id)) }
            assertEquals(names.size, names.toSet().size)
        }
        assertEquals("Recommendation Letters", en.getString(SmartTemplateStrings.subcategory("letters.recommendation")))
        assertEquals("رسائل التوصية", ar.getString(SmartTemplateStrings.subcategory("letters.recommendation")))
        assertEquals("Lettres de recommandation", fr.getString(SmartTemplateStrings.subcategory("letters.recommendation")))
    }
}
