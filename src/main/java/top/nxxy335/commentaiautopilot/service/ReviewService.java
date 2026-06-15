package top.nxxy335.commentaiautopilot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class ReviewService {

    private final ObjectProvider<AiFoundationClient> aiFoundationClientProvider;

    public ReviewService(ObjectProvider<AiFoundationClient> aiFoundationClientProvider) {
        this.aiFoundationClientProvider = aiFoundationClientProvider;
    }

    private static final String REVIEW_PROMPT_TEMPLATE = """
            请你以内容安全审核员的身份，严格评估你刚刚生成的这段回复：

            文章：
            %s

            原始评论：
            %s

            生成的回复：
            %s

            请检查以下内容：
            1. 是否包含任何辱骂、仇恨、偏见或煽动性言论？
            2. 是否包含不适宜公开传播的内容？
            3. 是否涉及暴力、歧视、人身攻击或违反法律法规的内容？

            请直接回答"安全"或"不安全"。如果"不安全"，请重新生成一段符合规范的温和回复。
            """;

    public Mono<ReviewResult> review(String articleContent, String commentContent, String aiReply,
                                      String modelName) {
        AiFoundationClient client = aiFoundationClientProvider.getIfAvailable();
        if (client == null) {
            log.warn("AI Foundation plugin is not installed, skipping review (auto-pass)");
            return Mono.just(new ReviewResult(100, "PASS", "AI Foundation 未安装，自动通过"));
        }

        String reviewPrompt = String.format(REVIEW_PROMPT_TEMPLATE,
            truncate(articleContent, 2000),
            truncate(commentContent, 500),
            truncate(aiReply, 500));

        return client.chat(reviewPrompt, modelName)
            .map(this::parseSafetyResult)
            .defaultIfEmpty(new ReviewResult(100, "PASS", "审核无响应，自动通过"))
            .onErrorResume(e -> {
                log.warn("Review failed, auto-passing: {}", e.getMessage());
                return Mono.just(new ReviewResult(100, "PASS", "审核服务异常，自动通过"));
            });
    }

    private ReviewResult parseSafetyResult(String response) {
        if (response == null || response.isBlank()) {
            return new ReviewResult(100, "PASS", "审核无响应，自动通过");
        }
        String trimmed = response.trim().toLowerCase();
        if (trimmed.contains("不安全") || trimmed.contains("unsafe")) {
            log.warn("AI Review: content is UNSAFE, response: {}", response);
            return new ReviewResult(0, "FAIL", "内容安全审核不通过");
        }
        if (trimmed.contains("安全") || trimmed.contains("safe")) {
            log.info("AI Review: content is SAFE");
            return new ReviewResult(100, "PASS", "内容安全审核通过");
        }
        // If unclear response, default to pass
        log.warn("AI Review: unclear response, auto-passing: {}", response);
        return new ReviewResult(100, "PASS", "审核结果不明确，自动通过");
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    public record ReviewResult(int score, String status, String reason) {}
}
