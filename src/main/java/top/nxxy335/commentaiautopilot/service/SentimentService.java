package top.nxxy335.commentaiautopilot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@Slf4j
public class SentimentService {

    private final AiFoundationClient aiFoundationClient;

    public SentimentService(AiFoundationClient aiFoundationClient) {
        this.aiFoundationClient = aiFoundationClient;
    }

    public record SentimentResult(String sentiment, double confidence) {
        public static final String VERY_POSITIVE = "VERY_POSITIVE";
        public static final String POSITIVE = "POSITIVE";
        public static final String NEUTRAL = "NEUTRAL";
        public static final String NEGATIVE = "NEGATIVE";
        public static final String VERY_NEGATIVE = "VERY_NEGATIVE";
    }

    private static final List<String> CHOICES = List.of(
        SentimentResult.VERY_POSITIVE, SentimentResult.POSITIVE,
        SentimentResult.NEUTRAL, SentimentResult.NEGATIVE,
        SentimentResult.VERY_NEGATIVE
    );

    /**
     * Analyze sentiment using AI Foundation structured output
     * ({@code OutputSpec.choice}) for reliable classification.
     */
    public Mono<SentimentResult> analyzeSentiment(String commentContent, String modelName) {
        String systemPrompt = "你是一个专业的情感分析助手。请根据以下标准分析评论的情感倾向：\n"
            + "\n"
            + "- VERY_POSITIVE：非常正面，包含强烈的感谢、赞美或认同（如\"太棒了\"、\"非常感谢\"、\"写得太好了\"）\n"
            + "- POSITIVE：正面，友好、肯定或支持的态度（如\"不错\"、\"学习了\"、\"支持\"）\n"
            + "- NEUTRAL：中性，提问、讨论、陈述事实，无明显情感倾向（如\"请问...\"、\"这个怎么用\"、\"我觉得\"）\n"
            + "- NEGATIVE：负面，不满、质疑或批评（如\"不好用\"、\"有问题\"、\"不太行\"）\n"
            + "- VERY_NEGATIVE：非常负面，攻击、辱骂或极端负面情绪（如\"垃圾\"、\"骗子\"、\"太差了\"）\n"
            + "\n"
            + "只返回 VERY_POSITIVE、POSITIVE、NEUTRAL、NEGATIVE 或 VERY_NEGATIVE 之一。";
        String userPrompt = "分析以下评论的情感倾向：\n\n" + commentContent;

        return aiFoundationClient.classify(systemPrompt, userPrompt, CHOICES, modelName)
            .map(sentiment -> {
                String upper = sentiment.toUpperCase();
                // Validate against known choices; default to NEUTRAL if unexpected
                if (!CHOICES.contains(upper)) {
                    log.warn("[Sentiment] Unexpected classification result: {}, defaulting to NEUTRAL", sentiment);
                    return new SentimentResult(SentimentResult.NEUTRAL, 0.0);
                }
                return new SentimentResult(upper, 1.0);
            })
            .onErrorResume(e -> {
                log.warn("[Sentiment] Failed to analyze sentiment, defaulting to NEUTRAL: {}", e.getMessage());
                return Mono.just(new SentimentResult(SentimentResult.NEUTRAL, 0.0));
            })
            .defaultIfEmpty(new SentimentResult(SentimentResult.NEUTRAL, 0.0));
    }
}
