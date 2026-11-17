package com.example.smarttemplates

import com.example.template.domain.TemplateProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Catalog, built-in templates, document-intent detection (AR/FR/EN) and default-template rules. */
class SmartTemplatesCoreTest {

    // ------------------------------------------------------------------ catalog and built-ins

    @Test
    fun catalog_hasTheSixCategories_andTheirSubcategories() {
        assertEquals(listOf("reports", "spreadsheets", "academic", "letters", "requests", "presentations"), TemplateCatalog.categories.map { it.id })
        val subs = TemplateCatalog.categories.associate { c -> c.id to c.subcategories.map { it.id.substringAfter('.') } }
        assertEquals(listOf("administrative", "technical", "financial", "academic", "scientific", "project", "research", "general"), subs["reports"])
        assertEquals(listOf("financial", "sales", "inventory", "attendance", "statistics", "project_tracking", "data_analysis", "general"), subs["spreadsheets"])
        assertEquals(listOf("licence", "master", "phd", "research_paper", "project", "scientific_paper"), subs["academic"])
        assertEquals(listOf("recommendation", "business", "administrative", "employment", "cover", "personal", "general"), subs["letters"])
        assertEquals(listOf("job", "university", "administrative", "leave", "permission", "complaint", "general"), subs["requests"])
        assertEquals(listOf("academic", "business", "project", "scientific", "educational", "general"), subs["presentations"])
        // Ids are unique and stable.
        val ids = TemplateCatalog.subcategories.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("tpl_sub_letters_recommendation", TemplateCatalog.subcategoryNameKey("letters.recommendation"))
    }

    @Test
    fun everySubcategory_hasAtLeastTwoBuiltInStyles_ofTheRightFileType() {
        for (c in TemplateCatalog.categories) {
            for (s in c.subcategories) {
                val styles = BuiltInTemplates.forSubcategory(s.id)
                assertTrue("${s.id}: ${styles.size} styles", styles.size >= 2)
                assertTrue(s.id, styles.all { it.kind == c.kind })
                assertNotNull(s.id, BuiltInTemplates.bestFor(s.id))
            }
        }
        assertEquals(16, BuiltInTemplates.all.size)
        assertEquals(BuiltInTemplates.all.size, BuiltInTemplates.all.map { it.id }.toSet().size)
        // Preferred styles name existing subcategories of their own category.
        for (t in BuiltInTemplates.all) for (p in t.preferredFor) assertEquals(t.id, t.categoryId, TemplateCatalog.subcategory(p)?.categoryId)
    }

    @Test
    fun builtInProfiles_areDistinct_A4_andSurviveSaveAndReload() {
        for (t in BuiltInTemplates.all) {
            assertTrue(t.id, t.profile.isBuiltInStyle)
            assertEquals(t.id, t.profile, TemplateProfile.fromJson(t.profile.toJson()))
            if (t.kind == OutputKind.DOCUMENT) assertEquals(595 to 842, t.profile.pageWidthPt to t.profile.pageHeightPt)
        }
        // Styles of one category really differ (font, color or page decoration).
        for (c in TemplateCatalog.categories) {
            val looks = BuiltInTemplates.forCategory(c.id).map { Triple(it.profile.bodyFont, it.profile.primaryColorHex, it.profile.pageBorders) }
            assertEquals(c.id, looks.size, looks.toSet().size)
        }
    }

    // ------------------------------------------------------------------ intent detection

    private fun intent(text: String) = DocumentIntentDetector.detect(text)

    private fun assertIntent(text: String, sub: String, format: String, language: String) {
        val i = intent(text)
        assertNotNull("not detected: $text", i)
        assertEquals(text, sub, i!!.subcategoryId)
        assertEquals(text, sub.substringBefore('.'), i.categoryId)
        assertEquals(text, format, i.format)
        assertEquals(text, language, i.language)
        assertFalse("$text needs clarification (${i.confidence})", i.needsClarification)
    }

    @Test
    fun english_requests() {
        assertIntent("I need a recommendation letter for a Master's student in Geology.", "letters.recommendation", "DOCX", "en")
        assertIntent("Write a business letter to our supplier about the late delivery", "letters.business", "DOCX", "en")
        assertIntent("Write a job application for a data analyst position", "requests.job", "DOCX", "en")
        assertIntent("Prepare a monthly financial report.", "reports.financial", "DOCX", "en")
        assertIntent("Create a technical report on the pump failure", "reports.technical", "DOCX", "en")
        assertIntent("Create an Excel inventory sheet.", "spreadsheets.inventory", "XLSX", "en")
        assertIntent("Make a monthly sales spreadsheet", "spreadsheets.sales", "XLSX", "en")
        assertIntent("Prepare a PowerPoint presentation about renewable energy.", "presentations.scientific", "PPTX", "en")
        assertIntent("Create an academic PowerPoint for my thesis defense", "presentations.academic", "PPTX", "en")
        assertIntent("Prepare a research presentation on groundwater", "presentations.scientific", "PPTX", "en")
        assertIntent("Write the introduction of my master's thesis", "academic.master", "DOCX", "en")
        assertIntent("Write a recommendation letter as a PDF", "letters.recommendation", "PDF", "en")
    }

    @Test
    fun french_requests() {
        assertIntent("Rédige une lettre de recommandation pour mon étudiant", "letters.recommendation", "DOCX", "fr")
        assertIntent("Je veux une lettre commerciale pour un fournisseur", "letters.business", "DOCX", "fr")
        assertIntent("Écris une demande de congé pour la semaine prochaine", "requests.leave", "DOCX", "fr")
        assertIntent("Prépare un rapport financier mensuel", "reports.financial", "DOCX", "fr")
        assertIntent("Crée un tableau Excel des ventes mensuelles", "spreadsheets.sales", "XLSX", "fr")
        assertIntent("Prépare une présentation PowerPoint sur l'énergie renouvelable", "presentations.scientific", "PPTX", "fr")
        assertIntent("Rédige une candidature pour un poste d'ingénieur", "requests.job", "DOCX", "fr")
        assertIntent("Je veux rédiger mon mémoire de master", "academic.master", "DOCX", "fr")
    }

    @Test
    fun arabic_requests() {
        assertIntent("أريد رسالة توصية لطالب ماستر في الجيولوجيا", "letters.recommendation", "DOCX", "ar")
        assertIntent("أنشئ تقريراً مالياً", "reports.financial", "DOCX", "ar")
        assertIntent("أريد جدول Excel للمبيعات", "spreadsheets.sales", "XLSX", "ar")
        assertIntent("حضّر عرض PowerPoint عن الطاقة المتجددة", "presentations.scientific", "PPTX", "ar")
        assertIntent("اكتب طلب توظيف", "requests.job", "DOCX", "ar")
        assertIntent("اكتب تقريرا تقنيا عن الشبكة", "reports.technical", "DOCX", "ar")
        assertIntent("أريد جدول اكسل لجرد المخزون", "spreadsheets.inventory", "XLSX", "ar")
        assertIntent("اكتب شكوى إلى البلدية", "requests.complaint", "DOCX", "ar")
        assertIntent("أريد رسالة ماجستير عن المياه الجوفية", "academic.master", "DOCX", "ar")
    }

    @Test
    fun documentLanguage_isTheOneAskedFor_notTheInterface() {
        assertEquals("en", intent("اكتب رسالة توصية باللغة الإنجليزية")!!.language)
        assertEquals("fr", intent("Write a recommendation letter in French")!!.language)
        assertEquals("ar", intent("Rédige une lettre de recommandation en arabe")!!.language)
    }

    @Test
    fun questionsAndChat_areNotDocumentRequests() {
        assertNull(intent("What is a recommendation letter?"))
        assertNull(intent("Comment écrire une bonne lettre de motivation ?"))
        assertNull(intent("ما هي رسالة التوصية؟"))
        assertNull(intent("Hello, how are you today?"))
        assertNull(intent("Translate this sentence into French"))
    }

    @Test
    fun unclearRequests_askOneQuestionWithChoices() {
        // A thesis without its level.
        val thesis = intent("Write my thesis on groundwater")!!
        assertTrue(thesis.needsClarification)
        assertEquals("academic", thesis.categoryId)
        assertTrue(thesis.choices.containsAll(listOf("academic.licence", "academic.master", "academic.phd")))
        // Two kinds of document named with the same weight.
        val both = intent("Create a report or a presentation about our project")!!
        assertTrue(both.needsClarification)
        assertEquals(setOf("reports.project", "presentations.project"), both.choices.toSet())
        // The answer picks one of the choices, by number or by name.
        assertEquals("academic.master", DocumentIntentDetector.resolveChoice("2", thesis.choices))
        assertEquals("academic.phd", DocumentIntentDetector.resolveChoice("doctorat", thesis.choices))
        assertEquals("academic.master", DocumentIntentDetector.resolveChoice("ماستر", thesis.choices))
        assertNull(DocumentIntentDetector.resolveChoice("banana", thesis.choices))
    }

    @Test
    fun aKindWithoutDetails_usesTheGeneralSubcategory() {
        val i = intent("Write a letter to my landlord")!!
        assertEquals("letters.general", i.subcategoryId)
        assertFalse(i.needsClarification)
        assertTrue(i.missingInfo.isEmpty())
        assertEquals(listOf("candidate name", "your name and position", "relationship"), intent("Write a recommendation letter")!!.missingInfo)
    }

    // ------------------------------------------------------------------ default templates

    private val uploadedLetter = TemplateCandidate("u-letter", "My letterhead", "DOCX", false, "letters", "letters.recommendation")
    private val uploadedDeck = TemplateCandidate("u-deck", "Deck", "PPTX", false, "presentations", null)
    private fun lookup(id: String): TemplateCandidate? = when (id) {
        uploadedLetter.id -> uploadedLetter
        uploadedDeck.id -> uploadedDeck
        else -> BuiltInTemplates.byId(id)?.let { TemplateCandidate(it.id, it.id, it.format, true, it.categoryId, null) }
    }

    @Test
    fun defaults_subcategoryBeatsCategory_beatsBuiltIn() {
        val defaults = mapOf("letters.recommendation" to "u-letter", "letters" to "builtin.letters.formal")
        assertEquals(TemplateChoice("u-letter", SelectionSource.SUBCATEGORY_DEFAULT), TemplateSelection.choose("letters.recommendation", "DOCX", null, defaults, ::lookup))
        // Another subcategory of the same category: the category default.
        assertEquals(TemplateChoice("builtin.letters.formal", SelectionSource.CATEGORY_DEFAULT), TemplateSelection.choose("letters.business", "DOCX", null, defaults, ::lookup))
        // No default at all: the best built-in for the subcategory.
        assertEquals(TemplateChoice("builtin.letters.modern", SelectionSource.BUILT_IN_BEST_MATCH), TemplateSelection.choose("letters.business", "DOCX", null, emptyMap(), ::lookup))
        assertEquals(TemplateChoice("builtin.letters.classic", SelectionSource.BUILT_IN_BEST_MATCH), TemplateSelection.choose("letters.recommendation", "PDF", null, emptyMap(), ::lookup))
        // Independent defaults per subcategory.
        val two = mapOf("reports.financial" to "builtin.reports.minimal", "reports.technical" to "builtin.reports.classic")
        assertEquals("builtin.reports.minimal", TemplateSelection.choose("reports.financial", "DOCX", null, two, ::lookup).templateId)
        assertEquals("builtin.reports.classic", TemplateSelection.choose("reports.technical", "DOCX", null, two, ::lookup).templateId)
    }

    @Test
    fun overrideIsUsedOnce_andUnfitOrMissingDefaultsAreSkipped() {
        val defaults = mapOf("letters.recommendation" to "u-letter")
        assertEquals(TemplateChoice("builtin.letters.modern", SelectionSource.OVERRIDE),
            TemplateSelection.choose("letters.recommendation", "DOCX", "builtin.letters.modern", defaults, ::lookup))
        // The saved default is untouched by the override.
        assertEquals("u-letter", defaults["letters.recommendation"])
        // A PowerPoint template saved as default for letters is never applied to a letter.
        assertEquals(SelectionSource.BUILT_IN_BEST_MATCH, TemplateSelection.choose("letters.recommendation", "DOCX", null, mapOf("letters.recommendation" to "u-deck"), ::lookup).source)
        // A deleted template is skipped.
        assertEquals(SelectionSource.BUILT_IN_BEST_MATCH, TemplateSelection.choose("letters.recommendation", "DOCX", "gone", mapOf("letters" to "gone-too"), ::lookup).source)
        // Spreadsheets and presentations get their own kind of template.
        assertEquals("builtin.spreadsheets.clean", TemplateSelection.choose("spreadsheets.inventory", "XLSX", null, emptyMap(), ::lookup).templateId)
        assertEquals("builtin.presentations.academic", TemplateSelection.choose("presentations.scientific", "PPTX", null, emptyMap(), ::lookup).templateId)
        assertEquals("u-deck", TemplateSelection.choose("presentations.scientific", "PPTX", null, mapOf("presentations" to "u-deck"), ::lookup).templateId)
    }
}
