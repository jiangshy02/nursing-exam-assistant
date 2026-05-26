# 护理助手刷题助手 - 架构设计文档

## 一、技术选型：纯原生 Android (Kotlin)

| 考虑因素 | 纯原生 Kotlin | Flutter + Native Bridge |
|---|---|---|
| AccessibilityService | ✅ 直接实现 | ❌ 仍需原生，Flutter 帮不了 |
| 悬浮窗 | ✅ WindowManager 直接操作 | ❌ 仍需原生实现 |
| 开发效率 | 核心逻辑全在 Kotlin，无桥接开销 | 多一层 MethodChannel，增加复杂度 |
| UI 美观度 | Material 3 / 自定义 View 够用 | Flutter 更好但非核心需求 |

**结论：纯原生 Kotlin，不引入 Flutter。** 核心逻辑（无障碍服务+悬浮窗）全是原生，Flutter 只会多一层桥接开销没有收益。

---

## 二、系统架构图

```
┌─────────────────────────────────────────────────────────────┐
│                      SettingsActivity                        │
│   - 开关服务 / 配置目标 App / 题库管理 / 导入导出           │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    FloatingWindowService                     │
│   - 悬浮按钮（可拖动）                                       │
│   - 展开菜单：收集模式 / 答题模式 / 题库统计                │
│   - 模式指示器                                               │
└──────────────────────────┬──────────────────────────────────┘
                           │ 通信 (LocalBroadcast / EventBus)
                           ▼
┌─────────────────────────────────────────────────────────────┐
│               ExamAccessibilityService                       │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │ 窗口状态监听  │  │  UI 树解析   │  │ 自动点击执行  │       │
│  │ (检测进入答题)│  │ (提取题目选项)│  │ (选择答案)    │       │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘       │
│         │                 │                 │                │
│         ▼                 ▼                 │                │
│  ┌─────────────────────────────────────┐    │                │
│  │         ExamParser (解析器)          │    │                │
│  │  - 题型识别 (单选/多选/判断)         │    │                │
│  │  - 题目文本提取                      │    │                │
│  │  - 选项文本提取 + 标签               │    │                │
│  │  - 正确答案识别 (收集模式)            │    │                │
│  └──────────────────┬──────────────────┘    │                │
│                     │                       │                │
│                     ▼                       ▼                │
│  ┌──────────────────────────────────────────────┐           │
│  │          QuestionMatcher (匹配引擎)           │           │
│  │  - 文本归一化 → Hash 精确匹配                │           │
│  │  - Levenshtein 编辑距离 → 模糊匹配           │           │
│  │  - 返回最佳匹配题目 + 正确选项               │           │
│  └──────────────────┬───────────────────────────┘           │
│                     │                                        │
│                     ▼                                        │
│  ┌──────────────────────────────────────────────┐           │
│  │          QuestionRepository (数据层)          │           │
│  │  - Room Database (SQLite)                    │           │
│  │  - 题目 CRUD                                 │           │
│  │  - 批量导入/导出                             │           │
│  │  - 去重逻辑                                  │           │
│  └──────────────────────────────────────────────┘           │
└─────────────────────────────────────────────────────────────┘
```

---

## 三、数据库设计

```sql
-- 题目表
CREATE TABLE questions (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    raw_text      TEXT    NOT NULL,        -- 原始题目文本
    normalized    TEXT    NOT NULL,        -- 归一化后文本
    text_hash     TEXT    NOT NULL UNIQUE, -- MD5(normalized) 用于去重
    question_type TEXT    NOT NULL,        -- single / multi / judge
    source        TEXT    DEFAULT 'collect', -- collect / import
    extra_tags    TEXT,                    -- JSON: {"chapter":"外科","difficulty":"中"}
    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL
);

-- 选项表
CREATE TABLE options (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    question_id   INTEGER NOT NULL,
    label         TEXT    NOT NULL,        -- A / B / C / D / E / √ / ×
    text          TEXT    NOT NULL,
    is_correct    INTEGER DEFAULT 0,      -- 0/1
    FOREIGN KEY (question_id) REFERENCES questions(id) ON DELETE CASCADE
);

-- 索引
CREATE INDEX idx_questions_hash  ON questions(text_hash);
CREATE INDEX idx_questions_type  ON questions(question_type);
CREATE INDEX idx_options_qid     ON options(question_id);
```

---

## 四、题目解析策略

### 4.1 如何从 Accessibility 节点树提取题目？

护理助手这类刷题 App，UI 结构通常为：

```
FrameLayout
├── TextView "1. 休克病人的护理观察要点是？"     ← 题目
├── LinearLayout
│   ├── TextView "A. 观察血压变化"                ← 选项A
│   ├── TextView "B. 观察尿量变化"                ← 选项B
│   ├── TextView "C. 观察意识状态"                ← 选项C
│   └── TextView "D. 以上都是"                    ← 选项D
└── Button "下一题"
```

**解析算法**：
1. 递归遍历 AccessibilityNodeInfo 树
2. 收集所有 `className="android.widget.TextView"` 的文本
3. 按规则分类：
   - 匹配 `/^\d+[\.、)\s]/` → 题目（编号开头）
   - 匹配 `/^[A-F][\.、)\s]/` → 选项
   - 匹配 `/^[√✓×✗xX]|^正确|^错误/` → 判断题选项
4. 相邻的选项归属同一道题

### 4.2 正确答案识别（收集模式）

用户在 App 中做完题后，App 通常会显示正确答案。识别方式：

- **颜色标记**：正确选项变绿 → 检查 `isSelected` / accessibility 描述
- **√ 标记**：文本中出现 "√" / "✓"
- **文字提示**："正确答案: A" → 正则匹配
- **提交后高亮**：某些 App 提交后会突出显示正确选项

### 4.3 题型识别

- **单选题**：选项标签为 A/B/C/D，提交后只有一个标记为正确
- **多选题**：选项标签含 A-E 多个，提交后有多个标记正确
- **判断题**：选项为 "正确/错误" 或 "√/×" 或 "对/错"

---

## 五、文本归一化与去重

```
原始: "  1.休克病人的护理观察要点是？（  ）"
归一化: "休克病人的护理观察要点是"

去重策略：
1. 去除题号前缀 (1. 2、 (1) 等)
2. 去除结尾的括号/空格
3. 去除全角空格、首尾空白
4. 全角转半角（英文字母/数字）
5. 计算 MD5 → 唯一的 text_hash
```

---

## 六、题目匹配算法

采用**两阶段匹配**：

### 阶段1：精确匹配 (O(1))
```
MD5(normalize(screen_text)) == stored_text_hash → 直接返回
```

### 阶段2：模糊匹配 (O(n))
当精确匹配失败时，用 Levenshtein 编辑距离：

```
similarity = 1 - levenshtein_distance(text1, text2) / max(len1, len2)

if similarity > 0.85 → 视为同一道题
```

### 优化
- 只用归一化的前50个字符做模糊匹配（护理题目核心信息在前半段）
- 索引加速：先按 `question_type` 过滤
- 加入 TF-IDF 关键词匹配作为备选

---

## 七、悬浮窗 UI 设计

### 折叠态（默认）
```
      ┌──────┐
      │  📋  │ ← 60dp 圆形，半透明 80%，可拖动
      └──────┘
```

### 展开态（点击后）
```
┌─────────────────────┐
│  🔍  自动收集题目    │
│  ✍️  一键答题        │
│  📊  题库 328 题     │
│  ⚙️  打开设置        │
│  ✕   关闭悬浮窗      │
└─────────────────────┘
```

### 答题状态指示
```
      ┌──────┐
      │  ✅  │ ← 绿色 = 找到答案并已自动点击
      └──────┘
      ┌──────┐
      │  ❓  │ ← 黄色 = 未在题库中找到
      └──────┘
      ┌──────┐
      │  📝  │ ← 蓝色 = 收集模式已记录
      └──────┘
```

---

## 八、核心流程

### 流程1：自动收集题目

```
用户刷题中 → 做完一道题 → App 显示答案
    │
    ▼
AccessibilityService 检测到窗口内容变化
    │
    ▼
解析：提取题目文本 + 选项 + 正确答案
    │
    ▼
归一化去重 → 存入 SQLite
    │
    ▼
悬浮按钮闪烁蓝色 💾 提示已收录
```

**触发时机**：
- 检测到 "下一题" / "提交" 按钮被点击
- 窗口内容出现 "正确答案" / "√" 标记
- 用户手动点击悬浮窗的 "收集当前题目"

### 流程2：一键自动答题

```
用户进入答题页面 → 点击悬浮按钮（答题模式）
    │
    ▼
AccessibilityService 解析当前屏幕
    │
    ▼
提取题目文本 + 题型 + 选项
    │
    ▼
归一化 → Hash 精确匹配题库
    │
    ├── 命中 → 获取正确选项 → 自动点击
    │           悬浮按钮闪烁绿色 ✅
    │
    └── 未命中 → 模糊匹配
          ├── 匹配成功 → 自动点击
          └── 匹配失败 → 悬浮按钮变黄 ❓
                         (可选：手动选择后记录)
```

---

## 九、权限需求

| 权限 | 用途 | 如何开启 |
|---|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | 读取屏幕内容 + 模拟点击 | 设置→无障碍→开启服务 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗显示 | 设置→显示在其他应用上层 |
| `FOREGROUND_SERVICE` | 保持后台运行 | 自动申请 |

---

## 十、配置化设计

因为无法确定护理助手 App 的精确 UI 结构和包名，设计为**可配置**：

### 配置项
```json
{
  "target_package": "com.example.nursing",  // 目标 App 包名
  "question_patterns": ["^(\\d+)[\\.、\\)]", "^(第\\d+题)"],  // 题目匹配正则
  "option_patterns": ["^[A-F][\\.、\\)]", "^[√✓×✗]"],
  "correct_answer_patterns": ["正确答案[:：]\\s*([A-F√✓×✗])", "correct.*?(\\w)"],
  "collect_auto_trigger": true,  // 自动触发收集
  "match_threshold": 0.85,       // 模糊匹配阈值
  "float_button_size": 60,       // 悬浮按钮大小 dp
  "float_button_opacity": 0.8    // 悬浮按钮透明度
}
```

### 获取目标 App 包名的方式
1. 内置一个"选择目标应用"列表，列出已安装 App
2. 用户运行时，悬浮窗显示当前前台 App 包名，方便确认
3. 点击"记录当前应用"一键配置

---

## 十一、模块依赖关系

```
app/
├── App.kt                       -- Application，初始化数据库
├── MainActivity.kt              -- 设置主页，管理权限和服务开关
├── model/
│   ├── Question.kt              -- 数据模型
│   ├── Option.kt
│   ├── QuestionType.kt          -- 枚举：single/multi/judge
│   └── MatchResult.kt           -- 匹配结果
├── database/
│   ├── AppDatabase.kt           -- Room Database
│   ├── QuestionDao.kt           -- DAO 接口
│   └── QuestionRepository.kt    -- 仓库层
├── parser/
│   ├── TextNormalizer.kt        -- 文本归一化 + Hash
│   ├── ExamParser.kt            -- 屏幕内容解析器
│   └── ScreenNode.kt            -- 解析后的节点数据类
├── matcher/
│   └── QuestionMatcher.kt       -- 匹配引擎
├── service/
│   ├── ExamAccessibilityService.kt  -- 无障碍服务（核心）
│   └── FloatingWindowService.kt     -- 悬浮窗服务
├── ui/
│   ├── FloatingWindowManager.kt     -- 悬浮窗管理
│   └── ConfigManager.kt            -- 配置读写
└── util/
    └── Extensions.kt                -- 扩展函数
```

---

## 十二、开发阶段规划

### 阶段1：骨架搭建 (Day 1-2)
- [x] 项目结构、Gradle 配置
- [ ] Room 数据库 + DAO
- [ ] 数据模型

### 阶段2：核心引擎 (Day 3-5)
- [ ] TextNormalizer 归一化
- [ ] ExamParser 解析器
- [ ] QuestionMatcher 匹配引擎
- [ ] 单元测试

### 阶段3：无障碍服务 (Day 6-8)
- [ ] ExamAccessibilityService 骨架
- [ ] 窗口状态监听
- [ ] UI 树遍历提取
- [ ] 模拟点击

### 阶段4：悬浮窗 (Day 9-10)
- [ ] FloatingWindowService
- [ ] 拖动、折叠/展开
- [ ] 模式切换
- [ ] 状态指示

### 阶段5：整合调试 (Day 11-14)
- [ ] 用护理助手实测调试
- [ ] 调节解析正则
- [ ] 性能优化
- [ ] 导入导出题库
