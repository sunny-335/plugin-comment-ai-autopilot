import { defineConfig } from "vitepress";

export default defineConfig({
  title: "AI回评",
  description: "Halo AI回评插件文档",
  lang: "zh-CN",
  themeConfig: {
    nav: [
      { text: "指南", link: "/guide/introduction" },
      { text: "配置", link: "/guide/settings" },
    ],
    sidebar: [
      {
        text: "开始",
        items: [
          { text: "插件介绍", link: "/guide/introduction" },
          { text: "安装与更新", link: "/guide/installation" },
        ],
      },
      {
        text: "功能",
        items: [
          { text: "自动回复", link: "/guide/auto-reply" },
          { text: "草稿模式", link: "/guide/draft-mode" },
          { text: "情感分析", link: "/guide/sentiment" },
          { text: "过滤规则", link: "/guide/filter" },
          { text: "手动触发", link: "/guide/manual-trigger" },
          { text: "数据清理", link: "/guide/cleanup" },
        ],
      },
      {
        text: "配置",
        items: [
          { text: "插件设置", link: "/guide/settings" },
          { text: "AI角色", link: "/guide/persona" },
          { text: "Prompt模板", link: "/guide/prompt" },
        ],
      },
      {
        text: "其他",
        items: [
          { text: "常见问题", link: "/guide/faq" },
        ],
      },
    ],
    socialLinks: [
      {
        icon: "github",
        link: "https://github.com/nxxy335/plugin-comment-ai-autopilot",
      },
    ],
    search: {
      provider: "local",
    },
    footer: {
      message: "基于 GPL-3.0 许可发布",
    },
  },
});
