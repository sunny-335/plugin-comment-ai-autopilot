package top.nxxy335.commentaiautopilot.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import top.nxxy335.commentaiautopilot.extension.AiCommentReply;
import top.nxxy335.commentaiautopilot.service.AiReplyOrchestrator;
import top.nxxy335.commentaiautopilot.service.PersonaResolver;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class CommentReconciler implements Reconciler<Reconciler.Request> {

    private final ExtensionClient client;
    private final AiReplyOrchestrator orchestrator;
    private final PersonaResolver personaResolver;

    private static final String PROCESSED_ANNOTATION = "comment-ai-autopilot.nxxy335.top/processed";
    private static final String AI_PERSONA_OWNER_PREFIX = "ai-persona-";
    private static final String AI_MARKER_ANNOTATION = "comment-ai-autopilot.nxxy335.top/is-ai";

    // Record the time when this bean was created (plugin startup time)
    private final Instant pluginStartTime = Instant.now();

    @Override
    public Result reconcile(Request request) {
        var name = request.name();

        client.fetch(Comment.class, name).ifPresent(comment -> {
            if (isProcessed(comment.getMetadata().getAnnotations())) {
                return;
            }

            // Skip comments created before plugin startup (historical comments)
            var creationTime = comment.getMetadata().getCreationTimestamp();
            if (creationTime != null && creationTime.isBefore(pluginStartTime)) {
                log.debug("[CommentReconciler] Skipping historical comment: {} (created before plugin startup)", name);
                markProcessed(comment);
                client.update(comment);
                return;
            }

            // Skip comments from AI persona itself
            if (isAiComment(comment)) {
                markProcessed(comment);
                client.update(comment);
                return;
            }

            // Dedup: check if we already have an AiCommentReply record for this comment
            boolean alreadyHasRecord = !client.list(AiCommentReply.class,
                    record -> name.equals(record.getSpec().getCommentId())
                        && !Boolean.TRUE.equals(record.getSpec().getIsAiConversation()),
                    null)
                .isEmpty();

            if (alreadyHasRecord) {
                log.debug("[CommentReconciler] Already have AiCommentReply record for: {}, skipping", name);
                markProcessed(comment);
                client.update(comment);
                return;
            }

            // Mark as processed first to avoid re-processing
            markProcessed(comment);
            client.update(comment);

            // Read persona name from the post's annotations
            String personaName = personaResolver.getPersonaNameFromCommentBlocking(client, comment);

            // Top-level comment → always trigger AI reply
            log.info("[CommentReconciler] New top-level comment detected: {}, personaName: {}", name, personaName);
            orchestrator.processComment(name, null, false, personaName)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                    null,
                    e -> log.error("[CommentReconciler] Error processing comment {}: {}", name, e.getMessage(), e),
                    () -> log.info("[CommentReconciler] Processing completed for comment: {}", name)
                );
        });

        return Result.doNotRetry();
    }

    /**
     * Check if a comment is from AI persona.
     */
    private boolean isAiComment(Comment comment) {
        var owner = comment.getSpec().getOwner();
        if (owner != null && owner.getName() != null
            && owner.getName().startsWith(AI_PERSONA_OWNER_PREFIX)) {
            return true;
        }
        if (owner != null && owner.getAnnotations() != null
            && "true".equals(owner.getAnnotations().get(AI_MARKER_ANNOTATION))) {
            return true;
        }
        return false;
    }

    private boolean isProcessed(Map<String, String> annotations) {
        return annotations != null && "true".equals(annotations.get(PROCESSED_ANNOTATION));
    }

    private void markProcessed(Comment comment) {
        var annotations = comment.getMetadata().getAnnotations();
        if (annotations == null) {
            annotations = new HashMap<>();
            comment.getMetadata().setAnnotations(annotations);
        }
        annotations.put(PROCESSED_ANNOTATION, "true");
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
            .extension(new Comment())
            .syncAllOnStart(false)
            .build();
    }
}
