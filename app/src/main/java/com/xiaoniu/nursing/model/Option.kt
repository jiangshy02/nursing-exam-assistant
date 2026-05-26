package com.xiaoniu.nursing.model

import androidx.room.*

/**
 * 选项实体
 */
@Entity(
    tableName = "options",
    foreignKeys = [
        ForeignKey(
            entity = Question::class,
            parentColumns = ["id"],
            childColumns = ["question_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("question_id")]
)
data class Option(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "question_id") val questionId: Long,
    @ColumnInfo(name = "label") val label: String,        // A/B/C/D/√/×
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "is_correct") val isCorrect: Boolean = false
)
