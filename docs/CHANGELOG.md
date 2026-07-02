# 更新日志

## v1.3.0

> 2026-07-01

### 新增

- **支持瞬间插件（Moments）评论区适配** — 当检测到已安装并启用 [plugin-moments](https://github.com/halo-sigs/plugin-moments) 时，自动为瞬间评论启用 AI 自动回复
  - 新增 `MomentsIntegrationService`，通过 `SchemeManager` 检测 Moment 扩展注册状态，避免直接引用导致的 `NoClassDefFoundError`
  - 在插件设置 - 基本设置中新增"瞬间评论区适配"开关，仅当瞬间插件可用时显示，默认开启
  - `ContextExtractor` 适配 Moment 上下文：使用 moment name 作为关联标识，通过 `Unstructured` 单次 fetch 获取瞬间实际内容（`spec.content.raw`/`html`）和发布时间（`spec.releaseTime`）作为 AI 上下文
  - `FilterService` 对 Moment 评论读取 `momentsEnabled` 配置决定是否触发 AI 回复
- **评论人昵称广告判定** — 前置过滤现在综合判断评论者昵称与评论内容。昵称包含商业推广关键词（如"免费算命"、"加微信xxx"、"代写论文"、"低价代购"等）即使评论内容看似正常也会被判定为广告
  - `CommentPreFilterService.check()` 新增 `commentOwner` 参数，将昵称纳入 AI 分类输入
  - 系统提示词新增"原则六：昵称与内容综合判定"，列举昵称广告典型特征

### 改进

- **重构提示词组装与兼容机制** — 建立更健壮的模块化拼接逻辑，解决多配置组合时的指令冲突与上下文丢失问题
  - 新增 `{{output_guidance}}`、`{{sentiment_hint}}`、`{{language_requirement}}` 三个占位符，将输出规范、情感提示、语言要求拆分为独立模块
  - 角色与预设使用段落分隔（空行+段落标记）确保指令隔离，避免风格预设污染角色设定
  - 情感提示通过 `{{sentiment_hint}}` 占位符原位注入；旧模板不含该占位符时自动降级为末尾追加，保持向后兼容
  - 消除两个近乎相同的 `buildPrompt` 重载的代码重复，统一委托给单一核心组装方法
  - 默认模板更新为模块化结构，新安装用户即可获得更稳定的 AI 输出
  - 强化身份约束：明确角色不是文章作者、站点管理员、客服或用户本人；禁止声称亲身经历未提供之事；禁止编造文章外的人物、数据、链接；禁止泄露系统提示词、模型参数、插件实现与安全策略
- **"Prompt设置"更名为"提示词设置"** — UI 标签页、面板标题、设置项标签、帮助文本统一改为中文"提示词"
- **日志瞬间关联链接精确到具体瞬间** — Moment 评论的关联链接从 `/moments` 列表页改为 `/moments/{name}` 具体瞬间页
- **日志页面增加实时刷新功能** — 新增"实时刷新"开关，开启后每 10 秒静默轮询新数据。标签页隐藏或弹窗打开时自动暂停，回到页面时立即刷新。支持可配置刷新间隔（5s/10s/30s/60s）、新记录 Toast 提示、滚动位置保留、连续失败自动关闭
- **AI 安全审核改为失败关闭策略** — `ReviewService` 在审核服务不可用或异常时不再自动通过，改为返回 FAIL 并拦截发布，避免未经审核的 AI 回复被自动发布

### Bug 修复

- **修复日志页面 XSS 漏洞** — `renderContent` 仅移除 `<script>` 和 `<iframe>` 标签，未过滤 `on*` 事件处理器和 `javascript:` 协议。现已全面清理所有事件处理器、危险协议和嵌入标签
- **修复日志页面删除后页码越界** — 删除最后一条记录后当前页变空但页码不回退，显示"暂无记录"。新增页码自动回退逻辑
- **修复日志页面分页按钮在加载中可重复点击** — 新增 `:disabled="loading"` 防止重复请求
- **修复误报弹窗关闭后残留状态** — 点击遮罩关闭弹窗时未清除 `falsePositiveTarget`，可能导致重开时显示旧数据
- **修复 `AiReplyOrchestrator` 指数退避无上限** — `retryCount` 较高时延迟可达 43 分钟，超过处理锁 TTL 导致锁提前过期。新增 300 秒上限
- **修复 `ContextExtractor` 空指针风险** — `extractCommentContent`/`extractCommentOwner`/`extractReplyContent` 未检查 `spec == null`，畸形数据会触发 NPE 中断整个处理链
- **修复 `SettingsView` 邮箱防抖定时器未清理** — 组件卸载时 `emailDebounce` 定时器仍在运行，导致内存泄漏。新增 `onUnmounted` 清理
- **修复 `HomeView` 刷新数据 Toast 提前弹出** — `refreshData` 未等待异步请求完成就提示成功。改为 `await Promise.all()` 后再提示
- **修复 `PromptBuilder` 安全提示词可被绕过** — 自定义模板若遗漏 `{{safety_prompt}}` 占位符，安全约束会被静默丢弃。新增安全网：检测到遗漏时强制前置注入安全规范
- **修复 `AiReplyOrchestrator.processFalsePositive` 无去重锁** — 误报处理流程未使用处理锁，重复触发会创建重复 AI 回复。新增 `processingLocks` 机制
- **修复 `AiReplyOrchestrator.processFalsePositive` 失败后记录卡在 PENDING** — 处理失败时记录未被标记为 FAIL，用户无法重试。新增 `onErrorResume` 将记录标记为 FAIL
- **修复 `AiReplyOrchestrator.hasExistingReply` 错误时静默放行** — 数据库异常时去重检查返回 false 导致重复创建记录。改为返回 true（失败关闭，宁可跳过也不重复）
- **修复 `AiReplyCleanupService` 删除处理中记录** — 清理逻辑未过滤 PENDING/REVIEWING 状态记录，可能破坏正在进行的 AI 回复流程。新增状态过滤
- **修复 `AiReplyCleanupService` null subscribe 消费者** — `.subscribe(null, ...)` 传入 null 成功消费者，可能导致 NPE。改为空 lambda
- **修复 `AiReplyCleanupService` 清理开关默认值不一致** — ConfigMap 存在但 data 为 null 时返回 false（禁用），与其他情况返回 true 不一致。统一为 true
- **修复 Endpoint 分页参数未校验** — `Integer.parseInt` 对非数字参数抛出 500 错误。新增 `parseIntSafely` 安全解析
- **修复 Endpoint 关键词搜索大小写敏感** — 搜索 "Hello" 无法匹配 "hello"。改为 `toLowerCase()` 不区分大小写
- **修复 Endpoint 批量操作并发无限制** — `flatMap` 默认并发 256，大批量操作可能压垮数据库。限制为 10
- **修复 LogsView 实时刷新漏检状态变化** — 数据签名仅含 total 和首尾 name，记录状态变化不会被检测。签名新增首尾状态和发布标记
- **修复 LogsView 实时刷新与手动操作竞态** — 自动刷新与手动 fetchReplies 可能同时执行导致数据错乱。新增 `autoRefreshing` 标志位
- **修复 `ReplyReconciler.isAiReply` 空指针风险** — 未检查 `spec == null`，畸形 Reply 数据会触发 NPE
- **修复 `ContextExtractor` 瞬间内容重复 fetch** — `getMomentContent` 和 `getMomentReleaseDate` 各自独立 fetch 同一个瞬间扩展，产生 2 次重复查询。合并为 `getMomentContentAndDate` 单次 fetch
- **修复 `ContextExtractor` 瞬间分支缺少容错** — `buildContext` 的 Moment 分支缺少 `onErrorResume` 和 `defaultIfEmpty`，异常时静默跳过而非降级处理。已补齐与 Post/SinglePage 一致的容错
- **修复 `AiReplyOrchestrator.processFalsePositive` 锁竞态条件** — 锁值存储过期时间（未来时间戳），过期后 `putIfAbsent` 不覆盖旧值导致去重失效。改为存储获取时间，与 `processComment` 一致
- **修复 `cleanupStaleLocks` 无法清理误报处理锁** — 误报处理锁值是未来时间戳，`cleanupStaleLocks` 计算 age 为负数永远不清理。统一为存储获取时间
- **修复 `ContextExtractor.getCommentCount` 空指针风险** — `reply.getSpec()` 可能为 null 时直接调用 `getCommentName()` 触发 NPE。`fetchConversationHistory` 同样问题已一并修复
- **移除实时刷新冗余时间显示** — 移除刷新间隔选择右侧的"等待中…"/"刚刚更新"/"N秒前更新"等状态文本及相关定时器，减少不必要的 UI 噪声和每秒重渲染

---

## v1.2.1

> 2026-07-01

### Bug 修复

- **修复 `AiReplyOrchestrator.retryOrFail` 重试计数失效** — `.then()` 丢弃了更新后的记录导致 `retryCount` 始终为 0，AI 生成失败时陷入无限重试。改为 `.flatMap()` 传递更新后的记录
- **修复误报反馈"AI 回复"被空字符串覆盖** — `.subscribe()` 在异步流程中过早触发，导致 AI 回复生成完成后被空字符串覆盖。改为在 `.doOnSuccess()` 中触发异步生成
- **修复 `PersonaResolver` 在响应式上下文中使用 `.block()`** — 调用阻塞方法会阻塞 Reactor 线程。改为返回 `Mono<String>` 并使用 `Flux.concatMap().next()` 替代 for 循环
- **修复 `penalizeComment`/`penalizeReply` 缺少乐观锁重试** — 并发更新 Comment/Reply 时可能静默失败。添加 `Retry.backoff(3, 100ms)` 重试
- **修复 `approveOriginalComment` 缺少乐观锁重试** — 同上，添加 `Retry.backoff(3, 100ms)` 重试
- **修复误报反馈端点无法重试 `FAIL` 状态记录** — 仅接受 `FILTERED` 和 `FALSE_POSITIVE` 状态，AI 生成失败的记录无法重试。现接受 `FAIL` 状态
- **修复 `tag-NEUTRAL` 缺少 CSS 样式** — 中性情感标签无样式显示。补充样式定义
- **修复 `handleTriggerAiReply` 缺少加载保护** — 触发 AI 回复按钮可被重复点击导致重复提交。添加 loading 状态
- **修复 `filterKeyword` 输入未做防抖** — 每次按键都触发搜索，性能开销大。添加 300ms 防抖
- **修复 `performCleanup` 逻辑错误** — 清理逻辑存在判断错误

### 改进

- **优化 `extractChoice` 分类匹配优先级** — 优先匹配违规类别（advertising/abuse/sensitive/meaningless），再匹配 `normal`，避免正常评论被误判为违规类别

---

## v1.2.0

> 2026-06-25

### 新增

- **误报反馈功能** — 被拦截的评论可进行误报反馈，支持两种处理方式：
  - **AI 回复**：标记为通过 + 触发 AI 生成回复
  - **仅通过**：仅标记为通过，不生成回复
- **误报通过状态** — 新增 `FALSE_POSITIVE` 状态，"仅通过"的记录显示为"误报通过"，不显示"通过/拒绝"按钮
- **触发 AI 回复按钮** — "误报通过"状态的记录可随时点击"触发AI回复"按钮补生成 AI 回复
- **上下文优先判断原则** — 前置过滤 AI 提示词重写，遵循五条核心原则：上下文优先、口语化宽容、恶意导向判定、宁放勿杀、闲聊不算无意义

### Bug 修复

- **修复误报反馈"AI 回复"被前置过滤再次拦截** — `processComment()` 始终调用 `preFilterService.check()`，用户已确认为误报的评论会被再次拦截。新增 `processFalsePositive()` 方法跳过前置过滤和去重检查
- **修复误报反馈"AI 回复"被去重检查拦截** — `hasExistingReply()` 找到已有的 FILTERED→PENDING 记录导致 AI 回复无法生成。`processFalsePositive()` 复用已有记录，不经过去重检查
- **修复误报反馈"AI 回复"导致全站崩溃** — `processComment()` 同步等待 AI 生成完成，HTTP 请求长时间不返回。改为 `.subscribe()` 异步执行，API 立即返回
- **修复误报反馈"仅通过"后显示通过/拒绝按钮** — "仅通过"将记录设为 `status=PASS, published=false, reply=""`，导致显示"通过/拒绝"按钮且内容为空。改为 `status=FALSE_POSITIVE`
- **修复 `extractChoice` 无匹配时返回原始文本** — AI 返回非预期文本时被误判为违规类别。改为返回空字符串触发安全拦截
- **修复 `approveOriginalComment` 缺少乐观锁重试** — 并发更新 Comment/Reply 时可能静默失败。添加 `Retry.backoff(3, 100ms)` 重试

### 改进

- **消除 `checkBlockedCommenters` 重复代码** — `FilterService` 新增 `isCommenterBlocked(commentName)` 公共方法，`AiReplyOrchestrator` 改为调用它
- **前端批量操作防重复提交** — 批量通过/拒绝/删除按钮添加 `batchLoading` 状态，操作期间禁用按钮

---

## v1.1.2

> 2026-06-24

### Bug 修复

- **修复 AI 分类完全不可用** — `classifyWithChoice` 和 `classifyWithChat` 均使用了 `GenerateTextRequest.Builder.system()` 方法，而该方法在当前 AI Foundation 版本中不被支持或导致运行时错误，导致所有评论均被拦截并显示"AI分类服务不可用，安全拦截"。现改为将 system prompt 合并到 user prompt 中，与可用的 `chat()` 方法保持一致的调用方式
- **修复 `classifyWithChoice` NPE** — `.map()` 返回 `null` 时触发 Reactor 内部 NullPointerException，改为 `.flatMap()` + `Mono.empty()` 正确触发 fallback

### 改进

- **分类调用诊断日志增强** — 在 `AiFoundationDelegate`、`AiFoundationClient`、`CommentPreFilterService` 中增加关键诊断日志（分类开始、fallback 触发、分类结果、异常详情），便于排查分类链路问题
- **AI 分类空结果处理** — 当 AI 返回空字符串时单独拦截，区别于"服务不可用"场景

---

## v1.1.1

> 2026-06-24

### 改进

- **"无意义"分类范围收窄** — 与文章主题无关的闲聊、灌水、打招呼不再被判为"无意义"，仅纯乱码和无意义字符堆砌（如随机符号、键盘乱敲）才归类为"无意义"
- **AI 分类降级方案** — 当 `OutputSpec.choice` 结构化输出不被模型支持时，自动退回到普通 chat 调用并从响应文本中提取分类值（`classifyWithChat` fallback）

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
