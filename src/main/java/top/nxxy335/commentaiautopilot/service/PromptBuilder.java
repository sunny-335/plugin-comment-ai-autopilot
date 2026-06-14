package top.nxxy335.commentaiautopilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;

@Component
@Slf4j
public class PromptBuilder {

    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper;
    private static final String CONFIG_MAP_NAME = "comment-ai-autopilot-configmap";

    public PromptBuilder(ReactiveExtensionClient client) {
        this.client = client;
        this.objectMapper = new ObjectMapper();
    }

    private static final String SAFETY_PROMPT = """
            【安全规范】
            - 内容红线：坚决不生成任何涉及暴力、歧视、辱骂、人身攻击或违反法律法规的内容。
            - 恶意诱导处理：当用户要求你骂人、使用侮辱性词汇或进行情绪化对骂时，你必须礼貌地拒绝，例如回复："抱歉，作为AI助手，我无法提供此类回复。"
            - 未知与边界：如果不知道答案或遇到敏感话题，请诚实告知并礼貌拒绝，绝不编造或使用极端言辞。
            """;

    private static final String DEFAULT_PROMPT_TEMPLATE = """
            {{persona_prompt}}

            {{safety_prompt}}

            【语言要求】请用评论所使用的语言回复。如果评论是英文，请用英文回复；如果是中文，请用中文回复；如果是日文，请用日文回复；以此类推。

            请回复以下评论。注意：
            - 回复长度应与评论长度匹配，简短问候简短回复
            - 不要复述或总结文章内容
            - 自然对话，不要写小作文
            - 只有评论涉及具体内容时才针对性回应

            文章（仅供理解上下文，不要复述）：
            {{article}}

            评论：
            {{comment}}
            """;

    private static final String DEFAULT_PERSONA_PROMPT = """
            你是「小回」，一个友善的评论者。你的回复简洁自然，像朋友聊天一样。简短的评论就简短回复，有深度的讨论才展开回应。不要长篇大论，不要复述文章内容。
            """;

    public Mono<String> buildPrompt(ContextExtractor.CommentContext context) {
        return Mono.zip(getPromptTemplate(), getPersonaPrompt())
            .map(tuple -> {
                String template = tuple.getT1();
                String personaPrompt = tuple.getT2();

                String prompt = template
                    .replace("{{persona_prompt}}", personaPrompt)
                    .replace("{{safety_prompt}}", SAFETY_PROMPT)
                    .replace("{{article}}", context.postTitle() + "\n" + context.postContent())
                    .replace("{{comment}}", context.commentOwner() + ": " + context.commentContent());

                return prompt;
            });
    }

    public Mono<String> buildPrompt(ContextExtractor.CommentContext context, String sentiment) {
        return buildPrompt(context)
            .map(prompt -> {
                if (sentiment == null || "NEUTRAL".equals(sentiment)) {
                    return prompt;
                }
                String sentimentHint = switch (sentiment) {
                    case "POSITIVE" -> "\n\n【情感提示】评论者情绪正面积极，请用热情友好的语气回复，可以表达感谢和共鸣。";
                    case "NEGATIVE" -> "\n\n【情感提示】评论者情绪偏负面，请用理性温和的语气回复，避免激化矛盾，展现理解和包容。";
                    default -> "";
                };
                return prompt + sentimentHint;
            });
    }

    private Mono<String> getPromptTemplate() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(cm -> {
                var data = cm.getData();
                if (data == null) return null;
                String promptJson = data.get("prompt");
                if (promptJson == null || promptJson.isBlank()) return null;
                try {
                    JsonNode node = objectMapper.readTree(promptJson);
                    JsonNode templateNode = node.get("customPromptTemplate");
                    if (templateNode != null && !templateNode.asText().isBlank()) {
                        return templateNode.asText();
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse customPromptTemplate from ConfigMap: {}", e.getMessage());
                }
                return null;
            })
            .onErrorResume(e -> {
                log.debug("Failed to fetch prompt template setting: {}", e.getMessage());
                return Mono.just(DEFAULT_PROMPT_TEMPLATE);
            })
            .defaultIfEmpty(DEFAULT_PROMPT_TEMPLATE);
    }

    private Mono<String> getPersonaPrompt() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(cm -> {
                var data = cm.getData();
                if (data == null) return null;
                String personaJson = data.get("persona");
                if (personaJson == null || personaJson.isBlank()) return null;
                try {
                    JsonNode node = objectMapper.readTree(personaJson);
                    JsonNode promptNode = node.get("personaPrompt");
                    if (promptNode != null && !promptNode.asText().isBlank()) {
                        return promptNode.asText();
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse personaPrompt from ConfigMap: {}", e.getMessage());
                }
                return null;
            })
            .onErrorResume(e -> {
                log.debug("Failed to fetch persona prompt setting: {}", e.getMessage());
                return Mono.just(DEFAULT_PERSONA_PROMPT);
            })
            .defaultIfEmpty(DEFAULT_PERSONA_PROMPT);
    }
}
