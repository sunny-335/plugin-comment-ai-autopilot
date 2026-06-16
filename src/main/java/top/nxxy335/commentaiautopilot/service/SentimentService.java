package top.nxxy335.commentaiautopilot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class SentimentService {

    private final AiFoundationClient aiFoundationClient;

    public SentimentService(AiFoundationClient aiFoundationClient) {
        this.aiFoundationClient = aiFoundationClient;
    }

    public record SentimentResult(String sentiment, double confidence) {
        public static final String POSITIVE = "POSITIVE";
        public static final String NEUTRAL = "NEUTRAL";
        public static final String NEGATIVE = "NEGATIVE";
    }

    public Mono<SentimentResult> analyzeSentiment(String commentContent, String modelName) {
        String prompt = buildSentimentPrompt(commentContent);

        return aiFoundationClient.chat(prompt, modelName)
            .map(response -> {
                String sentiment = parseSentiment(response);
                return new SentimentResult(sentiment, 1.0);
            })
            .onErrorResume(e -> {
                log.warn("[Sentiment] Failed to analyze sentiment, defaulting to NEUTRAL: {}", e.getMessage());
                return Mono.just(new SentimentResult(SentimentResult.NEUTRAL, 0.0));
            })
            .defaultIfEmpty(new SentimentResult(SentimentResult.NEUTRAL, 0.0));
    }

    private String buildSentimentPrompt(String commentContent) {
        return "请分析以下评论的情感倾向。只回复一个词：POSITIVE（正面）、NEUTRAL（中性）或 NEGATIVE（负面）。\n\n评论内容：\n" + commentContent;
    }

    private String parseSentiment(String response) {
        if (response == null || response.isBlank()) return SentimentResult.NEUTRAL;
        String upper = response.trim().toUpperCase();
        if (upper.contains("POSITIVE")) return SentimentResult.POSITIVE;
        if (upper.contains("NEGATIVE")) return SentimentResult.NEGATIVE;
        return SentimentResult.NEUTRAL;
    }
}
