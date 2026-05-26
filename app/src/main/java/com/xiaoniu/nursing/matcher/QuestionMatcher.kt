package com.xiaoniu.nursing.matcher

import com.xiaoniu.nursing.database.QuestionRepository
import com.xiaoniu.nursing.model.*
import com.xiaoniu.nursing.parser.TextNormalizer
import kotlinx.coroutines.flow.first

/**
 * 题目匹配引擎 — 两阶段匹配
 */
class QuestionMatcher(private val repository: QuestionRepository) {

    companion object {
        const val DEFAULT_THRESHOLD = 0.85f
    }

    private var threshold = DEFAULT_THRESHOLD

    fun setThreshold(t: Float) {
        threshold = t.coerceIn(0.5f, 1.0f)
    }

    /**
     * 匹配题目 — 先精确 hash，再模糊 Levenshtein
     */
    suspend fun match(normalizedText: String): MatchResult {
        val hash = TextNormalizer.hash(normalizedText)

        // 阶段1：精确匹配
        val exact = repository.getByHash(hash)
        if (exact != null) {
            return MatchResult.ExactMatch(exact)
        }

        // 阶段2：模糊匹配
        return fuzzyMatch(normalizedText)
    }

    private suspend fun fuzzyMatch(screenText: String): MatchResult {
        // 获取所有题目做遍历匹配
        val allQuestions = mutableListOf<Question>()
        repository.getAllQuestions().collect { list ->
            allQuestions.addAll(list)
            return@collect // 只取一次
        }

        if (allQuestions.isEmpty()) {
            return MatchResult.NoMatch(screenText, "题库为空")
        }

        var best: Question? = null
        var bestScore = 0f

        for (q in allQuestions) {
            val score = TextNormalizer.similarity(screenText, q.normalized)
            if (score > bestScore) {
                bestScore = score
                best = q
            }
            // 拿到高分直接返回
            if (bestScore >= 0.98f) break
        }

        return if (best != null && bestScore >= threshold) {
            val withOptions = repository.getQuestionWithOptions(best.id)
            if (withOptions != null) {
                MatchResult.FuzzyMatch(withOptions, bestScore, best.rawText)
            } else {
                MatchResult.NoMatch(screenText, "题目选项数据丢失")
            }
        } else {
            MatchResult.NoMatch(
                screenText,
                if (best != null) "最佳相似度 ${(bestScore*100).toInt()}% < ${(threshold*100).toInt()}%"
                else "题库中未找到相似题目"
            )
        }
    }
}
