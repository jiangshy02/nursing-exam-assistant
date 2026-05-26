package com.xiaoniu.nursing.model

/**
 * 题目匹配结果
 */
sealed class MatchResult {
    /** 精确匹配成功 */
    data class ExactMatch(
        val question: QuestionWithOptions,
        val confidence: Float = 1.0f
    ) : MatchResult()

    /** 模糊匹配成功 */
    data class FuzzyMatch(
        val question: QuestionWithOptions,
        val confidence: Float,        // 0.0 ~ 1.0
        val matchedText: String       // 匹配到的题库题目文本
    ) : MatchResult()

    /** 未找到匹配 */
    data class NoMatch(
        val screenText: String,
        val reason: String = "题库中未找到该题目"
    ) : MatchResult()
}

/**
 * 从屏幕解析出的题目信息（未入库的原始数据）
 */
data class ParsedQuestion(
    val rawText: String,
    val normalized: String,
    val textHash: String,
    val questionType: QuestionType,
    val options: List<ParsedOption>,
    val source: String = "screen"
)

data class ParsedOption(
    val label: String,       // A/B/C/D/√/×
    val text: String,
    val isCorrect: Boolean = false
)
