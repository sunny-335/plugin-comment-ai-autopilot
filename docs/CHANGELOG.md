# 更新日志

## v1.0.0-b26cea

> 2026-06-18

### 改进

- **优化情感分析系统**：从 3 级分类（正面/中性/负面）升级为 5 级分类（非常正面/正面/中性/负面/非常负面），情感判断更精细
- **优化日志页面 UI**：批量操作按钮重写样式，确保底色和白色文字清晰可见；搜索框添加搜索图标；重置按钮添加图标和底色
- **优化评分显示**：评分数字与等级标签之间添加间距，等级标签增加底色背景（优秀/良好/一般/较差）
- **优化状态标签**：通过状态、发布状态、情感标签统一使用带底色的标签样式
- **支持页面链接显示**：日志中新增独立页面（SinglePage）链接显示，之前仅支持文章链接
- **ObjectMapper 统一注入**：FilterService 和 PromptBuilder 中的 `new ObjectMapper()` 改为 Spring 构造函数注入
- **服务端过滤优化**：日志列表查询改用 `Queries.equal()` 服务端过滤 status/sentiment，减少内存过滤开销
- **新增索引**：为 AiCommentReply 扩展添加 `spec.sentiment`、`spec.published`、`spec.postKind` 索引
- **新增 postKind 字段**：区分关联内容类型（Post/SinglePage），支持页面评论的链接生成
- **PromptBuilder 情感提示**：适配 5 级情感分类，新增 VERY_POSITIVE 和 VERY_NEGATIVE 的语气提示

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
