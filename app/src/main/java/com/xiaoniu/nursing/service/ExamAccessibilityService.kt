package com.xiaoniu.nursing.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.Gson
import com.xiaoniu.nursing.database.AppDatabase
import com.xiaoniu.nursing.matcher.QuestionMatcher
import com.xiaoniu.nursing.model.*
import com.xiaoniu.nursing.parser.ExamParser
import com.xiaoniu.nursing.parser.TextNormalizer
import com.xiaoniu.nursing.ui.FloatingWindowManager
import kotlinx.coroutines.*

/**
 * 护理刷题无障碍服务 — 核心引擎
 *
 * 两种模式：
 * - MODE_COLLECT：监听屏幕变化，自动/手动收录题目+答案
 * - MODE_ANSWER：点击悬浮按钮触发，匹配题库并自动点击正确答案
 *
 * 针对护理助手的优化：
 * - 识别 A/B/C/D/E 五选一格式
 * - 自动检测"提交"后显示正确答案的状态变化
 * - 选项节点查找兼容 RadioButton/TextView/CheckedTextView
 */
class ExamAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ExamA11y"

        const val MODE_COLLECT = "collect"
        const val MODE_ANSWER = "answer"
        const val MODE_IDLE = "idle"

        // Broadcast Actions
        const val ACTION_MODE_CHANGED = "com.xiaoniu.nursing.MODE_CHANGED"
        const val ACTION_COLLECT_NOW = "com.xiaoniu.nursing.COLLECT_NOW"
        const val ACTION_ANSWER_NOW = "com.xiaoniu.nursing.ANSWER_NOW"
        const val ACTION_QUESTION_FOUND = "com.xiaoniu.nursing.QUESTION_FOUND"
        const val ACTION_QUESTION_NOT_FOUND = "com.xiaoniu.nursing.QUESTION_NOT_FOUND"
        const val ACTION_COLLECT_DONE = "com.xiaoniu.nursing.COLLECT_DONE"

        @Volatile var instance: ExamAccessibilityService? = null; private set
        @Volatile var currentMode: String = MODE_IDLE
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var matcher: QuestionMatcher
    private lateinit var repo: com.xiaoniu.nursing.database.QuestionRepository
    private val gson = Gson()

    // 配置
    private var targetPkg: String = ""
    private var collectAuto = true
    private var lastHash: String = ""
    private var lastEventTime = 0L
    private val debounceMs = 500L

    // ============================================================
    // 生命周期
    // ============================================================

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "✅ 无障碍服务已连接")

        AppDatabase.getInstance(this)  // 预热数据库
        repo = com.xiaoniu.nursing.database.QuestionRepository(this)
        matcher = QuestionMatcher(repo)

        loadConfig()
        startFloatingService()
    }

    // ============================================================
    // 事件处理
    // ============================================================

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        // 包名过滤
        if (targetPkg.isNotEmpty() && pkg != targetPkg) return

        val now = System.currentTimeMillis()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (now - lastEventTime > debounceMs) {
                    lastEventTime = now
                    onContentChanged(event)
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                onViewClicked(event)
            }
        }
    }

    /**
     * 屏幕内容变化 → 解析题目
     */
    private fun onContentChanged(event: AccessibilityEvent) {
        val root = rootInActiveWindow ?: return

        val questionsWithNodes = ExamParser.parseFromNodeWithState(root)
        root.recycle()

        if (questionsWithNodes.isEmpty()) return
        val (q, optNodes) = questionsWithNodes.first()

        // 检测正确答案：通过节点状态判断
        val correctedOptions = q.options.mapIndexed { idx, opt ->
            val isCorrect = if (idx < optNodes.size && optNodes[idx] != null)
                ExamParser.isCorrectOptionNode(optNodes[idx]!!)
            else
                opt.isCorrect
            opt.copy(isCorrect = isCorrect)
        }
        val qWithCorrect = q.copy(options = correctedOptions)

        // 去重：同一题短时间内不重复处理
        if (q.textHash == lastHash) return
        lastHash = q.textHash

        Log.d(TAG, "📋 检测到题目: type=${q.questionType.label} " +
                "text=${q.rawText.take(60)}... options=${qWithCorrect.options.size} " +
                "corrects=${qWithCorrect.options.count { it.isCorrect }}")

        when (currentMode) {
            MODE_COLLECT -> {
                if (collectAuto && qWithCorrect.options.any { it.isCorrect }) {
                    collectAndReport(qWithCorrect)
                }
                // 无正确标记时跳过，等提交后再收集
            }
            MODE_ANSWER -> {
                // 答题模式下每次内容变化都尝试匹配
                // 但只在用户触发后才执行（通过 ACTION_ANSWER_NOW）
            }
        }
    }

    /**
     * 视图点击 → 检测"提交"/"下一题"按钮
     */
    private fun onViewClicked(event: AccessibilityEvent) {
        val source = event.source ?: return
        val text = source.text?.toString() ?: ""
        val desc = source.contentDescription?.toString() ?: ""
        source.recycle()

        val isSubmit = text.contains("提交") || text.contains("确定") ||
                text.contains("下一题") || text.contains("下一") ||
                desc.contains("提交") || desc.contains("下一题")

        if (isSubmit && currentMode == MODE_COLLECT) {
            Log.d(TAG, "🔘 检测到提交/下一题按钮，准备收集正确答案...")
            // 延迟等答案显示出来
            scope.launch {
                delay(600)
                val root = rootInActiveWindow ?: return@launch
                val questions = ExamParser.parseFromNode(root)
                root.recycle()

                val q = questions.firstOrNull()
                if (q != null && q.options.any { it.isCorrect }) {
                    collectAndReport(q)
                } else {
                    Log.d(TAG, "⚠️ 未检测到正确答案标记，可能需要调整解析逻辑")
                }
            }
        }
    }

    // ============================================================
    // 收集模式
    // ============================================================

    private fun collectAndReport(parsed: com.xiaoniu.nursing.model.ParsedQuestion) {
        scope.launch(Dispatchers.IO) {
            try {
                val id = repo.saveQuestion(parsed)
                val stats = repo.getStats()

                val msg = if (id > 0) "新题已收录 ✨" else "题目已存在，跳过"
                Log.i(TAG, "$msg (题库: ${stats.total}题) type=${parsed.questionType.label}")

                broadcast(ACTION_COLLECT_DONE,
                    "count" to stats.total,
                    "message" to msg,
                    "question_text" to parsed.rawText,
                    "options_json" to gson.toJson(parsed.options.map {
                        mapOf("label" to it.label, "text" to it.text, "isCorrect" to it.isCorrect)
                    }),
                    "question_type" to parsed.questionType.label
                )
            } catch (e: Exception) {
                Log.e(TAG, "收录失败", e)
            }
        }
    }

    // ============================================================
    // 答题模式
    // ============================================================

    /**
     * 执行自动答题（由 ACTION_ANSWER_NOW 或悬浮窗触发）
     */
    fun executeAnswer() {
        scope.launch(Dispatchers.Main) {
            delay(300) // 等 UI 稳定

            val root = rootInActiveWindow
            if (root == null) {
                Log.w(TAG, "无法获取当前窗口")
                broadcast(ACTION_QUESTION_NOT_FOUND, "question_text" to "无法获取屏幕内容")
                return@launch
            }

            val questions = ExamParser.parseFromNode(root)
            root.recycle()

            if (questions.isEmpty()) {
                broadcast(ACTION_QUESTION_NOT_FOUND, "question_text" to "未检测到题目")
                return@launch
            }

            val q = questions.first()
            matchAndAnswer(q)
        }
    }

    private suspend fun matchAndAnswer(parsed: ParsedQuestion) {
        val result = withContext(Dispatchers.IO) {
            matcher.match(parsed.normalized)
        }

        when (result) {
            is MatchResult.ExactMatch -> {
                Log.i(TAG, "🎯 精确匹配: ${result.question.question.rawText.take(40)}")
                doClick(result.question, parsed)
                broadcast(ACTION_QUESTION_FOUND,
                    "question_text" to parsed.rawText,
                    "options_json" to gson.toJson(result.question.options.map {
                        mapOf("label" to it.label, "text" to it.text, "isCorrect" to it.isCorrect)
                    }),
                    "confidence" to 1.0f,
                    "match_type" to "exact"
                )
            }
            is MatchResult.FuzzyMatch -> {
                Log.i(TAG, "🔍 模糊匹配: ${result.confidence} ${result.matchedText.take(40)}")
                doClick(result.question, parsed)
                broadcast(ACTION_QUESTION_FOUND,
                    "question_text" to parsed.rawText,
                    "options_json" to gson.toJson(result.question.options.map {
                        mapOf("label" to it.label, "text" to it.text, "isCorrect" to it.isCorrect)
                    }),
                    "confidence" to result.confidence,
                    "match_type" to "fuzzy"
                )
            }
            is MatchResult.NoMatch -> {
                Log.w(TAG, "❌ 未命中: ${parsed.rawText.take(40)}")
                broadcast(ACTION_QUESTION_NOT_FOUND,
                    "question_text" to parsed.rawText,
                    "options_json" to gson.toJson(parsed.options.map {
                        mapOf("label" to it.label, "text" to it.text, "isCorrect" to false)
                    })
                )
            }
        }
    }

    /**
     * 在屏幕上点击正确选项
     */
    private fun doClick(dbQ: QuestionWithOptions, screenQ: ParsedQuestion) {
        val correctLabels = dbQ.correctLabels().map { it.uppercase() }
        if (correctLabels.isEmpty()) {
            Log.w(TAG, "题库题目无正确答案标记")
            return
        }

        scope.launch(Dispatchers.Main) {
            val root = rootInActiveWindow ?: return@launch
            val clickableNodes = mutableListOf<AccessibilityNodeInfo>()
            findClickableOptions(root, clickableNodes)
            root.recycle()

            Log.d(TAG, "找到 ${clickableNodes.size} 个可点击选项, 正确答案: $correctLabels")

            var clicked = 0
            for (node in clickableNodes) {
                val text = node.text?.toString()?.trim() ?: continue
                val label = TextNormalizer.extractOptionLabel(text) ?: continue

                if (label.uppercase() in correctLabels) {
                    val success = performClick(node)
                    if (success) {
                        clicked++
                        Log.i(TAG, "✅ 已点击: $label - ${text.take(30)}")
                    }
                    // 单选题只点一个，多选题点全部
                    if (dbQ.question.questionType == "single") break
                }
            }

            if (clicked == 0) {
                Log.w(TAG, "未找到可点击的正确选项节点，尝试坐标点击...")
                // 兜底：尝试通过坐标点击
                tryCoordinateClick(clickableNodes, correctLabels)
            }
        }
    }

    private fun findClickableOptions(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        val cls = node.className?.toString() ?: ""
        val text = node.text?.toString()?.trim() ?: ""

        val isOption = ExamParser.isOption(text)
        val isClickableType = cls.contains("TextView") || cls.contains("RadioButton") ||
                cls.contains("CheckBox") || cls.contains("CheckedTextView") ||
                cls.contains("Button")

        if (isOption && isClickableType) {
            // 护理助手常见模式：选项是 TextView + 可点击
            if (node.isClickable || node.isCheckable || node.isFocusable) {
                result.add(node)
            } else {
                // 如果本身不可点击，找父级
                var p = node.parent
                while (p != null) {
                    if (p.isClickable) {
                        result.add(p)
                        break
                    }
                    p = p.parent
                }
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findClickableOptions(it, result) }
        }
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // 尝试点击父级
        var p = node.parent
        while (p != null) {
            if (p.isClickable) {
                val ok = p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                p.recycle()
                return ok
            }
            p = p.parent
        }
        return false
    }

    private fun tryCoordinateClick(nodes: List<AccessibilityNodeInfo>, correctLabels: List<String>) {
        for (node in nodes) {
            val text = node.text?.toString()?.trim() ?: continue
            val label = TextNormalizer.extractOptionLabel(text) ?: continue
            if (label.uppercase() in correctLabels) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val x = rect.centerX().toFloat()
                val y = rect.centerY().toFloat()
                gestureClick(x, y)
                Log.i(TAG, "📍 坐标点击: $label (${x.toInt()}, ${y.toInt()})")
                return
            }
        }
    }

    private fun gestureClick(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ============================================================
    // 命令处理
    // ============================================================

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleCmd(it) }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun handleCmd(intent: Intent) {
        when (intent.action) {
            ACTION_COLLECT_NOW -> {
                currentMode = MODE_COLLECT
                Log.i(TAG, "📝 切换到收集模式")
                broadcast(ACTION_MODE_CHANGED, "mode" to MODE_COLLECT)

                // 立即尝试收集当前屏幕
                scope.launch(Dispatchers.Main) {
                    delay(200)
                    val root = rootInActiveWindow ?: return@launch
                    val qs = ExamParser.parseFromNode(root)
                    root.recycle()
                    qs.firstOrNull()?.let { collectAndReport(it) }
                }
            }
            ACTION_ANSWER_NOW -> {
                currentMode = MODE_ANSWER
                Log.i(TAG, "✍️ 切换到答题模式")
                broadcast(ACTION_MODE_CHANGED, "mode" to MODE_ANSWER)
                executeAnswer()
            }
        }
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private fun loadConfig() {
        val prefs = getSharedPreferences("exam_config", Context.MODE_PRIVATE)
        targetPkg = prefs.getString("target_package", "") ?: ""
        collectAuto = prefs.getBoolean("collect_auto_trigger", true)
        matcher.setThreshold(prefs.getFloat("match_threshold", QuestionMatcher.DEFAULT_THRESHOLD))
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingWindowService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun broadcast(action: String, vararg extras: Pair<String, Any>) {
        val intent = Intent(action).apply {
            setPackage(packageName)
            extras.forEach { (key, value) ->
                when (value) {
                    is String -> putExtra(key, value)
                    is Int -> putExtra(key, value)
                    is Boolean -> putExtra(key, value)
                    is Float -> putExtra(key, value)
                    is Long -> putExtra(key, value)
                }
            }
        }
        sendBroadcast(intent)
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
    }
}
