package top.nxxy335.commentaiautopilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import top.nxxy335.commentaiautopilot.extension.AiPersona;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Slf4j
public class PromptBuilder {

    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper;
    private static final String CONFIG_MAP_NAME = "comment-ai-autopilot-configmap";

    public PromptBuilder(ReactiveExtensionClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    private static final String PRESET_FRIENDLY = """
【友好型预设】你的回复应该热情友好，多用感叹号和表情符号，让评论者感到受欢迎。像朋友一样聊天，适当使用口语化表达。
""";

    private static final String PRESET_PROFESSIONAL = """
【专业型预设】你的回复应该专业严谨，使用正式的语言风格，避免口语化表达。回复要有逻辑性，必要时引用文章中的具体内容。
""";

    private static final String PRESET_HUMOROUS = """
【幽默型预设】你的回复可以适当加入幽默元素，使用轻松诙谐的语言，但不要过度搞笑。保持友善的同时让对话更有趣。
""";

    private static final String PRESET_CONCISE = """
【简洁型预设】你的回复应该非常简洁，一两句话即可。不要展开讨论，直接回应评论的核心内容。
""";

    private static final Map<String, String> PRESET_MAP = new LinkedHashMap<>();
    static {
        PRESET_MAP.put("friendly", PRESET_FRIENDLY);
        PRESET_MAP.put("professional", PRESET_PROFESSIONAL);
        PRESET_MAP.put("humorous", PRESET_HUMOROUS);
        PRESET_MAP.put("concise", PRESET_CONCISE);
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

            文章标题：{{post_title}}
            发布日期：{{post_date}}
            评论数：{{comment_count}}
            文章（仅供理解上下文，不要复述）：
            {{article}}

            {{conversation_history}}
            评论：
            {{comment}}
            """;

    private static final String DEFAULT_PERSONA_PROMPT = """
            你是「小回」，一个友善的评论者。你的回复简洁自然，像朋友聊天一样。简短的评论就简短回复，有深度的讨论才展开回应。不要长篇大论，不要复述文章内容。
            """;

    public Mono<String> buildPrompt(ContextExtractor.CommentContext context) {
        return Mono.zip(getPromptTemplate(), getPersonaPrompt(null), getEnabledPresetsPrompt())
            .map(tuple -> {
                String template = tuple.getT1();
                String personaPrompt = tuple.getT2();
                String presetPrompt = tuple.getT3();

                // 将预设提示词合并到 persona_prompt 之后
                String combinedPersona = personaPrompt;
                if (presetPrompt != null && !presetPrompt.isBlank()) {
                    combinedPersona = personaPrompt + "\n" + presetPrompt;
                }

                String prompt = template
                    .replace("{{persona_prompt}}", combinedPersona)
                    .replace("{{safety_prompt}}", SAFETY_PROMPT)
                    .replace("{{post_title}}", context.postTitle() != null ? context.postTitle() : "")
                    .replace("{{post_date}}", context.postDate() != null ? context.postDate() : "")
                    .replace("{{comment_count}}", String.valueOf(context.commentCount()))
                    .replace("{{article}}", context.postTitle() + "\n" + context.postContent())
                    .replace("{{conversation_history}}", formatConversationHistory(context))
                    .replace("{{comment}}", context.commentOwner() + ": " + context.commentContent());

                return prompt;
            });
    }

    public Mono<String> buildPrompt(ContextExtractor.CommentContext context, String sentiment) {
        return buildPrompt(context, sentiment, null);
    }

    public Mono<String> buildPrompt(ContextExtractor.CommentContext context, String sentiment, String personaName) {
        return Mono.zip(getPromptTemplate(), getPersonaPrompt(personaName), getEnabledPresetsPrompt())
            .map(tuple -> {
                String template = tuple.getT1();
                String personaPrompt = tuple.getT2();
                String presetPrompt = tuple.getT3();

                // 将预设提示词合并到 persona_prompt 之后
                String combinedPersona = personaPrompt;
                if (presetPrompt != null && !presetPrompt.isBlank()) {
                    combinedPersona = personaPrompt + "\n" + presetPrompt;
                }

                String prompt = template
                    .replace("{{persona_prompt}}", combinedPersona)
                    .replace("{{safety_prompt}}", SAFETY_PROMPT)
                    .replace("{{post_title}}", context.postTitle() != null ? context.postTitle() : "")
                    .replace("{{post_date}}", context.postDate() != null ? context.postDate() : "")
                    .replace("{{comment_count}}", String.valueOf(context.commentCount()))
                    .replace("{{article}}", context.postTitle() + "\n" + context.postContent())
                    .replace("{{conversation_history}}", formatConversationHistory(context))
                    .replace("{{comment}}", context.commentOwner() + ": " + context.commentContent());

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

    /**
     * Format conversation history for inclusion in the prompt.
     * Returns empty string if no history is available.
     */
    private String formatConversationHistory(ContextExtractor.CommentContext context) {
        String history = context.conversationHistory();
        if (history == null || history.isBlank()) {
            return "";
        }
        return "对话历史（供理解上下文）：\n" + history + "\n";
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

    private Mono<String> getPersonaPrompt(String personaName) {
        if (personaName != null && !personaName.isBlank()) {
            return client.fetch(AiPersona.class, personaName)
                .mapNotNull(persona -> {
                    String prompt = persona.getSpec().getPrompt();
                    return (prompt != null && !prompt.isBlank()) ? prompt : null;
                })
                .defaultIfEmpty(DEFAULT_PERSONA_PROMPT);
        }
        // Find default persona
        return client.list(AiPersona.class,
                persona -> persona.getSpec() != null && Boolean.TRUE.equals(persona.getSpec().getIsDefault()),
                null)
            .next()
            .mapNotNull(persona -> {
                String prompt = persona.getSpec().getPrompt();
                return (prompt != null && !prompt.isBlank()) ? prompt : null;
            })
            .defaultIfEmpty(DEFAULT_PERSONA_PROMPT);
    }

    private Mono<String> getEnabledPresetsPrompt() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .mapNotNull(cm -> {
                var data = cm.getData();
                if (data == null) return "";
                String promptJson = data.get("prompt");
                if (promptJson == null || promptJson.isBlank()) return "";
                try {
                    JsonNode node = objectMapper.readTree(promptJson);
                    JsonNode presetsNode = node.get("enabledPresets");
                    if (presetsNode == null) return "";
                    StringBuilder sb = new StringBuilder();
                    if (presetsNode.isArray()) {
                        for (JsonNode item : presetsNode) {
                            String key = item.asText().trim().toLowerCase();
                            if (PRESET_MAP.containsKey(key)) {
                                sb.append(PRESET_MAP.get(key));
                            }
                        }
                    } else if (presetsNode.isTextual() && !presetsNode.asText().isBlank()) {
                        String[] presetNames = presetsNode.asText().split(",");
                        for (String presetName : presetNames) {
                            String key = presetName.trim().toLowerCase();
                            if (PRESET_MAP.containsKey(key)) {
                                sb.append(PRESET_MAP.get(key));
                            }
                        }
                    }
                    return sb.toString();
                } catch (Exception e) {
                    log.warn("Failed to parse enabledPresets from ConfigMap: {}", e.getMessage());
                }
                return "";
            })
            .onErrorResume(e -> {
                log.debug("Failed to fetch enabledPresets setting: {}", e.getMessage());
                return Mono.just("");
            })
            .defaultIfEmpty("");
    }
}
