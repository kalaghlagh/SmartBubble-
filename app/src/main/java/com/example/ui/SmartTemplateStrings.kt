package com.example.ui

import com.example.R

/**
 * String resources of the Smart Templates catalog and built-in templates, by id. A real map (not
 * a lookup by resource name at run time) so resource shrinking keeps them and a missing
 * translation fails the build.
 */
object SmartTemplateStrings {
    private val categories = mapOf(
        "reports" to R.string.tpl_cat_reports,
        "spreadsheets" to R.string.tpl_cat_spreadsheets,
        "academic" to R.string.tpl_cat_academic,
        "letters" to R.string.tpl_cat_letters,
        "requests" to R.string.tpl_cat_requests,
        "presentations" to R.string.tpl_cat_presentations,
    )
    private val subcategories = mapOf(
        "reports.administrative" to R.string.tpl_sub_reports_administrative,
        "reports.technical" to R.string.tpl_sub_reports_technical,
        "reports.financial" to R.string.tpl_sub_reports_financial,
        "reports.academic" to R.string.tpl_sub_reports_academic,
        "reports.scientific" to R.string.tpl_sub_reports_scientific,
        "reports.project" to R.string.tpl_sub_reports_project,
        "reports.research" to R.string.tpl_sub_reports_research,
        "reports.general" to R.string.tpl_sub_reports_general,
        "spreadsheets.financial" to R.string.tpl_sub_spreadsheets_financial,
        "spreadsheets.sales" to R.string.tpl_sub_spreadsheets_sales,
        "spreadsheets.inventory" to R.string.tpl_sub_spreadsheets_inventory,
        "spreadsheets.attendance" to R.string.tpl_sub_spreadsheets_attendance,
        "spreadsheets.statistics" to R.string.tpl_sub_spreadsheets_statistics,
        "spreadsheets.project_tracking" to R.string.tpl_sub_spreadsheets_project_tracking,
        "spreadsheets.data_analysis" to R.string.tpl_sub_spreadsheets_data_analysis,
        "spreadsheets.general" to R.string.tpl_sub_spreadsheets_general,
        "academic.licence" to R.string.tpl_sub_academic_licence,
        "academic.master" to R.string.tpl_sub_academic_master,
        "academic.phd" to R.string.tpl_sub_academic_phd,
        "academic.research_paper" to R.string.tpl_sub_academic_research_paper,
        "academic.project" to R.string.tpl_sub_academic_project,
        "academic.scientific_paper" to R.string.tpl_sub_academic_scientific_paper,
        "letters.recommendation" to R.string.tpl_sub_letters_recommendation,
        "letters.business" to R.string.tpl_sub_letters_business,
        "letters.administrative" to R.string.tpl_sub_letters_administrative,
        "letters.employment" to R.string.tpl_sub_letters_employment,
        "letters.cover" to R.string.tpl_sub_letters_cover,
        "letters.personal" to R.string.tpl_sub_letters_personal,
        "letters.general" to R.string.tpl_sub_letters_general,
        "requests.job" to R.string.tpl_sub_requests_job,
        "requests.university" to R.string.tpl_sub_requests_university,
        "requests.administrative" to R.string.tpl_sub_requests_administrative,
        "requests.leave" to R.string.tpl_sub_requests_leave,
        "requests.permission" to R.string.tpl_sub_requests_permission,
        "requests.complaint" to R.string.tpl_sub_requests_complaint,
        "requests.general" to R.string.tpl_sub_requests_general,
        "presentations.academic" to R.string.tpl_sub_presentations_academic,
        "presentations.business" to R.string.tpl_sub_presentations_business,
        "presentations.project" to R.string.tpl_sub_presentations_project,
        "presentations.scientific" to R.string.tpl_sub_presentations_scientific,
        "presentations.educational" to R.string.tpl_sub_presentations_educational,
        "presentations.general" to R.string.tpl_sub_presentations_general,
    )
    private val builtInNames = mapOf(
        "builtin.reports.classic" to R.string.tpl_builtin_reports_classic,
        "builtin.reports.modern" to R.string.tpl_builtin_reports_modern,
        "builtin.reports.minimal" to R.string.tpl_builtin_reports_minimal,
        "builtin.letters.classic" to R.string.tpl_builtin_letters_classic,
        "builtin.letters.modern" to R.string.tpl_builtin_letters_modern,
        "builtin.letters.formal" to R.string.tpl_builtin_letters_formal,
        "builtin.requests.formal" to R.string.tpl_builtin_requests_formal,
        "builtin.requests.modern" to R.string.tpl_builtin_requests_modern,
        "builtin.academic.formal" to R.string.tpl_builtin_academic_formal,
        "builtin.academic.classic" to R.string.tpl_builtin_academic_classic,
        "builtin.spreadsheets.financial" to R.string.tpl_builtin_spreadsheets_financial,
        "builtin.spreadsheets.clean" to R.string.tpl_builtin_spreadsheets_clean,
        "builtin.spreadsheets.minimal" to R.string.tpl_builtin_spreadsheets_minimal,
        "builtin.presentations.academic" to R.string.tpl_builtin_presentations_academic,
        "builtin.presentations.corporate" to R.string.tpl_builtin_presentations_corporate,
        "builtin.presentations.minimal" to R.string.tpl_builtin_presentations_minimal,
    )
    private val builtInDescriptions = mapOf(
        "builtin.reports.classic" to R.string.tpl_builtin_reports_classic_desc,
        "builtin.reports.modern" to R.string.tpl_builtin_reports_modern_desc,
        "builtin.reports.minimal" to R.string.tpl_builtin_reports_minimal_desc,
        "builtin.letters.classic" to R.string.tpl_builtin_letters_classic_desc,
        "builtin.letters.modern" to R.string.tpl_builtin_letters_modern_desc,
        "builtin.letters.formal" to R.string.tpl_builtin_letters_formal_desc,
        "builtin.requests.formal" to R.string.tpl_builtin_requests_formal_desc,
        "builtin.requests.modern" to R.string.tpl_builtin_requests_modern_desc,
        "builtin.academic.formal" to R.string.tpl_builtin_academic_formal_desc,
        "builtin.academic.classic" to R.string.tpl_builtin_academic_classic_desc,
        "builtin.spreadsheets.financial" to R.string.tpl_builtin_spreadsheets_financial_desc,
        "builtin.spreadsheets.clean" to R.string.tpl_builtin_spreadsheets_clean_desc,
        "builtin.spreadsheets.minimal" to R.string.tpl_builtin_spreadsheets_minimal_desc,
        "builtin.presentations.academic" to R.string.tpl_builtin_presentations_academic_desc,
        "builtin.presentations.corporate" to R.string.tpl_builtin_presentations_corporate_desc,
        "builtin.presentations.minimal" to R.string.tpl_builtin_presentations_minimal_desc,
    )

    fun category(id: String): Int = categories[id] ?: R.string.st_none
    fun subcategory(id: String): Int = subcategories[id] ?: categories[id] ?: R.string.st_none
    fun builtInName(id: String): Int = builtInNames[id] ?: R.string.st_none
    fun builtInDescription(id: String): Int = builtInDescriptions[id] ?: R.string.st_none

    /** Every catalog and built-in id has its strings (checked by a unit test). */
    val allIds: Set<String> get() = categories.keys + subcategories.keys + builtInNames.keys
}
