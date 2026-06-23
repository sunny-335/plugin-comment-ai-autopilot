package top.nxxy335.commentaiautopilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.Metadata;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import top.nxxy335.commentaiautopilot.extension.AiCommentReply;
import top.nxxy335.commentaiautopilot.extension.AiPersona;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * <p>Plugin main class to manage the lifecycle of the plugin.</p>
 * <p>This class must be public and have a public constructor.</p>
 * <p>Only one main class extending {@link BasePlugin} is allowed per plugin.</p>
 *
 * @author 暖心向阳335
 * @since 1.0.0
 */
@Slf4j
@Component
public class CommentAiAutopilotPlugin extends BasePlugin {

    private static final String CONFIG_MAP_NAME = "comment-ai-autopilot-configmap";

    private final SchemeManager schemeManager;
    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper;

    public CommentAiAutopilotPlugin(PluginContext pluginContext, SchemeManager schemeManager,
                                     ReactiveExtensionClient client, ObjectMapper objectMapper) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start() {
        schemeManager.register(AiCommentReply.class, indexSpecs -> {
            indexSpecs.add(IndexSpecs.<AiCommentReply, String>single("spec.commentId", String.class)
                .indexFunc(ext -> ext.getSpec().getCommentId()));
            indexSpecs.add(IndexSpecs.<AiCommentReply, String>single("spec.postId", String.class)
                .indexFunc(ext -> ext.getSpec().getPostId()));
            indexSpecs.add(IndexSpecs.<AiCommentReply, String>single("spec.status", String.class)
                .indexFunc(ext -> ext.getSpec().getStatus()));
            indexSpecs.add(IndexSpecs.<AiCommentReply, String>single("spec.sentiment", String.class)
                .indexFunc(ext -> ext.getSpec().getSentiment()));
            indexSpecs.add(IndexSpecs.<AiCommentReply, String>single("spec.published", String.class)
                .indexFunc(ext -> String.valueOf(ext.getSpec().getPublished())));
            indexSpecs.add(IndexSpecs.<AiCommentReply, String>single("spec.postKind", String.class)
                .indexFunc(ext -> ext.getSpec().getPostKind()));
        });
        schemeManager.register(AiPersona.class);

        // 初始化默认AI角色"小回"
        initDefaultPersona();

        // 迁移：确保升级用户的前置过滤配置正确
        migratePreFilterConfig();
    }

    /**
     * 迁移前置过滤配置：从 v1.0.x 升级到 v1.1.0 时，
     * ConfigMap 中可能保存了旧默认值 preFilterEnabled=false，
     * 需要将其更新为 true（新默认值）。
     */
    private void migratePreFilterConfig() {
        client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .flatMap(cm -> {
                var data = cm.getData();
                if (data == null) return Mono.empty();
                String basicJson = data.get("basic");
                if (basicJson == null || basicJson.isBlank()) return Mono.empty();
                try {
                    JsonNode node = objectMapper.readTree(basicJson);
                    if (!node.has("preFilterEnabled")) {
                        // 字段不存在，添加并设为 true
                        ((ObjectNode) node).put("preFilterEnabled", true);
                        data.put("basic", objectMapper.writeValueAsString(node));
                        return client.update(cm)
                            .doOnSuccess(c -> log.info("[Migration] Added preFilterEnabled=true to ConfigMap"));
                    }
                    if (node.has("preFilterEnabled") && !node.get("preFilterEnabled").asBoolean(true)) {
                        // 字段存在但为 false（旧默认值），迁移为 true
                        ((ObjectNode) node).put("preFilterEnabled", true);
                        data.put("basic", objectMapper.writeValueAsString(node));
                        return client.update(cm)
                            .doOnSuccess(c -> log.info("[Migration] Migrated preFilterEnabled from false to true"));
                    }
                } catch (Exception e) {
                    log.warn("[Migration] Failed to migrate preFilter config: {}", e.getMessage());
                }
                return Mono.empty();
            })
            .subscribe(
                null,
                err -> log.debug("[Migration] PreFilter config migration skipped: {}", err.getMessage()),
                () -> log.debug("[Migration] PreFilter config migration check completed")
            );
    }

    private void initDefaultPersona() {
        client.fetch(AiPersona.class, "default-ai-persona")
            .switchIfEmpty(Mono.defer(() -> {
                log.info("初始化默认AI角色：小回");
                AiPersona persona = new AiPersona();
                persona.setMetadata(new Metadata());
                persona.getMetadata().setName("default-ai-persona");
                AiPersona.AiPersonaSpec spec = new AiPersona.AiPersonaSpec();
                spec.setDisplayName("小回");
                spec.setPrompt("你是一个友善的评论者，回复简洁自然，像朋友聊天一样。");
                spec.setEmail("");
                spec.setGender("female");
                spec.setNeutralVoice(true);
                spec.setWakeWord("小回小回");
                spec.setIsDefault(true);
                persona.setSpec(spec);
                return client.create(persona);
            }))
            .subscribe(
                created -> log.info("默认AI角色已就绪"),
                err -> log.warn("初始化默认AI角色失败: {}", err.getMessage())
            );
    }

    @Override
    public void stop() {
        schemeManager.unregister(Scheme.buildFromType(AiCommentReply.class));
        schemeManager.unregister(Scheme.buildFromType(AiPersona.class));
    }
}
