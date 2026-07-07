---
layout: home

hero:
  name: AI回评
  text: Halo 智能评论回复插件
  tagline: 自动分析评论情感，生成自然、贴切的AI回复
  actions:
    - theme: brand
      text: 开始使用
      link: /guide/introduction
    - theme: alt
      text: 配置指南
      link: /guide/settings

features:
  - title: 自动回复
    details: 监听新评论，自动调用AI生成回复，支持对话式上下文和失败重试
  - title: 多 AI 角色
    details: 创建多个虚拟角色，独立昵称、人格提示词、性别、语气和 Gravatar 头像，支持按文章分类切换角色，人格提示词留空时使用基础配置
  - title: 情感分析
    details: 分析评论情感倾向，根据正面/中性/负面调整回复语气
  - title: 前置过滤
    details: AI回复前综合判断评论者昵称与评论内容，拦截广告/辱骂/敏感内容，节省Token
  - title: 黑/白名单
    details: 支持批量添加/移除评论者，白名单内评论跳过前置过滤，黑名单评论者不触发AI回复
  - title: 模块化提示词
    details: 拆分角色身份、安全审核、情感适配、输出规范、语言要求5个独立模块，各模块均为可选，留空即用默认值
  - title: 瞬间插件适配
    details: 检测到瞬间插件（Moments）已安装并启用时，自动为瞬间评论区启用AI自动回复
  - title: 草稿模式
    details: AI回复先存为草稿，管理员审核后再发布，支持批量操作
  - title: 对话上下文
    details: 查看完整对话上下文，支持引用摘要展示和头像显示
  - title: 智能状态检测
    details: 自动检测 AI Foundation 安装/启用/模型配置状态，区分 Comment Next 冲突并提供快捷跳转
  - title: 页面AI回复开关
    details: 全局一键启用/禁用所有页面的AI回复（包括新建页面），默认关闭
  - title: 数据管理
    details: 仪表盘统计（含已拦截数）、日志筛选搜索（含角色筛选）、实时刷新（偏好自动保存）、自动/手动清理（支持自定义时间节点）、配置导入导出
---
