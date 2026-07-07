package top.nxxy335.commentaiautopilot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Plugin;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.SchemeManager;

/**
 * Comment Next 插件冲突检测服务。
 *
 * <p>通过两种方式检测 plugin-comment-next 是否安装并启用：
 * <ol>
 *   <li>读取 Plugin 资源 plugin-comment-next，检查其 status.phase 是否为 STARTED</li>
 *   <li>（兜底）通过 SchemeManager 检测 commentnext.halo.run 组下的扩展是否注册</li>
 * </ol>
 *
 * <p>不直接引用 comment-next 插件的 API 类，避免未安装时触发 NoClassDefFoundError。
 */
@Component
@Slf4j
public class CommentNextDetectionService {

    private static final String PLUGIN_NAME = "plugin-comment-next";
    /** Comment Next 插件扩展使用的 GV 组（用于兜底检测）。 */
    private static final String COMMENT_NEXT_GROUP = "commentnext.halo.run";

    private final ReactiveExtensionClient client;
    private final SchemeManager schemeManager;

    public CommentNextDetectionService(ReactiveExtensionClient client, SchemeManager schemeManager) {
        this.client = client;
        this.schemeManager = schemeManager;
    }

    /**
     * 综合检测 Comment Next 插件是否安装并启用。
     *
     * @return 包含 installed 与 enabled 字段的检测结果
     */
    public Mono<CommentNextStatus> detect() {
        return client.fetch(Plugin.class, PLUGIN_NAME)
            .flatMap(plugin -> {
                boolean installed = true;
                boolean enabled = isPluginEnabled(plugin);
                return Mono.just(new CommentNextStatus(installed, enabled));
            })
            .switchIfEmpty(Mono.defer(() ->
                client.listAll(Plugin.class, ListOptions.builder().build(), Sort.unsorted())
                    .filter(p -> {
                        String name = p.getMetadata() != null ? p.getMetadata().getName() : "";
                        return name != null && (name.contains("comment-next") || name.contains("CommentNext"));
                    })
                    .next()
                    .map(p -> new CommentNextStatus(true, isPluginEnabled(p)))
                    .switchIfEmpty(Mono.defer(() -> {
                        boolean schemeRegistered = isCommentNextSchemeRegistered();
                        return Mono.just(new CommentNextStatus(schemeRegistered, schemeRegistered));
                    }))
            ))
            .onErrorResume(e -> {
                log.debug("[CommentNext] Failed to detect plugin: {}", e.getMessage());
                boolean schemeRegistered = isCommentNextSchemeRegistered();
                return Mono.just(new CommentNextStatus(schemeRegistered, schemeRegistered));
            });
    }

    /**
     * 判断 Plugin 是否已启用。
     * 检查 status.phase == STARTED，同时也检查 spec.enabled。
     */
    private boolean isPluginEnabled(Plugin plugin) {
        if (plugin.getStatus() != null && plugin.getStatus().getPhase() == Plugin.Phase.STARTED) {
            return true;
        }
        if (plugin.getSpec() != null) {
            try {
                java.lang.reflect.Method getEnabled = plugin.getSpec().getClass().getMethod("getEnabled");
                Object val = getEnabled.invoke(plugin.getSpec());
                if (Boolean.TRUE.equals(val)) return true;
            } catch (Exception ignored) {}
            if (plugin.getStatus() != null && plugin.getStatus().getPhase() != null) {
                Plugin.Phase phase = plugin.getStatus().getPhase();
                return phase == Plugin.Phase.STARTED;
            }
        }
        return false;
    }

    /**
     * 通过 SchemeManager 兜底检测 Comment Next 扩展是否已注册。
     * 检查多个可能的 GVK 组合。
     */
    private boolean isCommentNextSchemeRegistered() {
        String[] candidateGroups = {COMMENT_NEXT_GROUP, "plugin-comment-next", "commentnext"};
        String[] candidateVersions = {"v1alpha1", "v1"};
        String[] candidateKinds = {"CommentNext", "CommentNextConfig", "CommentNextSetting", "Comment", "Thread"};
        for (String group : candidateGroups) {
            for (String version : candidateVersions) {
                for (String kind : candidateKinds) {
                    try {
                        if (schemeManager.fetch(new GroupVersionKind(group, version, kind)).isPresent()) {
                            return true;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return false;
    }

    /**
     * Comment Next 检测结果。
     *
     * @param installed 是否已安装
     * @param enabled   是否已启用（installed=true 时表示 phase=STARTED）
     */
    public record CommentNextStatus(boolean installed, boolean enabled) {}
}
