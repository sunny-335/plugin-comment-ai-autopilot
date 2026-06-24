package top.nxxy335.commentaiautopilot.service;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.AiModelService;
import run.halo.aifoundation.chat.GenerateTextRequest;
import run.halo.aifoundation.chat.GenerateTextResult;
import run.halo.aifoundation.schema.OutputSpec;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

import java.util.List;

/**
 * AI Foundation API 隔离层。
 *
 * <p>此类集中了所有对 AI Foundation 插件 API 的直接引用（AiModelService、
 * GenerateTextRequest、GenerateTextResult、OutputSpec）。
 *
 * <p>关键设计：此类不是 Spring 组件，由 {@link AiFoundationClient} 通过
 * {@code Mono.defer()} 懒加载调用。当 AI Foundation 插件未安装时，
 * JVM 加载此类会触发 NoClassDefFoundError，该错误在
 * {@code AiFoundationClient} 的 defer + try-catch 中被捕获，
 * 从而保证插件在无 AI Foundation 的环境下仍可正常启动。
 */
@Slf4j
class AiFoundationDelegate {

    private AiFoundationDelegate() {}

    static Mono<String> chat(ExtensionGetter extensionGetter, String prompt, String modelName) {
        return extensionGetter.getEnabledExtension(AiModelService.class)
            .flatMap(service -> service.languageModel(modelName != null ? modelName : "")
                .flatMap(model -> model.generateText(
                    GenerateTextRequest.builder().prompt(prompt).maxRetries(2).build()))
                .map(GenerateTextResult::getText))
            .doOnError(e -> log.error("[Delegate] chat call failed: {}", e.getMessage()))
            .onErrorResume(e -> {
                log.warn("[Delegate] chat not available: {}", e.getMessage());
                return Mono.empty();
            });
    }

    /**
     * 使用 AI 进行文本分类。
     * 优先使用 OutputSpec.choice 结构化输出，失败时退回到普通 chat 并解析响应。
     */
    static Mono<String> classify(ExtensionGetter extensionGetter, String systemPrompt,
                                  String userPrompt, List<String> choices, String modelName) {
        log.info("[Delegate] Starting classification, modelName='{}'", modelName);
        return classifyWithChoice(extensionGetter, systemPrompt, userPrompt, choices, modelName)
            .switchIfEmpty(
                Mono.defer(() -> {
                    log.info("[Delegate] classifyWithChoice returned empty, falling back to classifyWithChat");
                    return classifyWithChat(extensionGetter, systemPrompt, userPrompt, choices, modelName);
                })
            )
            .doOnNext(result -> log.info("[Delegate] Classification succeeded: '{}'", result))
            .doOnSuccess(result -> {
                if (result == null) {
                    log.warn("[Delegate] Classification completed with no result (both methods returned empty)");
                }
            });
    }

    /**
     * 使用 OutputSpec.choice 结构化输出分类（部分模型不支持）。
     * 注意：不使用 system() 方法，因为部分 AI Foundation 版本可能不支持，
     * 将 system prompt 合并到 user prompt 中。
     */
    private static Mono<String> classifyWithChoice(ExtensionGetter extensionGetter, String systemPrompt,
                                                    String userPrompt, List<String> choices, String modelName) {
        // 合并 system prompt 和 user prompt，避免使用 system() 方法
        String combinedPrompt = systemPrompt + "\n\n" + userPrompt;
        return extensionGetter.getEnabledExtension(AiModelService.class)
            .flatMap(service -> service.languageModel(modelName != null ? modelName : "")
                .flatMap(model -> model.generateText(
                    GenerateTextRequest.builder()
                        .prompt(combinedPrompt)
                        .output(OutputSpec.choice(choices))
                        .maxRetries(2)
                        .build()))
                .flatMap(result -> {
                    Object output = result.getOutput();
                    if (output != null) {
                        String value = String.valueOf(output).trim();
                        if (!value.isEmpty()) {
                            log.debug("[Delegate] classifyWithChoice got output: '{}'", value);
                            return Mono.just(value);
                        }
                    }
                    // output 为空可能是模型不支持结构化输出，返回 empty 触发 fallback
                    log.info("[Delegate] classifyWithChoice: output is null/empty, triggering fallback");
                    return Mono.empty();
                }))
            .onErrorResume(e -> {
                log.warn("[Delegate] classifyWithChoice failed, will fallback to chat: {}", e.getMessage());
                return Mono.empty();
            });
    }

    /**
     * 使用普通 chat 调用进行分类，从响应文本中提取匹配的分类值。
     * 作为 OutputSpec.choice 不可用时的降级方案。
     * 不使用 system() 方法，将 system prompt 合并到 user prompt 中，
     * 与可用的 chat() 方法保持一致的调用方式。
     */
    private static Mono<String> classifyWithChat(ExtensionGetter extensionGetter, String systemPrompt,
                                                  String userPrompt, List<String> choices, String modelName) {
        // 合并 system prompt 和 user prompt，与 chat() 方法保持一致的调用方式
        String combinedPrompt = systemPrompt + "\n\n" + userPrompt;
        return extensionGetter.getEnabledExtension(AiModelService.class)
            .flatMap(service -> service.languageModel(modelName != null ? modelName : "")
                .flatMap(model -> model.generateText(
                    GenerateTextRequest.builder()
                        .prompt(combinedPrompt)
                        .maxRetries(2)
                        .build()))
                .map(GenerateTextResult::getText)
                .map(text -> extractChoice(text, choices)))
            .doOnError(e -> log.error("[Delegate] classifyWithChat failed: {}", e.getMessage()))
            .onErrorResume(e -> {
                log.warn("[Delegate] classifyWithChat error: {}", e.getMessage());
                return Mono.empty();
            });
    }

    /**
     * 从 chat 响应文本中提取匹配的分类值。
     * 优先精确匹配，其次包含匹配。
     */
    static String extractChoice(String text, List<String> choices) {
        if (text == null || text.isBlank()) return "";
        String trimmed = text.trim();
        // 精确匹配
        for (String choice : choices) {
            if (trimmed.equals(choice)) return choice;
        }
        // 包含匹配（响应可能包含额外文字，如"该评论属于：广告"）
        for (String choice : choices) {
            if (trimmed.contains(choice)) return choice;
        }
        // 无匹配，返回原始文本（让调用方处理）
        log.warn("[Delegate] No matching choice found in response: {}", trimmed);
        return trimmed;
    }

    static Mono<Boolean> isAvailable(ExtensionGetter extensionGetter) {
        return extensionGetter.getEnabledExtension(AiModelService.class)
            .hasElement()
            .onErrorResume(e -> {
                log.debug("[Delegate] AI Foundation not available: {}", e.getMessage());
                return Mono.just(false);
            });
    }
}
