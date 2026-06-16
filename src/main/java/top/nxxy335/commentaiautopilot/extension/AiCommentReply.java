package top.nxxy335.commentaiautopilot.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "comment-ai-autopilot.nxxy335.top",
    version = "v1alpha1",
    kind = "AiCommentReply",
    plural = "aicommentreplies",
    singular = "aicommentreply"
)
public class AiCommentReply extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    @Schema(name = "AiCommentReplySpec")
    public static class Spec {

        @Schema(description = "关联评论ID")
        private String commentId;

        @Schema(description = "关联文章ID")
        private String postId;

        @Schema(description = "关联文章Slug，用于生成文章链接")
        private String postSlug;

        @Schema(description = "AI回复内容")
        private String reply;

        @Schema(description = "审核评分")
        private Integer score;

        @Schema(description = "状态: PENDING/REVIEWING/PASS/FAIL")
        private String status;

        @Schema(description = "重试次数")
        private Integer retryCount;

        @Schema(description = "回复目标的评论ID")
        private String replyTo;

        @Schema(description = "是否为AI对话中的回复")
        private Boolean isAiConversation;

        @Schema(description = "是否已发布回复")
        private Boolean published;

        @Schema(description = "评论情感倾向: POSITIVE/NEUTRAL/NEGATIVE")
        private String sentiment;

        @Schema(description = "使用的AI角色名称")
        private String personaName;

        @Schema(description = "关联的Reply扩展名称，草稿模式下为空")
        private String replyName;
    }
}
