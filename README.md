# AI回评 / Comment AI Autopilot

基于 AI 的 Halo 博客评论自动回复插件，支持多 AI 角色、自审核、自动发布和对话式连续回复。

> **当前版本**: v1.0.0-beta.1

## 功能特性

- **多 AI 角色** — 支持创建多个 AI 角色，每个角色有独立的昵称、人格提示词和 Gravatar 头像，可为不同文章指定不同角色
- **自动回复** — 监听新评论，自动调用 AI 生成回复，支持多轮对话上下文
- **多语言适配** — 根据评论语言自动用对应语言回复
- **情感分析** — 分析评论情感倾向（正面/中性/负面），根据情感调整回复语气
- **草稿模式** — AI 回复先存为草稿，管理员审核后再发布，支持批量操作
- **失败重试** — AI 生成失败时自动重试，指数退避策略
- **对话轮次限制** — 同一评论线程中限制 AI 最多回复轮次，防止无限对话
- **速率限制** — 每分钟最大 AI 回复数量，防止批量评论消耗过多额度
- **文章/页面级开关** — 在文章编辑器中直接控制是否启用 AI 回复，文章默认开启，页面默认关闭
- **评论者黑名单** — 支持按名称、邮箱和正则表达式屏蔽指定评论者，可从评论列表选择
- **手动触发** — 在评论管理页面对历史评论手动触发 AI 回复
- **安全审核** — AI 生成的内容经过安全审核，不合规内容自动拒绝
- **Prompt 模板** — 支持自定义 Prompt 模板，提供多种模板变量（文章标题、发布日期、评论数等）
- **Prompt 预设** — 内置友好型、专业型、幽默型、简洁型预设风格，可多选组合
- **仪表盘统计** — 显示回复数、情感分布、每日回复趋势等图表，支持时间范围切换
- **插件健康检查** — 实时检测 AI Foundation 连接状态和模型可用性
- **日志筛选** — 按状态、情感筛选，关键词搜索
- **数据清理** — 自动清理超过指定天数的旧记录
- **AI Foundation 集成** — 必须安装 Halo AI Foundation 插件，使用其提供的 AI 模型能力

## 前置要求

- Halo 2.25+
- AI Foundation 插件（必须）

## 安装

1. 前往 [Releases](https://github.com/sunny-335/plugin-comment-ai-autopilot/releases) 下载最新的 `.jar` 文件
2. 登录 Halo 管理后台
3. 进入 **插件** → **已安装** → 点击右上角 **安装** 按钮
4. 选择下载的 `.jar` 文件上传
5. 安装完成后启用插件

## 从源码构建

```bash
# 克隆仓库
git clone https://github.com/sunny-335/plugin-comment-ai-autopilot.git
cd plugin-comment-ai-autopilot

# 构建
./gradlew build -x test

# 构建产物位于 build/libs/ 目录
```

## 开发

```bash
# 启用插件开发服务器
./gradlew haloServer

# 开发前端
cd ui
pnpm install
pnpm dev
```

## 文档

完整文档请访问 [AI回评文档站](https://nxxy335.top/comment-ai-autopilot)

## 更新日志

### v1.0.0-beta.1

**新功能**

- AI Foundation 反射集成：通过运行时反射调用 AI Foundation 插件的 `AiModelService`，无需 `pluginDependencies`，彻底解决跨 ClassLoader 类加载问题
- 草稿模式：关闭"自动发布"后，AI 回复将保存为草稿，需站长审核后才发布
- 多 AI 角色支持：支持配置多个 AI 虚拟角色，每个角色有独立的提示词、头像和模型
- 提示词预设：内置多种回复风格预设（专业型、幽默型、简洁型等），可自由组合
- 每日回复趋势图：首页新增每日回复数量柱状图，支持 7 天/30 天/全部切换
- 对话历史查看：支持查看 AI 回复的完整对话上下文

**Bug 修复**

- 修复草稿模式下审批失败（"AI回复已存在，无法重复发布"）的问题
- 修复批量审批时同样的去重检查冲突问题
- 修复 AI Foundation 不可用的问题（`PluginManager` 无法通过 Spring 依赖注入获取）
- 修复 `DefaultSpringPlugin` 包级私有类反射访问权限问题
- 修复 CI 构建失败（`gradlew` 缺少执行权限）

**改进**

- 审批逻辑优化：先查找已有 Reply 扩展再决定创建或更新
- 移除不必要的 `AiFoundationConfiguration` 配置类
- 前端 UI 优化：移除编辑功能、简化角色排序逻辑、清理无用代码

### v0.0.3

- 增强插件可靠性与可用性
- 多 AI 角色支持
- 草稿模式初步实现
- 文档全面更新

## 许可证

[GPL-3.0](https://github.com/sunny-335/plugin-comment-ai-autopilot/blob/main/LICENSE) © 暖心向阳335
