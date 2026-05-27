package com.xiaoniu.nursing.ui

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Build
import android.util.DisplayMetrics
import android.view.*
import android.view.animation.OvershootInterpolator
import android.widget.*
import com.xiaoniu.nursing.service.ExamAccessibilityService

/**
 * 悬浮窗管理器 — 参考竞品 APP 重新设计
 *
 * 折叠态：40dp 半透明白色圆形按钮，带 ➕ 图标
 * 展开态：卡片式面板，显示模式选择 + 最新操作结果
 * 结果卡片：题目预览 + 选项列表 + 正确答案高亮
 */
class FloatingWindowManager(private val context: Context) {

    companion object {
        private const val COLLAPSED_SIZE_DP = 64
        private const val EXPANDED_WIDTH_DP = 240
        private const val EXPANDED_HEIGHT_DP = 320
        private const val RESULT_CARD_WIDTH_DP = 260
        private const val RESULT_CARD_MAX_HEIGHT_DP = 400
        private const val EDGE_MARGIN_DP = 12
    }

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dm = DisplayMetrics()

    // 视图引用
    private var floatBtn: View? = null        // 折叠态按钮
    private var menuPanel: View? = null       // 展开菜单
    private var resultCard: View? = null      // 答题结果卡片
    private var isExpanded = false
    private var isShowing = false

    private var btnParams: WindowManager.LayoutParams? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var cardParams: WindowManager.LayoutParams? = null

    // 拖动状态
    private var dragStartX = 0
    private var dragStartY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var hasDragged = false

    // 当前操作结果（用于结果卡片展示）
    private var lastResult: AnswerResult? = null
    private var currentMode: String = ExamAccessibilityService.MODE_IDLE
    private var lastStats: Int = 0

    private val btnSize by lazy { dp(COLLAPSED_SIZE_DP) }
    private val menuW by lazy { dp(EXPANDED_WIDTH_DP) }
    private val menuH by lazy { dp(EXPANDED_HEIGHT_DP) }
    private val cardW by lazy { dp(RESULT_CARD_WIDTH_DP) }
    private val edge by lazy { dp(EDGE_MARGIN_DP) }

    init {
        wm.defaultDisplay.getMetrics(dm)
    }

    // ============================================================
    // 生命周期
    // ============================================================

    fun show() {
        if (isShowing) return
        try {
            buildFloatButton()
            wm.addView(floatBtn, btnParams)
            isShowing = true
            registerReceiver()
        } catch (e: SecurityException) {
            android.util.Log.e("FloatingWindow", "⛔ 无悬浮窗权限: ${e.message}")
        } catch (e: Exception) {
            android.util.Log.e("FloatingWindow", "⛔ 悬浮窗显示失败: ${e.message}")
        }
    }

    fun hide() {
        if (!isShowing) return
        safeRemove(floatBtn)
        dismissMenu()
        dismissResultCard()
        isShowing = false
    }

    fun destroy() {
        hide()
        try { context.unregisterReceiver(resultReceiver) } catch (_: Exception) {}
    }

    // ============================================================
    // 折叠态：浮动按钮
    // ============================================================

    private fun buildFloatButton() {
        // 外层容器
        val container = FrameLayout(context)

        // 圆形背景
        val circle = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF2196F3.toInt())   // 蓝色
                setStroke(2, 0xFF1976D2.toInt())  // 深蓝边框
            }
            // 阴影效果
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                clipToOutline = true
                elevation = dp(4).toFloat()
            }
        }
        container.addView(circle, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // ➕ 图标
        val icon = TextView(context).apply {
            text = "✦"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(4f, 1f, 1f, 0x80000000.toInt())
            gravity = Gravity.CENTER
            tag = "float_icon"
        }
        container.addView(icon, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 触摸处理：拖动 or 点击展开菜单
        container.setOnTouchListener(FloatBtnTouchListener())

        btnParams = overlayParams(btnSize, btnSize).apply {
            x = dm.widthPixels - btnSize - edge
            y = dm.heightPixels / 3
        }
        floatBtn = container
    }

    // ============================================================
    // 展开菜单
    // ============================================================

    private fun showMenu() {
        if (menuPanel != null) return

        val card = buildMenuCard()
        menuPanel = card

        // 定位：在按钮左边或右边展开，避免超出屏幕
        menuParams = overlayParams(menuW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            val btnRight = btnParams!!.x + btnSize
            if (btnRight + menuW + edge > dm.widthPixels) {
                x = btnParams!!.x - menuW  // 左边展开
            } else {
                x = btnParams!!.x + btnSize + dp(8) // 右边展开
            }
            y = (btnParams!!.y - dp(20)).coerceIn(edge, dm.heightPixels - menuH - edge)
        }

        wm.addView(card, menuParams)
        isExpanded = true

        // 入场动画
        card.translationY = dp(20).toFloat()
        card.alpha = 0f
        card.animate()
            .translationY(0f).alpha(1f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(0.8f))
            .start()
    }

    private fun dismissMenu() {
        menuPanel?.let { panel ->
            panel.animate()
                .translationY(dp(16).toFloat()).alpha(0f)
                .setDuration(150)
                .withEndAction {
                    safeRemove(panel)
                    menuPanel = null
                    isExpanded = false
                }.start()
        }
    }

    private fun buildMenuCard(): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(0xF5FFFFFF.toInt())
                setStroke(1, 0x1A000000)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                elevation = dp(8).toFloat()
            }
        }

                // 标题
        card.addView(textView("🐙 刷题助手", 15f, 0xFF333333.toInt(), bold = true).apply {
            setPadding(0, 0, 0, dp(12))
        })

        // 状态栏
        val statusRow = textView("💤 待机中 · 题库: 加载中...", 12f, 0xFF666666.toInt()).apply {
            setPadding(dp(12), 0, dp(12), dp(8))
            tag = "status_row"
        }
        card.addView(statusRow)

        // 分隔线
        card.addView(divider())

        // 收集模式
        card.addView(menuRow("📝", "收集模式", "进入护理助手刷题，自动收录") {
            switchMode(ExamAccessibilityService.MODE_COLLECT)
        })

        // 答题模式
        card.addView(menuRow("✍️", "答题模式", "点击悬浮按钮智能答题") {
            switchMode(ExamAccessibilityService.MODE_ANSWER)
        })

        // 手动收集当前题
        card.addView(menuRow("📷", "手动收录当前题", "收录屏幕上显示的题目") {
            manualCollect()
        })

        card.addView(menuRow("🔍", "调试：检查屏幕文本", "查看当前屏幕是否可读") {
            checkScreenText()
        })

        // 分隔线
        card.addView(divider())

        // 题库统计
        card.addView(textView("题库统计", 12f, 0xFF999999.toInt()).apply {
            setPadding(0, dp(8), 0, dp(4))
        })
        val statsText = textView("加载中...", 14f, 0xFF333333.toInt()).apply {
            tag = "stats_text"
        }
        card.addView(statsText)

        // 底部操作
        card.addView(divider())
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        bottomRow.addView(actionChip("⚙️ 设置") { openSettings() })
        bottomRow.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), 1)
        })
        bottomRow.addView(actionChip("✕ 关闭") { hide() })
        card.addView(bottomRow)

        // 高亮当前模式
        highlightCurrentMode()

        return card
    }

    // ============================================================
    // 答题结果卡片
    // ============================================================

    fun showResultCard(result: AnswerResult) {
        // 先关闭旧的
        dismissResultCard()
        dismissMenu()
        lastResult = result

        val card = buildResultCard(result)
        resultCard = card

        cardParams = overlayParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            x = (dm.widthPixels - cardW) / 2
            y = dm.heightPixels / 6
        }

        wm.addView(card, cardParams)

        // 入场动画
        card.scaleX = 0.8f
        card.scaleY = 0.8f
        card.alpha = 0f
        card.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(250)
            .setInterpolator(OvershootInterpolator())
            .start()

        // 3秒后自动消失
        card.postDelayed({ dismissResultCard() }, 3500)
    }

    private fun dismissResultCard() {
        resultCard?.let { card ->
            card.animate()
                .alpha(0f).setDuration(200)
                .withEndAction { safeRemove(card) }.start()
            resultCard = null
        }
    }

    private fun buildResultCard(result: AnswerResult): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(0xF8FFFFFF.toInt())
                setStroke(1, 0x1A000000)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                elevation = dp(12).toFloat()
            }
        }

        // 状态指示器
        val statusColor = when (result.status) {
            AnswerStatus.FOUND_EXACT -> 0xFF4CAF50.toInt()
            AnswerStatus.FOUND_FUZZY -> 0xFFFF9800.toInt()
            AnswerStatus.NOT_FOUND -> 0xFFFF5722.toInt()
            AnswerStatus.COLLECTED -> 0xFF2196F3.toInt()
        }
        val statusText = when (result.status) {
            AnswerStatus.FOUND_EXACT -> "✅ 精确匹配 · 已自动答题"
            AnswerStatus.FOUND_FUZZY -> "⚠️ 模糊匹配(${result.confidence}%) · 已自动答题"
            AnswerStatus.NOT_FOUND -> "❌ 题库未命中"
            AnswerStatus.COLLECTED -> "💾 题目已收录"
        }

        card.addView(textView(statusText, 13f, statusColor, bold = true).apply {
            setPadding(0, 0, 0, dp(10))
        })

        // 题目文本
        card.addView(textView("题目", 11f, 0xFF999999.toInt()))
        card.addView(textView(result.questionText, 14f, 0xFF333333.toInt()).apply {
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, dp(10))
        })

        // 选项
        if (result.options.isNotEmpty()) {
            card.addView(textView("选项", 11f, 0xFF999999.toInt()))
            val optsContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            result.options.forEach { opt ->
                optsContainer.addView(optionRow(opt.label, opt.text, opt.isCorrect))
            }
            card.addView(optsContainer)
        }

        // 关闭提示
        card.addView(textView("点击任意处关闭 · 3秒后自动消失", 11f, 0xFFBBBBBB.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })

        // 点击关闭
        card.setOnClickListener { dismissResultCard() }

        return card
    }

    private fun optionRow(label: String, optionText: String, isCorrect: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
            gravity = Gravity.CENTER_VERTICAL
        }

        val labelView = TextView(context).apply {
            this.text = label
            textSize = 12f
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(dp(24), dp(24))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isCorrect) 0xFF4CAF50.toInt() else 0xFFE0E0E0.toInt())
            }
            setTextColor(if (isCorrect) 0xFFFFFFFF.toInt() else 0xFF666666.toInt())
        }
        row.addView(labelView)
        row.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), 1)
        })

        val textView = TextView(context).apply {
            this.text = optionText
            textSize = 13f
            setTextColor(if (isCorrect) 0xFF2E7D32.toInt() else 0xFF666666.toInt())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(textView)

        // 正确标记
        if (isCorrect) {
            row.addView(TextView(context).apply {
                text = " ✓"
                textSize = 14f
                setTextColor(0xFF4CAF50.toInt())
            })
        }

        return row
    }

    // ============================================================
    // 拖动处理
    // ============================================================

    private inner class FloatBtnTouchListener : View.OnTouchListener {
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = btnParams!!.x
                    dragStartY = btnParams!!.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    hasDragged = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        hasDragged = true
                        btnParams!!.x = (dragStartX + dx).coerceIn(0, dm.widthPixels - btnSize)
                        btnParams!!.y = (dragStartY + dy).coerceIn(0, dm.heightPixels - btnSize)
                        wm.updateViewLayout(view, btnParams)
                        // 拖动时关闭菜单
                        dismissMenu()
                        dismissResultCard()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    snapToEdge(view)
                    if (!hasDragged) {
                        // 没有拖动 → 这是点击
                        onFloatBtnClick()
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun snapToEdge(view: View) {
        val cx = btnParams!!.x + btnSize / 2
        btnParams!!.x = if (cx < dm.widthPixels / 2) edge
        else dm.widthPixels - btnSize - edge
        wm.updateViewLayout(view, btnParams)
    }

    // ============================================================
    // 交互逻辑
    // ============================================================

    /** 点击浮动按钮（非拖动时触发）*/
    fun onFloatBtnClick() {
        if (isExpanded) {
            dismissMenu()
        } else {
            showMenu()
        }
    }

    private fun switchMode(mode: String) {
        this.currentMode = mode
        ExamAccessibilityService.currentMode = mode
        val action = when (mode) {
            ExamAccessibilityService.MODE_COLLECT -> ExamAccessibilityService.ACTION_COLLECT_NOW
            ExamAccessibilityService.MODE_ANSWER -> ExamAccessibilityService.ACTION_ANSWER_NOW
            else -> return
        }
        context.sendBroadcast(Intent(action).setPackage(context.packageName))
        // 切换模式后更新菜单高亮，不关闭菜单
        highlightCurrentMode()
        updateFloatIcon()
    }

    private fun highlightCurrentMode() {
        // 在菜单中高亮当前模式（通过遍历菜单子view设置颜色）
        menuPanel?.let { panel ->
            val container = panel as? ViewGroup ?: return
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (child is ViewGroup && child.childCount >= 2) {
                    val titleView = child.getChildAt(1) as? ViewGroup
                    val titleTv = titleView?.getChildAt(0) as? TextView
                    val subtitleTv = titleView?.getChildAt(1) as? TextView
                    if (titleTv != null && subtitleTv != null) {
                        val isActive = when (titleTv.text.toString()) {
                            "收集模式" -> currentMode == ExamAccessibilityService.MODE_COLLECT
                            "答题模式" -> currentMode == ExamAccessibilityService.MODE_ANSWER
                            else -> false
                        }
                        titleTv.setTextColor(if (isActive) 0xFF2196F3.toInt() else 0xFF333333.toInt())
                        subtitleTv.setTextColor(
                            if (isActive) 0xFF64B5F6.toInt() else 0xFF999999.toInt()
                        )
                    }
                }
            }
        }
    }

    private fun checkScreenText() {
        // 通过广播触发 ExamAccessibilityService 检查屏幕文本并广播回来
        context.sendBroadcast(
            Intent(ExamAccessibilityService.ACTION_CHECK_SCREEN_TEXT)
                .setPackage(context.packageName)
        )
    }
    private fun manualCollect() {
        // 手动触发收集当前屏幕的题目
        context.sendBroadcast(
            Intent(ExamAccessibilityService.ACTION_COLLECT_NOW).setPackage(context.packageName)
        )
        dismissMenu()
        updateFloatIcon()
    }

    private fun openSettings() {
        dismissMenu()
        val intent = android.content.Intent(context, com.xiaoniu.nursing.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun updateFloatIcon() {
        val icon = (floatBtn as? FrameLayout)?.findViewWithTag<TextView>("float_icon") ?: return
        icon.text = when (currentMode) {
            ExamAccessibilityService.MODE_COLLECT -> "📝"
            ExamAccessibilityService.MODE_ANSWER -> "✍️"
            else -> "✚"
        }
    }

    fun updateStats(count: Int) {
        lastStats = count
        updateStatusRow()
    }
    private fun updateStatusRow() {
        val modeText = when (currentMode) {
            ExamAccessibilityService.MODE_COLLECT -> "📝"
            ExamAccessibilityService.MODE_ANSWER -> "✍️"
            else -> "💤"
        }
        val count = lastStats
        menuPanel?.let { panel ->
            (panel as? ViewGroup)?.let { vg ->
                for (i in 0 until vg.childCount) {
                    val tv = vg.getChildAt(i) as? TextView
                    if (tv?.tag == "status_row") {
                        tv.text = "$modeText · 题库 ${count}题"
                    }
                }
            }
        }
    }

    // ============================================================
    // Receiver
    // ============================================================

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ExamAccessibilityService.ACTION_QUESTION_FOUND -> {
                    val question = intent.getStringExtra("question_text") ?: ""
                    val options = intent.getStringExtra("options_json") ?: "[]"
                    val confidence = intent.getFloatExtra("confidence", 1.0f)
                    val matchType = intent.getStringExtra("match_type") ?: "exact"
                    val status = if (matchType == "exact") AnswerStatus.FOUND_EXACT
                    else AnswerStatus.FOUND_FUZZY
                    showResultCard(AnswerResult(
                        status = status,
                        questionText = question,
                        options = parseOptions(options),
                        confidence = "${(confidence * 100).toInt()}%"
                    ))
                    // 闪烁绿色
                    flashIcon("✅")
                }
                ExamAccessibilityService.ACTION_QUESTION_NOT_FOUND -> {
                    val question = intent.getStringExtra("question_text") ?: ""
                    showResultCard(AnswerResult(
                        status = AnswerStatus.NOT_FOUND,
                        questionText = question
                    ))
                    flashIcon("❓")
                }
                ExamAccessibilityService.ACTION_COLLECT_DONE -> {
                    val count = intent.getIntExtra("count", 0)
                    updateStats(count)
                    flashCollectNotification(count)
                }
                ExamAccessibilityService.ACTION_MODE_CHANGED -> {
                    currentMode = intent.getStringExtra("mode") ?: ExamAccessibilityService.MODE_IDLE
                    updateFloatIcon()
                }
                ExamAccessibilityService.ACTION_SCREEN_TEXT_RESULT -> {
                    val count = intent.getIntExtra("node_count", 0)
                    val sample = intent.getStringExtra("sample") ?: ""
                    // 在状态行显示调试信息，3秒后恢复
                    menuPanel?.let { panel ->
                        (panel as? ViewGroup)?.let { vg ->
                            for (i in 0 until vg.childCount) {
                                val tv = vg.getChildAt(i) as? TextView
                                if (tv?.tag == "status_row") {
                                    val prev = tv.text
                                    tv.text = "📄 ${count}个文本节点"
                                    tv.postDelayed({ tv.text = prev }, 3000)
                                }
                            }
                        }
                    }
                    // 弹卡片显示详细信息
                    showDebugCard("检测到 ${count} 个文本节点\n前3条:\n${sample}")
                }
            }
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(ExamAccessibilityService.ACTION_QUESTION_FOUND)
            addAction(ExamAccessibilityService.ACTION_QUESTION_NOT_FOUND)
            addAction(ExamAccessibilityService.ACTION_COLLECT_DONE)
            addAction(ExamAccessibilityService.ACTION_MODE_CHANGED)
            addAction(ExamAccessibilityService.ACTION_SCREEN_TEXT_RESULT)
        }
        context.registerReceiver(resultReceiver, filter)
    }

    private fun flashIcon(emoji: String) {
        val icon = (floatBtn as? FrameLayout)?.findViewWithTag<TextView>("float_icon") ?: return
        icon.text = emoji
        icon.postDelayed({ updateFloatIcon() }, 2000)
    }

    private fun showDebugCard(msg: String) {
        // 简单 toast 风格通知
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(0xF0FFFFFF.toInt())
                setStroke(1, 0x1A000000)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                elevation = dp(10).toFloat()
            }
        }

        card.addView(textView("🐛 调试信息", 13f, 0xFF333333.toInt(), bold = true).apply {
            setPadding(0, 0, 0, dp(6))
        })
        card.addView(textView(msg, 12f, 0xFF666666.toInt()))

        val params = overlayParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            x = (dm.widthPixels - dp(300)) / 2
            y = dm.heightPixels / 4
        }
        wm.addView(card, params)
        card.postDelayed({
            card.animate().alpha(0f).setDuration(200)
                .withEndAction { safeRemove(card) }.start()
        }, 4000)
    }
    private fun flashCollectNotification(count: Int) {
        menuPanel?.let { panel ->
            (panel as? ViewGroup)?.let { vg ->
                for (i in 0 until vg.childCount) {
                    val tv = vg.getChildAt(i) as? TextView
                    if (tv?.tag == "status_row") {
                        val prev = tv.text
                        tv.text = "📥 +1 · 题库 ${count}题"
                        tv.postDelayed({ tv.text = prev }, 2000)
                    }
                }
            }
        }
        val icon = (floatBtn as? FrameLayout)?.findViewWithTag<TextView>("float_icon") ?: return
        icon.text = "📥"
        icon.postDelayed({ updateFloatIcon() }, 2000)
    }
    private fun parseOptions(json: String): List<OptResult> {
        try {
            val gson = com.google.gson.Gson()
            val arr = gson.fromJson(json, Array<OptResult>::class.java)
            return arr.toList()
        } catch (_: Exception) { return emptyList() }
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun dp(v: Int) = (v * dm.density).toInt()

    private fun safeRemove(view: View?) {
        view?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
    }

    private fun textView(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) {
                typeface = Typeface.defaultFromStyle(Typeface.BOLD)
            }
        }

    private fun divider(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { setMargins(0, dp(6), 0, dp(6)) }
        setBackgroundColor(0x1A000000)
    }

    private fun menuRow(icon: String, title: String, subtitle: String, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { onClick() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(0x0A000000)
            }
        }

        // 图标
        row.addView(TextView(context).apply {
            text = icon
            textSize = 20f
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT)
        })

        // 标题 + 副标题
        val txtCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        txtCol.addView(textView(title, 14f, 0xFF333333.toInt(), bold = true))
        txtCol.addView(textView(subtitle, 11f, 0xFF999999.toInt()))
        row.addView(txtCol)

        // 箭头
        row.addView(TextView(context).apply {
            text = "›"
            textSize = 18f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT)
        })

        return row
    }

    private fun actionChip(text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(0x0A000000)
            }
            setOnClickListener { onClick() }
        }

    // ============================================================
    // 数据类
    // ============================================================

    enum class AnswerStatus { FOUND_EXACT, FOUND_FUZZY, NOT_FOUND, COLLECTED }

    data class AnswerResult(
        val status: AnswerStatus,
        val questionText: String,
        val options: List<OptResult> = emptyList(),
        val confidence: String = ""
    )

    data class OptResult(
        val label: String = "",
        val text: String = "",
        val isCorrect: Boolean = false
    )
}
