package com.xiaoniu.nursing.model

/**
 * 题型枚举
 */
enum class QuestionType(val label: String) {
    SINGLE("单选题"),
    MULTI("多选题"),
    JUDGE("判断题");

    companion object {
        fun fromLabel(label: String): QuestionType = when (label.lowercase()) {
            "single", "单选", "单选题" -> SINGLE
            "multi", "多选", "多选题" -> MULTI
            "judge", "判断", "判断题" -> JUDGE
            else -> SINGLE
        }
    }
}
