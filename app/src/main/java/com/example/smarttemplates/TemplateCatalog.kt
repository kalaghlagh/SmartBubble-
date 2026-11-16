package com.example.smarttemplates

import java.text.Normalizer

/** What kind of file a category produces. */
enum class OutputKind {
    /** Word, with PDF on request. */
    DOCUMENT,
    SPREADSHEET,
    PRESENTATION;

    /** Output formats of this kind, preferred first. */
    val formats: List<String> get() = when (this) {
        DOCUMENT -> listOf("DOCX", "PDF")
        SPREADSHEET -> listOf("XLSX")
        PRESENTATION -> listOf("PPTX")
    }

    /** Template file types that can format a document of this kind (PDF templates as a style reference). */
    val templateFileTypes: Set<String> get() = when (this) {
        DOCUMENT -> setOf("DOCX", "PDF")
        SPREADSHEET -> setOf("XLSX")
        PRESENTATION -> setOf("PPTX")
    }

    companion object {
        fun ofFormat(format: String): OutputKind = when (format.uppercase()) {
            "XLSX" -> SPREADSHEET
            "PPTX" -> PRESENTATION
            else -> DOCUMENT
        }
    }
}

/** How the content of a category is laid out on the page. */
enum class DocumentLayout { REPORT, LETTER, REQUEST, ACADEMIC, SPREADSHEET, PRESENTATION }

/**
 * A subcategory. [keywords] are matched against a normalized request (see [TemplateCatalog.normalize]);
 * [fields] are the facts the document usually needs (asked for or left as marked placeholders).
 */
data class TemplateSubcategory(
    val id: String,
    val categoryId: String,
    val keywords: List<String>,
    val fields: List<String> = emptyList(),
    /** The catch-all subcategory of its category ("General ..."). */
    val general: Boolean = false
)

/** A main category: its document nouns (what makes a request a request for this category). */
data class TemplateCategory(
    val id: String,
    val kind: OutputKind,
    val layout: DocumentLayout,
    val nouns: List<String>,
    val subcategories: List<TemplateSubcategory>
) {
    /** The catch-all subcategory; null when the category has none (a thesis must name its level). */
    val generalSubcategory: TemplateSubcategory? get() = subcategories.firstOrNull { it.general }
}

/**
 * The template library's categories and subcategories, as data. Ids are stable (stored in the
 * database and in settings); display names come from string resources named after the ids
 * ("tpl_cat_<category>", "tpl_sub_<subcategory with '.' replaced by '_'>"). A category or
 * subcategory is added here (plus its three translations) without touching the screens.
 */
object TemplateCatalog {

    private fun sub(category: String, id: String, keywords: List<String>, fields: List<String> = emptyList(), general: Boolean = false) =
        TemplateSubcategory("$category.$id", category, keywords.map(::normalize), fields, general)

    val categories: List<TemplateCategory> = listOf(
        TemplateCategory(
            "reports", OutputKind.DOCUMENT, DocumentLayout.REPORT,
            listOf("report", "reports", "rapport", "rapports", "compte rendu", "compte-rendu", "تقرير", "تقارير", "تقريرا").map(::normalize),
            listOf(
                sub("reports", "administrative", listOf("administrative", "administratif", "administrative report", "اداري", "إداري", "ادارية"), listOf("organization", "period")),
                sub("reports", "technical", listOf("technical", "technique", "technicien", "تقني", "تقنية", "فني", "فنية", "engineering", "ingénierie"), listOf("system", "findings")),
                sub("reports", "financial", listOf("financial", "finance", "finances", "budget", "monthly financial", "financier", "financière", "comptable", "مالي", "مالية", "ميزانية", "محاسبي"), listOf("period", "figures")),
                sub("reports", "academic", listOf("academic", "académique", "universitaire", "أكاديمي", "اكاديمي", "جامعي"), listOf("course", "author")),
                sub("reports", "scientific", listOf("scientific", "scientifique", "laboratory", "lab", "laboratoire", "علمي", "علمية", "مخبري"), listOf("experiment", "results")),
                sub("reports", "project", listOf("project", "progress", "projet", "avancement", "مشروع", "تقدم"), listOf("project name", "period")),
                sub("reports", "research", listOf("research", "study", "recherche", "étude", "بحث", "دراسة"), listOf("topic", "method")),
                sub("reports", "general", emptyList(), general = true)
            )
        ),
        TemplateCategory(
            "spreadsheets", OutputKind.SPREADSHEET, DocumentLayout.SPREADSHEET,
            listOf("excel", "xlsx", "spreadsheet", "spreadsheets", "worksheet", "workbook", "tableur", "feuille de calcul", "classeur",
                "اكسل", "إكسل", "جدول بيانات", "جدول اكسل", "ورقة عمل", "جدول").map(::normalize),
            listOf(
                sub("spreadsheets", "financial", listOf("financial", "finance", "budget", "expenses", "expense", "financier", "dépenses", "comptabilité", "مالي", "مالية", "ميزانية", "مصاريف", "نفقات")),
                sub("spreadsheets", "sales", listOf("sales", "revenue", "revenues", "ventes", "chiffre d'affaires", "recettes", "مبيعات", "المبيعات", "ايرادات", "إيرادات")),
                sub("spreadsheets", "inventory", listOf("inventory", "stock", "stocks", "inventaire", "مخزون", "جرد", "مخزن")),
                sub("spreadsheets", "attendance", listOf("attendance", "schedule", "timesheet", "shift", "présence", "presences", "planning", "emploi du temps", "حضور", "غياب", "جدول زمني", "مواعيد", "دوام")),
                sub("spreadsheets", "statistics", listOf("statistics", "statistical", "statistiques", "statistique", "إحصاء", "احصاء", "إحصائي", "احصائي", "إحصائيات", "احصائيات")),
                sub("spreadsheets", "project_tracking", listOf("project tracking", "tracking", "tasks", "task list", "suivi de projet", "suivi", "tâches", "متابعة", "مهام", "تتبع")),
                sub("spreadsheets", "data_analysis", listOf("data analysis", "analysis", "analyse de données", "analyse", "تحليل بيانات", "تحليل")),
                sub("spreadsheets", "general", emptyList(), general = true)
            )
        ),
        TemplateCategory(
            "academic", OutputKind.DOCUMENT, DocumentLayout.ACADEMIC,
            listOf("thesis", "dissertation", "research paper", "scientific paper", "academic project", "graduation project", "mémoire", "thèse",
                "article scientifique", "article de recherche", "projet de fin d'études", "projet académique", "مذكرة", "مذكرة تخرج", "أطروحة", "اطروحة",
                "رسالة ماجستير", "رسالة دكتوراه", "بحث علمي", "ورقة بحثية", "مقال علمي", "مشروع تخرج").map(::normalize),
            listOf(
                sub("academic", "licence", listOf("licence", "bachelor", "bachelor's", "undergraduate", "ليسانس", "إجازة", "اجازة", "بكالوريوس"), listOf("student", "supervisor")),
                sub("academic", "master", listOf("master", "master's", "masters", "mastère", "ماستر", "ماجستير", "ماجيستير"), listOf("student", "supervisor")),
                sub("academic", "phd", listOf("phd", "ph.d", "doctorate", "doctoral", "dissertation", "doctorat", "thèse", "دكتوراه", "أطروحة", "اطروحة"), listOf("candidate", "supervisor")),
                sub("academic", "research_paper", listOf("research paper", "article de recherche", "ورقة بحثية", "بحث علمي"), listOf("authors", "abstract")),
                sub("academic", "project", listOf("academic project", "graduation project", "projet académique", "projet de fin d'études", "pfe", "مشروع تخرج", "مشروع أكاديمي"), listOf("student", "supervisor")),
                sub("academic", "scientific_paper", listOf("scientific paper", "article scientifique", "مقال علمي"), listOf("authors", "journal"))
            )
        ),
        TemplateCategory(
            "letters", OutputKind.DOCUMENT, DocumentLayout.LETTER,
            listOf("letter", "letters", "lettre", "courrier", "رسالة", "خطاب", "مراسلة").map(::normalize),
            listOf(
                sub("letters", "recommendation", listOf("recommendation", "reference letter", "recommend", "recommandation", "توصية", "تزكية"), listOf("candidate name", "your name and position", "relationship")),
                sub("letters", "business", listOf("business", "commercial", "client", "supplier", "affaires", "fournisseur", "تجارية", "تجاري", "عميل", "مورد"), listOf("company", "recipient")),
                sub("letters", "administrative", listOf("administrative", "official", "administratif", "administrative letter", "officielle", "إدارية", "ادارية", "رسمية"), listOf("administration", "subject")),
                sub("letters", "employment", listOf("employment", "job offer", "offer letter", "resignation", "work certificate", "emploi", "embauche", "démission", "attestation de travail", "استقالة", "شهادة عمل", "عرض عمل", "تعيين"), listOf("employee", "employer")),
                sub("letters", "cover", listOf("cover letter", "motivation letter", "lettre de motivation", "motivation", "رسالة تحفيزية", "رسالة تغطية", "تحفيزية"), listOf("position", "company")),
                sub("letters", "personal", listOf("personal", "friend", "family", "thank you", "personnelle", "ami", "amie", "remerciement", "شخصية", "صديق", "عائلة", "شكر"), listOf("recipient")),
                sub("letters", "general", emptyList(), general = true)
            )
        ),
        TemplateCategory(
            "requests", OutputKind.DOCUMENT, DocumentLayout.REQUEST,
            listOf("application", "request", "complaint", "demande", "candidature", "réclamation", "plainte", "requête", "طلب", "شكوى", "التماس", "استمارة").map(::normalize),
            listOf(
                sub("requests", "job", listOf("job", "position", "employment", "vacancy", "poste", "emploi", "travail", "توظيف", "وظيفة", "عمل", "منصب"), listOf("position", "company", "your experience")),
                sub("requests", "university", listOf("university", "admission", "enrollment", "enrolment", "scholarship", "université", "inscription", "bourse", "جامعة", "جامعي", "قبول", "تسجيل", "منحة"), listOf("university", "program")),
                sub("requests", "administrative", listOf("administrative", "certificate", "document", "administratif", "attestation", "إداري", "اداري", "وثيقة", "شهادة"), listOf("administration", "document requested")),
                sub("requests", "leave", listOf("leave", "vacation", "holiday", "day off", "congé", "conge", "vacances", "إجازة", "اجازة", "عطلة"), listOf("dates", "reason")),
                sub("requests", "permission", listOf("permission", "authorization", "authorisation", "autorisation", "إذن", "اذن", "ترخيص", "رخصة"), listOf("what for", "dates")),
                sub("requests", "complaint", listOf("complaint", "réclamation", "plainte", "شكوى", "تظلم"), listOf("issue", "recipient")),
                sub("requests", "general", emptyList(), general = true)
            )
        ),
        TemplateCategory(
            "presentations", OutputKind.PRESENTATION, DocumentLayout.PRESENTATION,
            listOf("powerpoint", "power point", "pptx", "presentation", "slides", "slide deck", "deck", "présentation", "diaporama", "diapositives",
                "عرض تقديمي", "باوربوينت", "بوربوينت", "باور بوينت", "شرائح", "عرض").map(::normalize),
            listOf(
                sub("presentations", "academic", listOf("academic", "thesis defense", "defense", "soutenance", "académique", "أكاديمي", "اكاديمي", "مناقشة", "جامعة")),
                sub("presentations", "business", listOf("business", "company", "pitch", "corporate", "startup", "marketing", "entreprise", "commercial", "شركة", "تجاري", "أعمال", "اعمال", "تسويق")),
                sub("presentations", "project", listOf("project", "projet", "مشروع")),
                sub("presentations", "scientific", listOf("scientific", "science", "research", "energy", "renewable", "climate", "physics", "biology", "chemistry", "geology",
                    "scientifique", "recherche", "énergie", "renouvelable", "climat", "علمي", "علمية", "علوم", "طاقة", "متجددة", "مناخ", "بحث")),
                sub("presentations", "educational", listOf("educational", "lesson", "course", "class", "students", "teaching", "training", "éducatif", "pédagogique", "cours", "leçon",
                    "formation", "تعليمي", "تعليمية", "درس", "طلاب", "تلاميذ", "تدريب")),
                sub("presentations", "general", emptyList(), general = true)
            )
        )
    )

    private val categoryById = categories.associateBy { it.id }
    private val subcategoryById = categories.flatMap { it.subcategories }.associateBy { it.id }

    val subcategories: List<TemplateSubcategory> get() = subcategoryById.values.toList()

    fun category(id: String?): TemplateCategory? = id?.let { categoryById[it] }
    fun subcategory(id: String?): TemplateSubcategory? = id?.let { subcategoryById[it] }
    fun categoryOf(subcategoryId: String?): TemplateCategory? = subcategory(subcategoryId)?.let { categoryById[it.categoryId] }

    /** Resource name of a category's display name. */
    fun categoryNameKey(id: String) = "tpl_cat_$id"

    /** Resource name of a subcategory's display name. */
    fun subcategoryNameKey(id: String) = "tpl_sub_" + id.replace('.', '_')

    /**
     * Lower case, accents and Arabic diacritics removed, Arabic letter variants unified
     * (أ إ آ -> ا, ة -> ه, ى -> ي), punctuation turned into spaces.
     */
    fun normalize(text: String): String {
        val lower = text.lowercase()
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
            .replace('ة', 'ه').replace('ى', 'ي').replace("ـ", "")
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (c in decomposed) {
            val type = Character.getType(c)
            when {
                type == Character.NON_SPACING_MARK.toInt() -> Unit // accents and Arabic harakat
                c.isLetterOrDigit() || c == '\'' -> sb.append(c)
                else -> sb.append(' ')
            }
        }
        return sb.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }
}
