package com.xiaoniu.nursing.database

import androidx.room.*
import com.xiaoniu.nursing.model.Option
import com.xiaoniu.nursing.model.Question
import com.xiaoniu.nursing.model.QuestionWithOptions
import com.xiaoniu.nursing.model.QuestionBankStats
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestionDao {

    // ===== 查询 =====

    @Query("SELECT * FROM questions ORDER BY created_at DESC")
    fun getAllQuestions(): Flow<List<Question>>

    @Transaction
    @Query("SELECT * FROM questions ORDER BY created_at DESC")
    fun getAllQuestionsWithOptions(): Flow<List<QuestionWithOptions>>

    @Transaction
    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun getQuestionWithOptions(id: Long): QuestionWithOptions?

    @Query("SELECT * FROM questions WHERE text_hash = :hash LIMIT 1")
    suspend fun getByHash(hash: String): Question?

    @Transaction
    @Query("SELECT * FROM questions WHERE text_hash = :hash LIMIT 1")
    suspend fun getByHashWithOptions(hash: String): QuestionWithOptions?

    @Query("SELECT * FROM questions WHERE question_type = :type")
    suspend fun getByType(type: String): List<Question>

    /** 获取所有题目（不带选项，用于模糊匹配遍历）*/
    @Query("SELECT * FROM questions")
    suspend fun getAllQuestionsList(): List<Question>

    @Transaction
    @Query("SELECT * FROM questions WHERE question_type = :type")
    suspend fun getByTypeWithOptions(type: String): List<QuestionWithOptions>

    @Query("SELECT COUNT(*) FROM questions")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM questions WHERE question_type = :type")
    suspend fun getCountByType(type: String): Int

    suspend fun getStats(): QuestionBankStats {
        return QuestionBankStats(
            total = getCount(),
            singleCount = getCountByType("single"),
            multiCount = getCountByType("multi"),
            judgeCount = getCountByType("judge")
        )
    }

    // ===== 写入 =====

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertQuestion(question: Question): Long

    @Insert
    suspend fun insertOptions(options: List<Option>)

    @Transaction
    suspend fun insertQuestionWithOptions(question: Question, options: List<Option>): Long {
        val id = insertQuestion(question)
        if (id > 0) {
            insertOptions(options.map { it.copy(questionId = id) })
        } else {
            // 题目已存在（hash冲突），更新但不重复插入选项
            val existing = getByHash(question.textHash)
            existing?.let {
                // 更新已有题目的选项（以最新为准）
                deleteOptionsByQuestionId(it.id)
                insertOptions(options.map { opt -> opt.copy(questionId = it.id) })
                return it.id
            }
        }
        return id
    }

    @Query("DELETE FROM options WHERE question_id = :questionId")
    suspend fun deleteOptionsByQuestionId(questionId: Long)

    // ===== 删除 =====

    @Delete
    suspend fun deleteQuestion(question: Question)

    @Query("DELETE FROM questions")
    suspend fun deleteAllQuestions()

    @Query("DELETE FROM options")
    suspend fun deleteAllOptions()

    @Transaction
    suspend fun clearAll() {
        deleteAllOptions()
        deleteAllQuestions()
    }

    // ===== 搜索 =====

    @Query("SELECT * FROM questions WHERE raw_text LIKE '%' || :keyword || '%' LIMIT 50")
    suspend fun searchByKeyword(keyword: String): List<Question>
}
