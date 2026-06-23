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
            .doOnError(e -> log.error("AI Foundation call failed: {}", e.getMessage()))
            .onErrorResume(e -> {
                log.warn("AI Foundation not available: {}", e.getMessage());
                return Mono.empty();
            });
    }

    static Mono<String> classify(ExtensionGetter extensionGetter, String systemPrompt,
                                  String userPrompt, List<String> choices, String modelName) {
        return extensionGetter.getEnabledExtension(AiModelService.class)
            .flatMap(service -> service.languageModel(modelName != null ? modelName : "")
                .flatMap(model -> model.generateText(
                    GenerateTextRequest.builder()
                        .system(systemPrompt)
                        .prompt(userPrompt)
                        .output(OutputSpec.choice(choices))
                        .maxRetries(2)
                        .build()))
                .map(result -> {
                    Object output = result.getOutput();
                    return output != null ? String.valueOf(output).trim() : "";
                }))
            .doOnError(e -> log.error("AI Foundation classify failed: {}", e.getMessage()))
            .onErrorResume(e -> {
                log.warn("AI Foundation not available: {}", e.getMessage());
                return Mono.empty();
            });
    }

    static Mono<Boolean> isAvailable(ExtensionGetter extensionGetter) {
        return extensionGetter.getEnabledExtension(AiModelService.class)
            .hasElement()
            .onErrorResume(e -> {
                log.debug("AI Foundation not available: {}", e.getMessage());
                return Mono.just(false);
            });
    }
}
