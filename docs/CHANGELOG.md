# 更新日志

## v1.1.2

> 2026-06-24

### Bug 修复

- **修复 AI 分类完全不可用** — `classifyWithChoice` 和 `classifyWithChat` 均使用了 `GenerateTextRequest.Builder.system()` 方法，而该方法在当前 AI Foundation 版本中不被支持或导致运行时错误，导致所有评论均被拦截并显示"AI分类服务不可用，安全拦截"。现改为将 system prompt 合并到 user prompt 中，与可用的 `chat()` 方法保持一致的调用方式
- **修复 `classifyWithChoice` NPE** — `.map()` 返回 `null` 时触发 Reactor 内部 NullPointerException，改为 `.flatMap()` + `Mono.empty()` 正确触发 fallback

### 改进

- **分类调用诊断日志增强** — 在 `AiFoundationDelegate`、`AiFoundationClient`、`CommentPreFilterService` 中增加关键诊断日志（分类开始、fallback 触发、分类结果、异常详情），便于排查分类链路问题
- **AI 分类空结果处理** — 当 AI 返回空字符串时单独拦截，区别于"服务不可用"场景

---

## v1.1.0

> 2026-06-23

### 新增

- **评论前置过滤（合规检测）** — AI 回复前对评论进行合规性分类，识别广告/辱骂攻击/敏感内容/无意义内容，违规评论停止生成 AI 回复，节省 Token
- **违规评论自动设为待审核** — 检测到违规评论时自动将原评论 `approved` 置为 `false`，进入待审核队列，前端不再展示该评论
- **FILTERED 日志状态** — 被拦截的评论生成"已拦截"状态记录，日志页支持按"已拦截"状态筛选
- **拦截原因分类标签** — 日志页显示拦截分类标签（广告/辱骂攻击/敏感内容/无意义）和详细拦截原因（含评论内容摘要）
- **安全优先策略** — AI 分类服务不可用或异常时，默认拦截评论而非放行，防止违规内容漏网

### 改进

- **AI Foundation 隔离加载** — 将 AI Foundation API 引用隔离到 `AiFoundationDelegate` 类，`AiFoundationClient` 不再直接引用 AI Foundation 类，修复未安装 AI Foundation 时插件无法启动的问题（`NoClassDefFoundError`）
- **评论内容 HTML 剥离** — 前置过滤检测前自动剥离评论 HTML 标签，提升 AI 分类准确性
- **对话场景精准处罚** — AI 对话场景下违规内容来自 Reply 时，仅取消通过该 Reply 而非父级 Comment，避免误伤
- **升级配置自动迁移** — 从 v1.0.x 升级时自动将 `preFilterEnabled` 从 `false` 迁移为 `true`（新默认值）

### Bug 修复

- **修复未安装 AI Foundation 时插件无法启动** — `BeanDefinitionStoreException: Failed to parse AiFoundationClient`，将 AI Foundation API 引用隔离到委托类
- **修复前置过滤默认关闭** — `preFilterEnabled` 默认值从 `false` 改为 `true`，新安装和升级用户均默认启用
- **修复 `penalize()` 遗漏 `approved=null`** — Halo 评论创建时 `approved` 可能为 `null`，原代码仅处理 `approved=true` 的情况
- **修复 `classify()` 失败时放行违规评论** — `defaultIfEmpty` 和 `onErrorResume` 改为拦截而非放行
- **修复 Windows 构建失败** — Gradle Worker Daemon 执行 pnpm 退出码 268435659，改用系统 pnpm Exec 任务并禁用 Daemon

---

## v1.0.4

> 2026-06-19

### 改进

- **对话弹窗头像显示** — 对话弹窗中每条消息显示 Gravatar 头像，基于评论者或 AI 角色的邮箱自动匹配
- **对话引用摘要** — 对话弹窗中回复消息显示引用摘要框，标明引用了谁的什么内容，支持截断显示
- **UI 全面重构** — LogsView 和 SettingsView 改用纯 Scoped CSS，移除所有 Tailwind 类和自定义 CSS 依赖，避免 Halo 主题冲突
- **标签去 Emoji 化** — 状态、情感标签改用纯色背景标签，去除所有 Emoji
- **移动端适配优化** — 全面优化移动端响应式布局，解决排版错位问题
- **AI角色设置完善** — 支持 CRUD、Gravatar 头像预览、性别/唤醒词/默认角色配置
- **配置导入导出** — 支持将插件配置（ConfigMap + AI角色）导出为 JSON 文件，方便备份和迁移
- **评论者黑名单弹窗选择** — 设置页面可从已有评论列表中选择评论者添加到黑名单

### Bug 修复

- **修复对话弹窗引用溯源** — 后端 `getConversation` 重写，构建 Reply 映射字典正确溯源引用关系
- **修复 ConversationMessage 数据结构** — 新增 `quoteOwner`/`quoteContent` 字段支持引用摘要展示
- **修复 AI 角色邮箱提取** — 后端新增 `extractOwnerEmail` 方法，正确从 CommentOwner 提取邮箱用于头像生成

---

## v1.0.3

### 改进

- **SettingsView 完整功能版** — 5个设置面板（基本设置、AI角色、模型设置、Prompt、数据清理）全部实现
- **AI角色管理** — 支持 CRUD、Gravatar 头像、性别/唤醒词/默认角色配置
- **数据清理** — 自动清理开关、保留天数滑块、手动清理
- **导入导出** — JSON 配置导入导出
- **评论者黑名单弹窗选择** — 从已有评论列表中选择评论者

---

## v1.0.2

### 改进

- **LogsView & SettingsView 样式重构** — 移除所有 Tailwind 类，改用 `<style scoped>` 原生 CSS
- **标签配色、气泡样式、引用框** — 全部使用纯 CSS 实现，避免 Halo 主题冲突

---

## v1.0.1

### 改进

- **版本号升级** — 强制刷新 Halo 前端缓存
- **历史数据兼容** — LogsView 增加历史 Markdown 引用文本清理正则，防止旧版测试数据套娃显示

---

## v1.0.0

> 2026-06-18

### 新功能

- **唤醒词**：评论以唤醒词开头可唤醒指定角色回复，支持自定义唤醒词，可在未启用AI回评的页面使用唤醒词召唤AI，二级评论同样支持
- **性别配置**：AI角色支持性别设置（男/女），AI回复时会保持对应性别身份
- **语气风格**：支持中性语气复选框，勾选后使用中性语气，取消勾选则跟随性别语气（女性温柔细腻/男性沉稳理性）
- **身份提示词强化**：角色身份信息前置到Prompt最开头（【核心身份】），安全规范中增加身份约束，确保AI始终保持角色身份

### 改进

- **优化情感分析系统**：从 3 级分类（正面/中性/负面）升级为 5 级分类（非常正面/正面/中性/负面/非常负面），情感判断更精细
- **优化日志页面 UI**：批量操作按钮重写样式，确保底色和白色文字清晰可见；搜索框添加搜索图标；重置按钮添加图标和底色
- **优化评分显示**：评分数字与等级标签之间添加间距，等级标签增加底色背景（优秀/良好/一般/较差）
- **优化状态标签**：通过状态、发布状态、情感标签统一使用带底色的标签样式
- **支持页面链接显示**：日志中新增独立页面（SinglePage）链接显示，之前仅支持文章链接
- **移动端适配**：仪表盘、配置、日志页面全面适配移动端
- **ObjectMapper 统一注入**：FilterService 和 PromptBuilder 中的 `new ObjectMapper()` 改为 Spring 构造函数注入
- **服务端过滤优化**：日志列表查询改用 `Queries.equal()` 服务端过滤 status/sentiment，减少内存过滤开销
- **新增索引**：为 AiCommentReply 扩展添加 `spec.sentiment`、`spec.published`、`spec.postKind` 索引
- **新增 postKind 字段**：区分关联内容类型（Post/SinglePage），支持页面评论的链接生成
- **PromptBuilder 情感提示**：适配 5 级情感分类，新增 VERY_POSITIVE 和 VERY_NEGATIVE 的语气提示

### Bug 修复

- **修复 ObjectMapper Bean 不存在**：Halo 插件上下文中没有自动注册 ObjectMapper Bean，创建 ObjectMapperConfiguration 手动注册
- **修复 AI 回复仍说没有性别**：将身份信息前置到 Prompt 最开头，安全规范中删除"作为AI助手"措辞，新增身份约束
- **修复唤醒词无法唤醒**：评论内容提取时对 raw 也做 HTML strip（Jsoup.clean），所有内容做 trim()，wakeWord 也做 trim()
- **修复二级评论唤醒词检查位置错误**：唤醒词检查提前到 isReplyToAi 判断之前
- **修复 SinglePage 内容获取 404**：PostContentService 不能用于 SinglePage，改用 SinglePage.getStatus().getExcerpt()
- **修复 Post/SinglePage 404 容错**：fetch 添加 onErrorResume 降级为空上下文继续处理
- **修复 Sort 参数 null 警告**：listAll 调用改为 Sort.unsorted()

---

## v1.0.0-beta.2

> 2026-06-17

### 改进

- **改用 ExtensionGetter 集成 AI Foundation**：通过 Halo 官方推荐的 `ExtensionGetter.getEnabledExtension(AiModelService.class)` 获取 AI 服务，替换原先的跨 ClassLoader 反射调用方式（[Issue #1](https://github.com/sunny-335/plugin-comment-ai-autopilot/issues/1)）
- **声明插件依赖**：在 `plugin.yaml` 中声明可选插件依赖 `ai-foundation?: "*"`，建立正确的插件依赖关系，插件在未安装 AI Foundation 时仍可正常加载
- **应用市场推荐**：新增 `store.halo.run/recommended-apps` 注解，安装本插件后可在应用市场推荐安装 AI Foundation 插件
- **使用结构化输出**：情感分析和内容审核改用 AI Foundation 的 `OutputSpec.choice` 结构化输出，替换原先的字符串匹配解析，分类更可靠
- **使用 GenerateTextRequest**：AI 调用改用 `GenerateTextRequest` 并设置 `maxRetries=2`，由 SDK 自动重试瞬时错误
- **多轮对话上下文**：AI 对话续接时自动获取之前的回复历史并注入到 Prompt 中，AI 能更好地理解对话上下文
- **优化 AI 自审核评分机制**：审核改为两阶段评估（安全检查 + 质量评分 1-5 分），评分映射到 0-100 分（0/30/50/70/85/100），替代原先的二值评分（0/100），评分更有区分度
- **精简仪表盘**：移除情感分布、近7日回复趋势、平均审核评分三个卡片，快捷操作精简为回复日志、插件设置、刷新数据
- **重做设置页面**：基本设置、AI角色设置、模型设置、Prompt设置、数据清理各为独立页面，通过标签栏切换
- **优化设置页面布局**：按钮统一排版并添加图标，侧边栏保存卡片高亮显示，新增"未保存"状态指示器
- **优化日志页面**：评分增加等级标签（优秀/良好/一般/较差），筛选下拉框修复文本与箭头重叠
- **优化 AI Foundation 状态提示宽度**：状态提示与内容区宽度一致
- **优化插件文档**：修复版本要求（2.23→2.25）、变量名（`{{conversation}}`→`{{conversation_history}}`）、GitHub 链接（`nxxy335`→`sunny-335`）、Cravatar 链接（`cravatar.cn`→`cn.cravatar.com`）等错误，补充缺失的配置项文档

### Bug 修复

- **修复自动发布后日志显示未发布**：`generateAndPublish` 中 `Mono<Void>` 的 empty 信号触发 `switchIfEmpty` 导致 `publishReply` 被调用两次，第二次覆盖 `published=false`
- **修复 RateLimitService 线程泄漏**：清理线程未在插件停止时关闭，实现 `DisposableBean` 正确释放资源
- **修复 ReviewService 提示词不匹配**：审核提示词要求"重新生成"但代码未使用重新生成的内容，移除误导性指令
- **修复设置页面按钮图标文字对齐**：通过 `:deep(.btn-content)` 设置 inline-flex 布局，图标和文字并排显示
- **修复设置页面标签栏无法点击切换**：替换不工作的 VTabbar 组件为自定义按钮实现
- **修复设置页面配置区域布局错误**：将标签栏移出 grid 容器，避免挤占配置区域宽度
- **修复日志页面筛选下拉框文本与箭头重叠**：将 `px-3` 改为 `pl-3 pr-8` 为下拉箭头预留空间

---

## v1.0.0-beta.1

> 2026-06-05

### 新功能

- **自动回复**：监听新评论，自动调用 AI 生成回复
- **多语言适配**：根据评论语言自动用对应语言回复
- **情感分析**：分析评论情感倾向，根据情感调整回复语气
- **草稿模式**：关闭"自动发布"后，AI 回复将保存为草稿，需站长审核后才发布
- **多 AI 角色支持**：支持配置多个 AI 虚拟角色，每个角色有独立的提示词、头像和模型
- **提示词预设**：内置多种回复风格预设（专业型、幽默型、简洁型等），可自由组合
- **文章/页面级开关**：在文章编辑器中直接控制是否启用 AI 回复
- **评论者黑名单**：屏蔽指定评论者，不触发 AI 回复
- **安全审核**：AI 生成的内容经过安全审核，不合规内容自动拒绝
- **Prompt 模板**：支持自定义 Prompt 模板，提供多种模板变量
- **仪表盘统计**：显示回复数等统计信息
- **日志筛选搜索**：按状态、情感筛选，关键词搜索
- **数据清理**：自动清理超过指定天数的旧记录
- **AI Foundation 集成**：使用 AI Foundation 插件提供的 AI 模型能力
- **对话历史查看**：支持查看 AI 回复的完整对话上下文

### Bug 修复

- 修复草稿模式下审批失败（"AI回复已存在，无法重复发布"）的问题
- 修复批量审批时同样的去重检查冲突问题
- 修复 AI Foundation 不可用的问题（`PluginManager` 无法通过 Spring 依赖注入获取）
- 修复 `DefaultSpringPlugin` 包级私有类反射访问权限问题
- 修复 CI 构建失败（`gradlew` 缺少执行权限）

### 改进

- 审批逻辑优化：先查找已有 Reply 扩展再决定创建或更新
- 移除不必要的 `AiFoundationConfiguration` 配置类
- 前端 UI 优化：移除编辑功能、简化角色排序逻辑、清理无用代码
