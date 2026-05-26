package com.xiaoniu.nursing.database

import android.content.Context
import com.google.gson.GsonBuilder
import com.xiaoniu.nursing.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 题库仓库 — 数据库操作入口
 */
class QuestionRepository(private val context: Context) {

    private val dao = AppDatabase.getInstance(context).questionDao()
    private val gson = GsonBuilder().setPrettyPrinting().create()

    // ===== 查询 =====

    fun getAllQuestions(): Flow<List<Question>> = dao.getAllQuestions()

    fun getAllQuestionsWithOptions(): Flow<List<QuestionWithOptions>> =
        dao.getAllQuestionsWithOptions()

    suspend fun getByHash(hash: String): QuestionWithOptions? = dao.getByHashWithOptions(hash)

    suspend fun getStats(): QuestionBankStats = dao.getStats()

    // ===== 保存（带去重） =====

    suspend fun saveQuestion(parsed: ParsedQuestion): Long = withContext(Dispatchers.IO) {
        val question = Question(
            rawText = parsed.rawText,
            normalized = parsed.normalized,
            textHash = parsed.textHash,
            questionType = parsed.questionType.name.lowercase(),
            source = parsed.source
        )
        val options = parsed.options.map {
            Option(label = it.label, text = it.text, isCorrect = it.isCorrect)
        }
        dao.insertQuestionWithOptions(question, options)
    }

    // ===== 删除 =====

    suspend fun clearAll() = dao.clearAll()

    // ===== 导出 =====

    suspend fun exportToJson(): String = withContext(Dispatchers.IO) {
        val list = mutableListOf<ExportItem>()
        dao.getAllQuestionsWithOptions().collect { questions ->
            questions.forEach { q ->
                list.add(ExportItem(
                    question = q.question.rawText,
                    type = q.question.questionType,
                    options = q.options.map {
                        ExportOption(label = it.label, text = it.text, correct = it.isCorrect)
                    }
                ))
            }
            return@collect
        }
        gson.toJson(list)
    }

    // ===== 导入 =====

    suspend fun importFromJson(json: String): Int = withContext(Dispatchers.IO) {
        val type = object : com.google.gson.reflect.TypeToken<List<ExportItem>>() {}.type
        val items: List<ExportItem> = gson.fromJson(json, type)
        var count = 0
        items.forEach { item ->
            val parsed = ParsedQuestion(
                rawText = item.question,
                normalized = com.xiaoniu.nursing.parser.TextNormalizer.normalize(item.question),
                textHash = com.xiaoniu.nursing.parser.TextNormalizer.hash(item.question),
                questionType = QuestionType.fromLabel(item.type),
                options = item.options.map {
                    ParsedOption(label = it.label, text = it.text, isCorrect = it.correct)
                },
                source = "import"
            )
            if (saveQuestion(parsed) > 0) count++
        }
        count
    }

    data class ExportItem(
        val question: String,
        val type: String,
        val options: List<ExportOption>
    )

    data class ExportOption(
        val label: String,
        val text: String,
        val correct: Boolean
    )
}
