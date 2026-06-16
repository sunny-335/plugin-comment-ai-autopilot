package top.nxxy335.commentaiautopilot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Plugin;
import run.halo.app.extension.ReactiveExtensionClient;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * AI Foundation client that uses runtime class loading and reflection
 * to call the AI Foundation plugin's AiModelService.
 * <p>
 * This approach avoids classloader identity issues by loading AiModelService
 * from ai-foundation's own classloader, so that Spring's getBeansOfType()
 * can correctly match the implementation bean.
 * <p>
 * No @ConditionalOnClass or pluginDependencies needed.
 * Always registered as a bean; availability is checked at runtime.
 */
@Slf4j
@Component
public class AiFoundationClient {

    private static final String AI_FOUNDATION_PLUGIN_NAME = "ai-foundation";
    private static final String AI_MODEL_SERVICE_CLASS = "run.halo.aifoundation.AiModelService";

    private final ReactiveExtensionClient client;
    private final ApplicationContext applicationContext;

    public AiFoundationClient(ReactiveExtensionClient client, ApplicationContext applicationContext) {
        this.client = client;
        this.applicationContext = applicationContext;
    }

    /**
     * Call AI Foundation to generate a chat response using the specified model.
     *
     * @param prompt    the prompt text
     * @param modelName the AiModel metadata.name, null or blank to use default model
     * @return the generated text, or empty if AI Foundation is unavailable
     */
    public Mono<String> chat(String prompt, String modelName) {
        return isAiFoundationEnabled()
            .flatMap(enabled -> {
                if (!enabled) {
                    log.warn("AI Foundation plugin is not installed or not enabled, skipping AI reply");
                    return Mono.empty();
                }
                return doChat(prompt, modelName);
            });
    }

    /**
     * Check if AI Foundation is available: plugin installed, enabled, and AiModelService bean found.
     */
    public Mono<Boolean> isAvailable() {
        return isAiFoundationEnabled()
            .flatMap(enabled -> {
                if (!enabled) return Mono.just(false);
                return findAiModelService().hasElement();
            });
    }

    private Mono<Boolean> isAiFoundationEnabled() {
        return client.fetch(Plugin.class, AI_FOUNDATION_PLUGIN_NAME)
            .map(plugin -> plugin.getSpec().getEnabled())
            .defaultIfEmpty(false)
            .onErrorResume(e -> {
                log.debug("Failed to check AI Foundation plugin status: {}", e.getMessage());
                return Mono.just(false);
            });
    }

    private Mono<String> doChat(String prompt, String modelName) {
        return findAiModelService()
            .flatMap(service -> invokeLanguageModel(service, modelName)
                .flatMap(model -> invokeGenerateText(model, prompt))
            )
            .doOnError(e -> log.error("AI Foundation call failed: {}", e.getMessage()))
            .onErrorResume(e -> {
                log.warn("AI Foundation not available: {}", e.getMessage());
                return Mono.empty();
            });
    }

    /**
     * Get PluginManager via the pluginWrapper bean registered in our plugin context.
     * Halo's DefaultPluginApplicationContextFactory registers pluginWrapper as a singleton:
     *   beanFactory.registerSingleton("pluginWrapper", pluginWrapper);
     * Then PluginWrapper.getPluginManager() gives us the PluginManager instance.
     */
    private Object findPluginManager() {
        try {
            Object pluginWrapper = applicationContext.getBean("pluginWrapper");
            Method getPluginManagerMethod = pluginWrapper.getClass().getMethod("getPluginManager");
            getPluginManagerMethod.setAccessible(true);
            Object pm = getPluginManagerMethod.invoke(pluginWrapper);
            if (pm != null) {
                log.info("Found PluginManager via pluginWrapper bean: {}", pm.getClass().getName());
            }
            return pm;
        } catch (NoSuchMethodException e) {
            log.warn("pluginWrapper does not have getPluginManager() method: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to get PluginManager via pluginWrapper: {}", e.getMessage());
        }
        log.warn("PluginManager not found");
        return null;
    }

    /**
     * Find the AiModelService bean from ai-foundation's PluginApplicationContext.
     * Uses PluginManager.getPlugin() to get the plugin wrapper, then reflection
     * to get the plugin's ApplicationContext.
     */
    private Mono<Object> findAiModelService() {
        return Mono.fromCallable(() -> {
            Object pm = findPluginManager();
            if (pm == null) return null;

            // Call pm.getPlugin("ai-foundation") via reflection
            Method getPluginMethod = pm.getClass().getMethod("getPlugin", String.class);
            getPluginMethod.setAccessible(true);
            Object pluginWrapper = getPluginMethod.invoke(pm, AI_FOUNDATION_PLUGIN_NAME);
            if (pluginWrapper == null) {
                log.debug("ai-foundation plugin not found in PluginManager");
                return null;
            }

            // Call pluginWrapper.getPlugin() to get the plugin instance
            Method getPluginInstanceMethod = pluginWrapper.getClass().getMethod("getPlugin");
            getPluginInstanceMethod.setAccessible(true);
            Object pluginInstance = getPluginInstanceMethod.invoke(pluginWrapper);
            if (pluginInstance == null) {
                log.debug("ai-foundation plugin instance is null");
                return null;
            }

            // Get the plugin's ApplicationContext via reflection on SpringPlugin
            // DefaultSpringPlugin is package-private, so we need setAccessible
            Method getCtxMethod = pluginInstance.getClass().getMethod("getApplicationContext");
            getCtxMethod.setAccessible(true);
            ApplicationContext pluginAppContext = (ApplicationContext) getCtxMethod.invoke(pluginInstance);

            // Get the plugin classloader
            Method getClassLoaderMethod = pluginWrapper.getClass().getMethod("getPluginClassLoader");
            getClassLoaderMethod.setAccessible(true);
            ClassLoader pluginClassLoader = (ClassLoader) getClassLoaderMethod.invoke(pluginWrapper);

            // Load AiModelService from ai-foundation's classloader
            Class<?> aiModelServiceClass = pluginClassLoader.loadClass(AI_MODEL_SERVICE_CLASS);

            // Find the AiModelService bean in ai-foundation's ApplicationContext
            Map<String, ?> beans = pluginAppContext.getBeansOfType(aiModelServiceClass);
            if (beans.isEmpty()) {
                log.debug("AiModelService bean not found in ai-foundation's ApplicationContext");
                return null;
            }

            log.info("Found AiModelService bean in ai-foundation's ApplicationContext");
            Object result = beans.values().iterator().next();
            return (Object) result;
        }).doOnError(e -> log.error("Failed to find AiModelService: {}", e.getMessage()));
    }

    /**
     * Call service.languageModel(modelName) or service.languageModel() via reflection.
     * Returns Mono&lt;LanguageModel&gt; from ai-foundation's classloader.
     */
    private Mono<Object> invokeLanguageModel(Object service, String modelName) {
        return Mono.fromCallable(() -> {
            Method method;
            if (modelName != null && !modelName.isBlank()) {
                method = service.getClass().getMethod("languageModel", String.class);
                method.setAccessible(true);
                return method.invoke(service, modelName);
            } else {
                method = service.getClass().getMethod("languageModel");
                method.setAccessible(true);
                return method.invoke(service);
            }
        }).flatMap(result -> {
            if (result instanceof Mono<?> mono) return mono;
            return Mono.justOrEmpty(result);
        });
    }

    /**
     * Call model.generateText(prompt) via reflection, then extract text from result.
     * Returns the generated text string.
     */
    private Mono<String> invokeGenerateText(Object model, String prompt) {
        return Mono.fromCallable(() -> {
            Method method = model.getClass().getMethod("generateText", String.class);
            method.setAccessible(true);
            return method.invoke(model, prompt);
        }).flatMap(result -> {
            if (result instanceof Mono<?> mono) {
                return mono.map(this::extractText);
            }
            return Mono.justOrEmpty(extractText(result));
        });
    }

    private String extractText(Object result) {
        if (result == null) return null;
        try {
            Method getText = result.getClass().getMethod("getText");
            getText.setAccessible(true);
            return (String) getText.invoke(result);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call getText() on GenerateTextResult: " + e.getMessage(), e);
        }
    }
}
