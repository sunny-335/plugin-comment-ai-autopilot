package top.nxxy335.commentaiautopilot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@Slf4j
public class ReviewService {

    private final AiFoundationClient aiFoundationClient;

    public ReviewService(AiFoundationClient aiFoundationClient) {
        this.aiFoundationClient = aiFoundationClient;
    }

    /**
     * Safety classification choices.
     * Using OutputSpec.choice for reliable structured output.
     */
    private static final String SAFE = "安全";
    private static final String UNSAFE = "不安全";
    private static final List<String> SAFETY_CHOICES = List.of(SAFE, UNSAFE);

    /**
     * Quality rating choices for content quality assessment.
     * Used to produce a meaningful score (1-5) instead of binary 0/100.
     */
    private static final String RATING_EXCELLENT = "5";
    private static final String RATING_GOOD = "4";
    private static final String RATING_AVERAGE = "3";
    private static final String RATING_POOR = "2";
    private static final String RATING_BAD = "1";
    private static final List<String> QUALITY_CHOICES = List.of(
        RATING_EXCELLENT, RATING_GOOD, RATING_AVERAGE, RATING_POOR, RATING_BAD
    );

    private static final String SAFETY_SYSTEM_PROMPT = """
            你是内容安全审核员。请严格评估AI生成的回复内容是否安全合规。
            检查以下方面：
            1. 是否包含辱骂、仇恨、偏见或煽动性言论
            2. 是否包含不适宜公开传播的内容
            3. 是否涉及暴力、歧视、人身攻击或违反法律法规的内容
            只返回"安全"或"不安全"。""";

    private static final String QUALITY_SYSTEM_PROMPT = """
            你是AI回复质量评估员。请对AI生成的回复进行质量评分（1-5分）。
            评分标准：
            5分：回复切题、自然流畅、语气恰当、有帮助
            4分：回复较好，基本切题，语气自然
            3分：回复一般，基本切题但略显生硬或冗长
            2分：回复较差，偏离主题或语气不当
            1分：回复很差，完全不相关或不当
            只返回数字 1-5。""";

    /**
     * Review AI reply content using two-stage assessment:
     * 1. Safety check via OutputSpec.choice (安全/不安全)
     * 2. Quality rating via OutputSpec.choice (1-5) — only if safe
     *
     * <p>Score mapping (0-100):
     * <ul>
     *   <li>Unsafe → 0 (FAIL)</li>
     *   <li>Rating 5 → 100 (PASS)</li>
     *   <li>Rating 4 → 85 (PASS)</li>
     *   <li>Rating 3 → 70 (PASS)</li>
     *   <li>Rating 2 → 50 (PASS, borderline)</li>
     *   <li>Rating 1 → 30 (PASS, but low quality)</li>
     * </ul>
     *
     * <p><b>失败关闭策略</b>：当审核服务不可用、AI 基础设施未安装或审核异常时，
     * 默认返回 FAIL（score=0），避免未经审核的内容被自动发布。这是安全优先的取舍：
     * 宁可漏发一条回复，也不让未审核内容直接放出。
     */
    public Mono<ReviewResult> review(String articleContent, String commentContent, String aiReply,
                                      String modelName) {
        String userPrompt = String.format("""
                原始评论：
                %s

                生成的回复：
                %s

                请判断以上回复是否安全合规。""",
            truncate(commentContent, 500),
            truncate(aiReply, 500));

        // Stage 1: Safety check
        return aiFoundationClient.classify(SAFETY_SYSTEM_PROMPT, userPrompt, SAFETY_CHOICES, modelName)
            .flatMap(safetyResult -> {
                if (UNSAFE.equals(safetyResult)) {
                    log.warn("[Review] Content is UNSAFE");
                    return Mono.just(new ReviewResult(0, "FAIL", "内容安全审核不通过"));
                }
                if (!SAFE.equals(safetyResult)) {
                    log.warn("[Review] Unexpected safety result: {}, treating as unsafe", safetyResult);
                    return Mono.just(new ReviewResult(0, "FAIL", "内容安全审核结果异常"));
                }
                // Stage 2: Quality rating (only for safe content)
                return rateQuality(commentContent, aiReply, modelName);
            })
            // 失败关闭：审核无响应时标记为 FAIL，避免未审核内容被自动发布
            .defaultIfEmpty(new ReviewResult(0, "FAIL", "审核服务无响应，已安全拦截"))
            .onErrorResume(e -> {
                log.warn("[Review] Review failed, blocking reply for safety: {}", e.getMessage());
                return Mono.just(new ReviewResult(0, "FAIL", "审核服务异常，已安全拦截"));
            });
    }

    /**
     * Rate the quality of a safe AI reply (1-5) and map to a 0-100 score.
     */
    private Mono<ReviewResult> rateQuality(String commentContent, String aiReply, String modelName) {
        String qualityPrompt = String.format("""
                评论：
                %s

                回复：
                %s

                请对以上回复进行质量评分（1-5分）。""",
            truncate(commentContent, 500),
            truncate(aiReply, 500));

        return aiFoundationClient.classify(QUALITY_SYSTEM_PROMPT, qualityPrompt, QUALITY_CHOICES, modelName)
            .map(rating -> {
                int score = mapRatingToScore(rating);
                String reason = "安全通过，质量评分: " + rating + "/5";
                log.info("[Review] Content is SAFE, quality rating: {}/5, score: {}", rating, score);
                return new ReviewResult(score, "PASS", reason);
            })
            .defaultIfEmpty(new ReviewResult(85, "PASS", "安全通过，质量评分默认 4/5"))
            .onErrorResume(e -> {
                log.warn("[Review] Quality rating failed, defaulting to 85: {}", e.getMessage());
                return Mono.just(new ReviewResult(85, "PASS", "安全通过，质量评分异常"));
            });
    }

    /**
     * Map a 1-5 quality rating to a 0-100 score.
     */
    private int mapRatingToScore(String rating) {
        return switch (rating) {
            case RATING_EXCELLENT -> 100;
            case RATING_GOOD -> 85;
            case RATING_AVERAGE -> 70;
            case RATING_POOR -> 50;
            case RATING_BAD -> 30;
            default -> 70; // default to average
        };
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    public record ReviewResult(int score, String status, String reason) {}
}
