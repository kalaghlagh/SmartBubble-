package com.example.smarttemplates

import com.example.document.model.LetterParts
import com.example.document.model.PresentationModel
import com.example.document.model.SheetModel
import com.example.document.model.SlideModel
import com.example.document.model.SpreadsheetModel
import com.example.document.model.WordDocumentModel
import com.example.document.model.WordSection
import com.example.document.model.WordTable

/** Example content used to preview a template (clearly sample text, never sent anywhere). */
object SampleContent {

    fun word(categoryId: String, title: String): WordDocumentModel = when (TemplateCatalog.category(categoryId)?.layout) {
        DocumentLayout.LETTER, DocumentLayout.REQUEST -> WordDocumentModel(
            title = title,
            sections = listOf(WordSection("", listOf(
                "It is my pleasure to write this sample letter. This paragraph shows the body text, its font, size, alignment and spacing as the template lays them out.",
                "A second paragraph shows the space between paragraphs. Your own document will contain the text you ask for in Chat."
            ))),
            letter = LetterParts(
                sender = listOf("Dr. A. Sample", "Department of Geology", "University of Example"),
                recipient = listOf("Admissions Committee", "Graduate School"),
                place = "Example City", date = "1 June 2026",
                subject = "Subject: Sample letter", salutation = "Dear Committee members,",
                closing = "Yours sincerely,", signatureName = "Dr. A. Sample", signatureTitle = "Professor of Geology",
                showTitle = TemplateCatalog.category(categoryId)?.layout == DocumentLayout.REQUEST
            )
        )
        else -> WordDocumentModel(
            title = title,
            subtitle = "Sample document",
            date = "June 2026",
            sections = listOf(
                WordSection("1. Introduction", listOf(
                    "This sample shows the template's page, headings, body text, spacing and tables. Your own document will contain the content you ask for in Chat.",
                    "A second paragraph shows the spacing between paragraphs and the alignment of the body text."
                ), bulletPoints = listOf("First point of a list", "Second point of a list")),
                WordSection("2. Results", listOf("The table below uses the template's table style."),
                    table = WordTable(listOf("Item", "Quantity", "Amount"), listOf(listOf("Alpha", "12", "1,250"), listOf("Beta", "7", "830"), listOf("Gamma", "21", "2,140")))),
                WordSection("3. Conclusion", listOf("This is the end of the sample document."))
            )
        )
    }

    fun sheet(title: String) = SpreadsheetModel(title, listOf(
        SheetModel("Sample", listOf("Item", "Category", "Quantity", "Unit price", "Total"), listOf(
            listOf("Notebook", "Office", 40, 2.5, 100), listOf("Printer paper", "Office", 25, 4.2, 105), listOf("Toner", "Printing", 6, 38, 228),
            listOf("Cables", "IT", 15, 3.9, 58.5), listOf("Headset", "IT", 8, 24, 192)
        ), summaryFormulaLabel = "Total", summaryFormula = "SUM(E2:E6)")
    ))

    fun slides(title: String) = PresentationModel(title, "Sample presentation", slides = listOf(
        SlideModel("Overview", bulletPoints = listOf("What this template looks like", "Titles, text and accents", "Your content goes here")),
        SlideModel("Key figure", bulletPoints = listOf("A slide can highlight one number"), calloutMetric = "42%", calloutLabel = "Sample metric"),
        SlideModel("Next steps", bulletPoints = listOf("Ask for a presentation in Chat", "The default template is applied automatically"))
    ))
}
