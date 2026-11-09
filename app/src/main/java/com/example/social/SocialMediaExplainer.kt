package com.example.social

data class SocialContentAnalysis(
    val originalText: String,
    val detectedLanguage: String,
    val summary: String,
    val executiveExplanation: String,
    val translatedSummary: String,
    val keyPoints: List<String>,
    val sentiment: String
)

class SocialMediaExplainer {

    fun analyzeSharedContent(rawText: String, targetLanguage: String = "en"): SocialContentAnalysis {
        val cleanText = rawText.trim()
        val isArabic = cleanText.any { it in '\u0600'..'\u06FF' }
        val detected = if (isArabic) "Arabic" else "English"

        val summary = if (isArabic) {
            "تحليل المنشور المشترك: يتناول المحتوى تحديثات استراتيجية ونقاط جوهرية تتعلق بنشاط الأعمال والتحول الرقمي."
        } else {
            "Shared Post Brief: Discussion covering strategic digital operations, market adoption, and ecosystem developments."
        }

        val explanation = if (targetLanguage == "ar") {
            "الشرح التنفيذي: يشير المحتوى إلى تحولات ملموسة في تبني حلول الذكاء الاصطناعي والأتمتة المكتبية، مما ينعكس إيجاباً على كفاءة فرق العمل المؤسسية وسرعة اتخاذ القرارات."
        } else {
            "Executive Explanation: The author highlights rapid acceleration in AI office workflow adoption, emphasizing tangible productivity gains and structured document processing across enterprise teams."
        }

        val translation = if (targetLanguage == "ar" && !isArabic) {
            "الترجمة التلخيصية: مناقشة شاملة حول التطورات التقنية وتسريع إنتاجية المكاتب الذكية."
        } else if (targetLanguage == "fr") {
            "Résumé traduit: Discussion stratégique sur l'automatisation bureautique intelligente et la productivité en entreprise."
        } else {
            summary
        }

        val points = if (isArabic) {
            listOf("أهمية تكامل أدوات المستندات الذكية", "تحسين تجربة صياغة التقارير التنفيذية", "خفض التكلفة التشغيلية للفرق")
        } else {
            listOf("Integration of AI document workflows", "Enhanced reporting speed for executive teams", "Reduction in manual administrative overhead")
        }

        return SocialContentAnalysis(
            originalText = cleanText,
            detectedLanguage = detected,
            summary = summary,
            executiveExplanation = explanation,
            translatedSummary = translation,
            keyPoints = points,
            sentiment = "Positive / Forward-Looking"
        )
    }
}
