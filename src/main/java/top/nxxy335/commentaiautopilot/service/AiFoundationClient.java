package top.nxxy335.commentaiautopilot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

import java.util.List;

/**
 * AI Foundation 客户端，通过 Halo 的 {@link ExtensionGetter} 获取 AI 服务。
 *
 * <p>此类不直接引用任何 AI Foundation API 类（AiModelService、GenerateTextRequest 等），
 * 所有 AI Foundation 交互委托给 {@link AiFoundationDelegate}。
 * 当 AI Foundation 插件未安装时，{@link AiFoundationDelegate} 的类加载会触发
 * {@link NoClassDefFoundError}，在 {@code Mono.defer()} 中被捕获，
 * 保证插件在无 AI Foundation 环境下仍可正常启动。
 *
 * <p>需要在 plugin.yaml 中声明可选依赖：
 * <pre>
 * spec:
 *   pluginDependencies:
 *     ai-foundation?: "*"
 * </pre>
 */
@Slf4j
@Component
public class AiFoundationClient {

    private final ExtensionGetter extensionGetter;

    public AiFoundationClient(ExtensionGetter extensionGetter) {
        this.extensionGetter = extensionGetter;
    }

    /**
     * 调用 AI Foundation 生成聊天回复。
     *
     * @param prompt    提示词文本
     * @param modelName AiModel metadata.name，null 或空则使用默认模型
     * @return 生成的文本，AI Foundation 不可用时返回 empty
     */
    public Mono<String> chat(String prompt, String modelName) {
        return Mono.defer(() -> {
            try {
                return AiFoundationDelegate.chat(extensionGetter, prompt, modelName);
            } catch (NoClassDefFoundError e) {
                log.debug("AI Foundation API not on classpath: {}", e.getMessage());
                return Mono.empty();
            }
        })
        .onErrorResume(NoClassDefFoundError.class, e -> {
            log.warn("AI Foundation not available: {}", e.getMessage());
            return Mono.empty();
        });
    }

    /**
     * 调用 AI Foundation 进行文本分类，使用结构化输出（OutputSpec.choice）。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   待分类的用户输入
     * @param choices      允许的分类值列表
     * @param modelName    AiModel metadata.name，null 或空则使用默认模型
     * @return 选中的分类字符串，AI Foundation 不可用时返回 empty
     */
    public Mono<String> classify(String systemPrompt, String userPrompt,
                                  List<String> choices, String modelName) {
        return Mono.defer(() -> {
            try {
                return AiFoundationDelegate.classify(extensionGetter, systemPrompt, userPrompt, choices, modelName);
            } catch (NoClassDefFoundError e) {
                log.warn("[Client] AI Foundation API not on classpath (classify): {}", e.getMessage());
                return Mono.empty();
            }
        })
        .onErrorResume(NoClassDefFoundError.class, e -> {
            log.warn("[Client] AI Foundation NoClassDefFoundError during classify: {}", e.getMessage());
            return Mono.empty();
        });
    }

    /**
     * 检查 AI Foundation 是否可用（插件已安装且 AiModelService 扩展已启用）。
     */
    public Mono<Boolean> isAvailable() {
        return Mono.defer(() -> {
            try {
                return AiFoundationDelegate.isAvailable(extensionGetter);
            } catch (NoClassDefFoundError e) {
                log.debug("AI Foundation API not on classpath: {}", e.getMessage());
                return Mono.just(false);
            }
        })
        .onErrorResume(NoClassDefFoundError.class, e -> Mono.just(false))
        .onErrorResume(e -> {
            log.debug("AI Foundation not available: {}", e.getMessage());
            return Mono.just(false);
        });
    }
}
