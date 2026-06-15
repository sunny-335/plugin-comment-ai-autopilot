package top.nxxy335.commentaiautopilot;

import org.springframework.stereotype.Component;
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

    private final SchemeManager schemeManager;
    private final ReactiveExtensionClient client;

    public CommentAiAutopilotPlugin(PluginContext pluginContext, SchemeManager schemeManager, ReactiveExtensionClient client) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.client = client;
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
        });
        schemeManager.register(AiPersona.class);

        // 初始化默认AI角色"小回"
        initDefaultPersona();
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
