package top.nxxy335.commentaiautopilot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class AiReplyService {

    private final AiFoundationClient aiFoundationClient;

    public AiReplyService(AiFoundationClient aiFoundationClient) {
        this.aiFoundationClient = aiFoundationClient;
    }

    /**
     * Generate an AI reply using the AI Foundation plugin.
     *
     * @param prompt    the prompt text
     * @param modelName the model name (null for default)
     */
    public Mono<String> generateReply(String prompt, String modelName) {
        return aiFoundationClient.chat(prompt, modelName)
            .doOnError(e -> log.error("AI reply generation failed: {}", e.getMessage()))
            .onErrorResume(e -> {
                log.warn("AI Foundation not available: {}", e.getMessage());
                return Mono.empty();
            });
    }
}
