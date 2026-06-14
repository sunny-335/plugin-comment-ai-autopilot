# AI回评 / Comment AI Autopilot

基于 AI 的 Halo 博客评论自动回复插件，支持 AI 虚拟角色回复、自审核、自动发布和对话式连续回复。

## 功能特性

- **自动回复** — 监听新评论，自动调用AI生成回复，支持多轮对话上下文
- **多语言适配** — 根据评论语言自动用对应语言回复
- **情感分析** — 分析评论情感倾向（正面/中性/负面），根据情感调整回复语气
- **草稿模式** — AI回复先存为草稿，管理员审核后再发布，支持批量操作
- **失败重试** — AI生成失败时自动重试，指数退避策略
- **文章/页面级开关** — 在文章编辑器中直接控制是否启用AI回复，文章默认开启，页面默认关闭
- **评论者黑名单** — 支持按名称和邮箱屏蔽指定评论者，可从评论列表选择
- **手动触发** — 在评论管理页面对历史评论手动触发AI回复
- **AI角色** — 自定义AI回复者的昵称、人格提示词和Gravatar头像，设置页面实时预览头像
- **安全审核** — AI生成的内容经过安全审核，不合规内容自动拒绝
- **仪表盘统计** — 显示回复数、情感分布、每日回复趋势等图表
- **日志筛选** — 按状态、情感筛选，关键词搜索
- **数据清理** — 自动清理超过指定天数的旧记录
- **AI Foundation 集成** — 必须安装 Halo AI Foundation 插件，使用其提供的AI模型能力

## 前置要求

- Halo 2.23+
- AI Foundation 插件（必须）

## 安装

1. 前往 [Releases](https://github.com/暖心向阳335/comment-ai-autopilot/releases) 下载最新的 `.jar` 文件
2. 登录 Halo 管理后台
3. 进入 **插件** → **已安装** → 点击右上角 **安装** 按钮
4. 选择下载的 `.jar` 文件上传
5. 安装完成后启用插件

## 从源码构建

```bash
# 克隆仓库
git clone https://github.com/暖心向阳335/comment-ai-autopilot.git
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

完整文档请访问 [AI回评文档站](https://暖心向阳335.github.io/comment-ai-autopilot/)

## 许可证

[GPL-3.0](./LICENSE) © 暖心向阳335
