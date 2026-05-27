package com.xiaoniu.nursing.parser

import android.view.accessibility.AccessibilityNodeInfo
import com.xiaoniu.nursing.model.ParsedOption
import com.xiaoniu.nursing.model.ParsedQuestion
import com.xiaoniu.nursing.model.QuestionType

/**
 * 护理助手专用解析器
 *
 * 基于护理助手 App 截图分析：
 * - 题目格式：「1. xxx」或「第1题 xxx」
 * - 选项格式：「A. xxx」「B. xxx」~「E. xxx」(五选一/多)
 * - 判断题选项：「正确」「错误」或「√」「×」
 * - 提交后正确答案变绿/加✓/加粗
 *
 * 解析策略：
 * 1. 遍历所有 TextView 节点
 * 2. 按正则分类为题目/选项
 * 3. 正确答案识别优先级：isSelected > contentDescription > 文本匹配"正确"
 */
object ExamParser {

    // ====== 题目识别 ======
    private val QUESTION_PATTERNS = listOf(
        Regex("""^\s*\d+[\.、．)\s]"""),           // "1." "2、" "3)"
        Regex("""^\s*第\s*\d+\s*[题问]"""),         // "第1题" "第2问"
        Regex("""^\s*[（(]\s*\d+\s*[）)]""")        // "(1)" "（2）"
    )

    // ====== 选项识别 ======
    // 标准 ABCDE 选项
    private val OPTION_LABEL_REGEX = Regex("""^([A-Fa-f])[\.、．)\s]+(.+)""")
    // 判断题选项
    private val JUDGE_MARK_REGEX = Regex("""^([√✓×✗xX])\s*(.*)""")
    private val JUDGE_TEXT_REGEX = Regex("""^(正确|错误|对|错|是|否)[\s。.]*(.*)""")

    // ====== 正确答案检测 ======
    // contentDescription / 状态中可能有的标记
    private val CORRECT_INDICATORS = listOf(
        "正确", "correct", "right", "√", "✓", "选中", "selected", "checked",
        "正确答案", "答案正确"
    )

    /**
     * 从 Accessibility 根节点解析题目列表
     */
    fun parseFromNode(rootNode: AccessibilityNodeInfo): List<ParsedQuestion> {
        val textNodes = mutableListOf<AccessibilityNodeInfo>()
        collectTextViews(rootNode, textNodes)

        // 提取纯文本
        val items = textNodes.mapNotNull { node ->
            val text = node.text?.toString()?.trim() ?: return@mapNotNull null
            if (text.isBlank()) null else TextItem(text, node)
        }

        return groupIntoQuestions(items)
    }

    private fun collectTextViews(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        val text = node.text?.toString()?.trim()
        val className = node.className?.toString() ?: ""
        
        // 收集所有有文本的节点，不限于 TextView
        // 很多 App 用自定义组件、Button、RadioButton 等
        if (!text.isNullOrBlank() && text.length >= 2) {
            out.add(node)
        }
        
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTextViews(it, out) }
        }
    }

    /** 调试：收集所有有文本的节点（包括单字符） */
    fun collectTextViewsDebug(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank()) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTextViewsDebug(it, out) }
        }
    }

    /**
     * 将文本按题目分组：题目头 → 选项 → 题目头 → 选项...
     */
    private fun groupIntoQuestions(items: List<TextItem>): List<ParsedQuestion> {
        val result = mutableListOf<ParsedQuestion>()
        if (items.isEmpty()) return result

        // 找出每个题目的起始位置
        val boundaries = mutableListOf<Int>()
        for (i in items.indices) {
            if (isQuestionStart(items[i].text)) boundaries.add(i)
        }
        if (boundaries.isEmpty()) {
            // 没有检测到题目头，可能整个内容都是题目
            val allOptions = items.filter { isOption(it.text) }
            if (allOptions.isNotEmpty()) {
                val questionText = items
                    .filter { !isOption(it.text) }
                    .joinToString(" ") { it.text }
                if (questionText.isNotBlank()) {
                    result.add(buildQuestion(
                        listOf(TextItem(questionText, items.first().node)),
                        allOptions
                    ))
                }
            }
            return result
        }

        // 按边界分组
        for (i in boundaries.indices) {
            val start = boundaries[i]
            val end = if (i + 1 < boundaries.size) boundaries[i + 1] else items.size
            val group = items.subList(start, end)

            val questionTexts = mutableListOf<TextItem>()
            val optionTexts = mutableListOf<TextItem>()
            var sawOption = false

            for (item in group) {
                if (isQuestionStart(item.text)) {
                    questionTexts.add(item)
                } else if (isOption(item.text)) {
                    sawOption = true
                    optionTexts.add(item)
                } else if (!sawOption) {
                    // 选项之前的文本 → 题目描述的一部分
                    questionTexts.add(item)
                }
            }

            if (optionTexts.isNotEmpty()) {
                result.add(buildQuestion(questionTexts, optionTexts))
            }
        }

        return result
    }

    private fun buildQuestion(qTexts: List<TextItem>, optTexts: List<TextItem>): ParsedQuestion {
        val rawText = qTexts.joinToString(" ") { it.text }
        val normalized = TextNormalizer.normalize(rawText)
        val textHash = TextNormalizer.hash(rawText)

        val options = optTexts.mapIndexed { idx, item ->
            parseOption(item, idx)
        }

        val questionType = detectType(options)
        val correctedOptions = detectCorrect(options)

        return ParsedQuestion(
            rawText = rawText,
            normalized = normalized,
            textHash = textHash,
            questionType = questionType,
            options = correctedOptions
        )
    }

    /**
     * 解析单个选项：提取 label + 文本
     */
    private fun parseOption(item: TextItem, index: Int): ParsedOption {
        val text = item.text.trim()

        // 尝试标准选项格式 A. xxx
        OPTION_LABEL_REGEX.find(text)?.let { match ->
            return ParsedOption(
                label = match.groupValues[1].uppercase(),
                text = match.groupValues[2].trim()
            )
        }

        // 判断题标记格式 √ xxx
        JUDGE_MARK_REGEX.find(text)?.let { match ->
            return ParsedOption(
                label = match.groupValues[1],
                text = match.groupValues[2].trim().ifBlank { match.groupValues[1] }
            )
        }

        // 判断题文字格式 正确/错误
        JUDGE_TEXT_REGEX.find(text)?.let { match ->
            return ParsedOption(
                label = match.groupValues[1],
                text = text
            )
        }

        // 兜底：按索引生成标签
        return ParsedOption(
            label = ('A' + index).toString(),
            text = text
        )
    }

    /**
     * 检测选项中的正确答案
     *
     * 护理助手提交后的常见标记方式：
     * 1. 正确选项 isSelected=true / isChecked=true
     * 2. 正确选项 contentDescription 包含 "正确" / "correct"
     * 3. 正确选项文本中包含 ✓
     * 4. 错误选项 isSelected=false 但可能有特殊标记
     */
    private fun detectCorrect(options: List<ParsedOption>): List<ParsedOption> {
        // 需要原始的 TextItem 才能检查节点状态...
        // 这里返回标记结果，实际状态检测在调用层处理
        return options // 调用层会通过 parseFromNodeWithState 处理
    }

    /**
     * 带状态检测的解析（收集模式用）
     * 解析题目 + 返回每个选项对应的 node，方便调用层检测 isSelected/isChecked
     */
    fun parseFromNodeWithState(rootNode: AccessibilityNodeInfo): List<
            Pair<ParsedQuestion, List<AccessibilityNodeInfo?>>> {
        val result = mutableListOf<Pair<ParsedQuestion, List<AccessibilityNodeInfo?>>>()
        val textNodes = mutableListOf<AccessibilityNodeInfo>()
        collectTextViews(rootNode, textNodes)

        val items = textNodes.mapNotNull { node ->
            val text = node.text?.toString()?.trim() ?: return@mapNotNull null
            if (text.isBlank()) null else TextItem(text, node)
        }

        val questions = groupIntoQuestions(items)
        questions.forEach { q ->
            // 按选项顺序匹配对应 node
            val optNodes = q.options.map { opt ->
                textNodes.firstOrNull { node ->
                    val text = node.text?.toString()?.trim() ?: return@firstOrNull false
                    text.startsWith(opt.label + ".") ||
                            text.startsWith(opt.label + "、") ||
                            text.startsWith(opt.label + ")  ") ||
                            text == opt.label + " " + opt.text ||
                            text == opt.text
                }
            }
            result.add(q to optNodes)
        }

        return result
    }

    /**
     * 题型检测
     */
    fun detectType(options: List<ParsedOption>): QuestionType {
        val labels = options.map { it.label.uppercase() }.filter { it.isNotEmpty() }

        // 判断题特征
        if (labels.any { it in listOf("√", "✓", "×", "✗", "X") }) return QuestionType.JUDGE
        if (labels.size == 2 && labels.all {
                it in listOf("正确", "错误", "对", "错", "是", "否")
            }) return QuestionType.JUDGE

        // 默认单选（多选需要在后续根据正确选项数量修正）
        return QuestionType.SINGLE
    }

    /**
     * 修正题型：根据正确选项数量
     */
    fun refineType(type: QuestionType, correctCount: Int): QuestionType {
        if (type == QuestionType.JUDGE) return type
        return if (correctCount > 1) QuestionType.MULTI else QuestionType.SINGLE
    }

    // ====== 判断方法 ======

    fun isQuestionStart(text: String): Boolean =
        QUESTION_PATTERNS.any { it.containsMatchIn(text.trim()) }

    fun isOption(text: String): Boolean {
        val t = text.trim()
        return OPTION_LABEL_REGEX.containsMatchIn(t) ||
                JUDGE_MARK_REGEX.matches(t) ||
                JUDGE_TEXT_REGEX.matches(t)
    }

    /**
     * 判断节点是否代表正确选项（收集模式核心逻辑）
     */
    fun isCorrectOptionNode(node: AccessibilityNodeInfo): Boolean {
        // 1. 被选中/勾选
        if (node.isSelected || node.isChecked) return true

        // 2. contentDescription 包含正确标记
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (CORRECT_INDICATORS.any { desc.contains(it.lowercase()) }) return true

        // 3. 文本中包含 ✓（通常在选项文本后面）
        val text = node.text?.toString() ?: ""
        if (text.contains("✓") || text.contains("√")) return true

        return false
    }

    data class TextItem(val text: String, val node: AccessibilityNodeInfo)
}
