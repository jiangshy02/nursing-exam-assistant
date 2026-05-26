package com.xiaoniu.nursing

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.usage.UsageStatsManager
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.xiaoniu.nursing.database.QuestionRepository
import com.xiaoniu.nursing.service.ExamAccessibilityService
import com.xiaoniu.nursing.service.FloatingWindowService
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var repo: QuestionRepository
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val prefs by lazy { getSharedPreferences("exam_config", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = QuestionRepository(this)

        setupUI()
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun setupUI() {
        // 无障碍服务
        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("请找到「护理刷题助手」并开启")
        }

        // 悬浮窗权限
        findViewById<Button>(R.id.btn_overlay).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
        }

        // 显示悬浮窗
        findViewById<Button>(R.id.btn_float).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                toast("请先开启悬浮窗权限")
                return@setOnClickListener
            }
            if (!isAccessibilityOn()) {
                toast("请先开启无障碍服务")
                return@setOnClickListener
            }
            val intent = Intent(this, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            toast("悬浮窗已显示 ✨")
        }

        // 选择目标应用
        findViewById<Button>(R.id.btn_target_app).setOnClickListener { showAppPicker() }

        // 导出
        findViewById<Button>(R.id.btn_export).setOnClickListener { doExport() }

        // 导入
        findViewById<Button>(R.id.btn_import).setOnClickListener { showImportDialog() }

        // 清空
        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空题库")
                .setMessage("确定要清空所有题库数据吗？此操作不可恢复。")
                .setPositiveButton("确定清空") { _, _ ->
                    scope.launch {
                        repo.clearAll()
                        withContext(Dispatchers.Main) {
                            toast("题库已清空")
                            refreshStats()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun refreshAll() {
        // 无障碍状态
        val isOn = isAccessibilityOn()
        findViewById<TextView>(R.id.tv_accessibility_status).apply {
            text = if (isOn) "✅ 运行中" else "⚠️ 未开启"
            setTextColor(if (isOn) 0xFF4CAF50.toInt() else 0xFFFF5722.toInt())
        }

        // 悬浮窗权限
        val hasOverlay = Settings.canDrawOverlays(this)
        findViewById<TextView>(R.id.tv_overlay_status).apply {
            text = if (hasOverlay) "✅ 已授权" else "⚠️ 未授权"
            setTextColor(if (hasOverlay) 0xFF4CAF50.toInt() else 0xFFFF5722.toInt())
        }

        // 目标应用
        val targetPkg = prefs.getString("target_package", "") ?: ""
        val targetName = targetPkg.let { pkg ->
            if (pkg.isEmpty()) "全部应用（未指定）"
            else getAppName(pkg)
        }
        findViewById<TextView>(R.id.tv_target_app).text = targetName

        // 前台应用提示
        val fgPkg = getForegroundPackage()
        findViewById<TextView>(R.id.tv_foreground_app).text =
            if (fgPkg.isNotEmpty()) "当前前台: ${getAppName(fgPkg)}\n包名: $fgPkg"
            else "（需要开启使用情况访问权限）"

        refreshStats()
    }

    private fun refreshStats() {
        scope.launch {
            val stats = repo.getStats()
            withContext(Dispatchers.Main) {
                findViewById<TextView>(R.id.tv_stats).text = buildString {
                    append("总计 ${stats.total} 题  |  ")
                    append("单选 ${stats.singleCount}  |  ")
                    append("多选 ${stats.multiCount}  |  ")
                    append("判断 ${stats.judgeCount}")
                }
            }
        }
    }

    private fun isAccessibilityOn(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any {
            it.resolveInfo.serviceInfo.packageName == packageName
        }
    }

    private fun showAppPicker() {
        val apps = packageManager.getInstalledApplications(0)
            .filter {
                it.packageName != packageName &&
                        packageManager.getLaunchIntentForPackage(it.packageName) != null
            }
            .sortedBy { packageManager.getApplicationLabel(it).toString() }

        val names = apps.map { packageManager.getApplicationLabel(it).toString() }.toTypedArray()
        val pkgs = apps.map { it.packageName }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择目标应用（护理助手）")
            .setItems(names) { _, i ->
                prefs.edit().putString("target_package", pkgs[i]).apply()
                refreshAll()
                toast("已选择: ${names[i]}")
            }
            .setNeutralButton("清除选择") { _, _ ->
                prefs.edit().remove("target_package").apply()
                refreshAll()
            }
            .show()
    }

    private fun doExport() {
        scope.launch(Dispatchers.IO) {
            try {
                val json = repo.exportToJson()
                withContext(Dispatchers.Main) {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("题库导出", json))
                    val stats = runBlocking { repo.getStats() }
                    toast("已导出 ${stats.total} 题到剪贴板 📋")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("导出失败: ${e.message}") }
            }
        }
    }

    private fun showImportDialog() {
        val input = EditText(this).apply {
            hint = "粘贴 JSON 题库数据..."
            minLines = 6
            gravity = android.view.Gravity.TOP
            setText("[\n  {\n    \"question\": \"题目文本\",\n    \"type\": \"single\",\n    \"options\": [\n      {\"label\": \"A\", \"text\": \"选项A\", \"correct\": false},\n      {\"label\": \"B\", \"text\": \"选项B\", \"correct\": true}\n    ]\n  }\n]")
        }

        AlertDialog.Builder(this)
            .setTitle("导入题库")
            .setView(input)
            .setPositiveButton("导入") { _, _ ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val count = repo.importFromJson(input.text.toString())
                        withContext(Dispatchers.Main) {
                            toast("成功导入 $count 道题 🎉")
                            refreshStats()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            toast("导入失败: ${e.message}")
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun getForegroundPackage(): String {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return ""
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 10000, now
            )
            stats.maxByOrNull { it.lastTimeUsed }?.packageName ?: ""
        } catch (_: Exception) { "" }
    }

    private fun getAppName(pkg: String): String {
        return try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (_: Exception) { pkg }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
