package com.xiaoniu.nursing.parser

import java.security.MessageDigest

/**
 * 文本归一化工具 — 题目去重 & 匹配的核心
 *
 * 归一化流程：
 * 1. 去除题号前缀 (1. 2、 (1) 第1题 等)
 * 2. 去除结尾的括号 (  ) （  ）
 * 3. 全角数字/字母转半角
 * 4. 去除多余空白
 * 5. 转小写
 */
object TextNormalizer {

    // 题号前缀正则：匹配 "1." "2、" "(3)" "第4题" "5)" 等
    private val QUESTION_NUMBER_REGEX = Regex(
        """^[\s]*((第\s*\d+\s*[题问])|(\d+[\.、．)\s]+)|(\(\s*\d+\s*\))|([（]\s*\d+\s*[）]))"""
    )

    // 结尾括号
    private val TRAILING_BRACKET_REGEX = Regex("""[（(]\s*[）)]\s*$""")

    // 全角字母数字
    private val FULLWIDTH_MAP = mapOf(
        '０' to '0', '１' to '1', '２' to '2', '３' to '3', '４' to '4',
        '５' to '5', '６' to '6', '７' to '7', '８' to '8', '９' to '9',
        'Ａ' to 'A', 'Ｂ' to 'B', 'Ｃ' to 'C', 'Ｄ' to 'D', 'Ｅ' to 'E', 'Ｆ' to 'F',
        'ａ' to 'a', 'ｂ' to 'b', 'ｃ' to 'c', 'ｄ' to 'd', 'ｅ' to 'e', 'ｆ' to 'f',
        '（' to '(', '）' to ')', '，' to ',', '。' to '.',
        '；' to ';', '：' to ':', '！' to '!', '？' to '?',
        '　' to ' ', '＠' to '@', '＃' to '#', '＄' to '$', '％' to '%'
    )

    /**
     * 归一化题目文本
     */
    fun normalize(text: String): String {
        var result = text.trim()

        // 1. 去除题号前缀
        result = result.replace(QUESTION_NUMBER_REGEX, "")

        // 2. 去除结尾括号
        result = result.replace(TRAILING_BRACKET_REGEX, "")

        // 3. 全角转半角
        result = result.map { FULLWIDTH_MAP[it] ?: it }.joinToString("")

        // 4. 合并连续空白
        result = result.replace(Regex("""\s+"""), " ").trim()

        // 5. 去除首尾标点
        result = result.trimEnd('?', '？', '.', '。', ',', '，', ';', '；')

        return result
    }

    /**
     * 计算归一化文本的 MD5 hash
     */
    fun hash(text: String): String {
        val normalized = normalize(text)
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 归一化选项文本
     */
    fun normalizeOption(text: String): String {
        var result = text.trim()

        // 去除选项标签前缀 (A. B、 C) 等)
        result = result.replace(Regex("""^[A-Fa-f√✓×✗xX][\.、．)\s]+"""), "")

        // 全角转半角
        result = result.map { FULLWIDTH_MAP[it] ?: it }.joinToString("")

        return result.trim()
    }

    /**
     * 提取选项标签 (A/B/C/D/√/×)
     */
    fun extractOptionLabel(text: String): String? {
        val match = Regex("""^([A-Fa-f√✓×✗xX])[\.、．)\s]""").find(text.trim())
        return match?.groupValues?.get(1)?.uppercase()
    }

    /**
     * 两条文本的相似度 (Levenshtein 距离)
     */
    fun similarity(s1: String, s2: String): Float {
        if (s1 == s2) return 1.0f
        if (s1.isEmpty() || s2.isEmpty()) return 0.0f

        val n1 = normalize(s1)
        val n2 = normalize(s2)

        val distance = levenshteinDistance(n1, n2)
        val maxLen = maxOf(n1.length, n2.length)
        return 1.0f - (distance.toFloat() / maxLen)
    }

    /**
     * Levenshtein 编辑距离
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length

        // 用两个一维数组代替二维，节省内存
        var prev = IntArray(len2 + 1) { it }
        var curr = IntArray(len2 + 1)

        for (i in 1..len1) {
            curr[0] = i
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,       // 删除
                    curr[j - 1] + 1,   // 插入
                    prev[j - 1] + cost // 替换
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }

        return prev[len2]
    }
}
