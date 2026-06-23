# 手动触发

手动触发功能允许对历史评论手动触发AI回复，适用于以下场景：

- 安装插件前已存在的评论
- 自动回复被过滤规则跳过的评论
- AI生成失败需要重试的评论

## 使用方法

1. 进入 Halo 后台 **评论** 管理页面
2. 找到需要触发AI回复的评论
3. 点击评论右侧的 **更多（…）** 按钮
4. 在下拉菜单中选择 **触发AI回复**
5. 在确认对话框中点击 **确定**

::: warning
如果该评论已有AI回复记录，触发将返回冲突提示，不会重复生成。
:::

## API 接口

插件提供了以下手动触发API：

### 触发评论回复

```
POST /apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/comments/{commentName}/trigger
```

对指定评论触发首次AI回复。

### 触发对话回复

```
POST /apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/{replyName}/trigger-conversation
```

对指定回复触发对话式AI回复。

### 更新草稿回复内容

```
PUT /apis/console.api.comment-ai-autopilot.nxxy335.top/v1alpha1/replies/{name}/content
```

更新草稿状态的AI回复内容。请求体为 JSON 格式：`{"reply": "新的回复内容"}`。仅未发布的草稿回复可编辑。

::: warning
已发布的回复不可编辑。
:::
