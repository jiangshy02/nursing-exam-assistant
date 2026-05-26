package com.xiaoniu.nursing.model

import androidx.room.*

/**
 * 题目实体
 */
@Entity(
    tableName = "questions",
    indices = [Index("text_hash", unique = true), Index("question_type")]
)
data class Question(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "raw_text") val rawText: String,
    @ColumnInfo(name = "normalized") val normalized: String,
    @ColumnInfo(name = "text_hash") val textHash: String,      // MD5(normalized) 去重用
    @ColumnInfo(name = "question_type") val questionType: String,  // single/multi/judge
    @ColumnInfo(name = "source") val source: String = "collect",
    @ColumnInfo(name = "extra_tags") val extraTags: String? = null, // JSON
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 带选项的题目（用于查询返回）
 */
data class QuestionWithOptions(
    @Embedded val question: Question,
    @Relation(
        parentColumn = "id",
        entityColumn = "question_id"
    )
    val options: List<Option>
) {
    /** 获取所有正确选项的标签 */
    fun correctLabels(): List<String> =
        options.filter { it.isCorrect }.map { it.label }

    /** 获取所有正确选项的文本 */
    fun correctTexts(): List<String> =
        options.filter { it.isCorrect }.map { it.text }
}

/**
 * 题库统计
 */
data class QuestionBankStats(
    val total: Int,
    val singleCount: Int,
    val multiCount: Int,
    val judgeCount: Int
)
