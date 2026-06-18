package top.nxxy335.commentaiautopilot.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Reply;
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
public class ReplyReconciler implements Reconciler<Reconciler.Request> {

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

        client.fetch(Reply.class, name).ifPresent(reply -> {
            if (isProcessed(reply.getMetadata().getAnnotations())) {
                return;
            }

            // Skip replies created before plugin startup (historical replies)
            var creationTime = reply.getMetadata().getCreationTimestamp();
            if (creationTime != null && creationTime.isBefore(pluginStartTime)) {
                log.debug("[ReplyReconciler] Skipping historical reply: {} (created before plugin startup)", name);
                markProcessed(reply);
                client.update(reply);
                return;
            }

            // Skip replies from AI persona itself
            var owner = reply.getSpec().getOwner();
            if (owner != null && owner.getName() != null
                && owner.getName().startsWith(AI_PERSONA_OWNER_PREFIX)) {
                markProcessed(reply);
                client.update(reply);
                return;
            }
            // Also skip if owner has AI marker annotation
            if (owner != null && owner.getAnnotations() != null
                && "true".equals(owner.getAnnotations().get(AI_MARKER_ANNOTATION))) {
                markProcessed(reply);
                client.update(reply);
                return;
            }

            String parentCommentName = reply.getSpec().getCommentName();
            if (parentCommentName == null || parentCommentName.isBlank()) {
                markProcessed(reply);
                client.update(reply);
                return;
            }

            // Check if this reply is specifically replying to an AI reply
            // by checking the quoteReply field
            String quoteReply = reply.getSpec().getQuoteReply();

            if (quoteReply == null || quoteReply.isBlank()) {
                // No quoteReply - this is a direct reply to the top-level comment,
                // NOT a reply to AI. Skip it (CommentReconciler handles top-level comments).
                log.debug("[ReplyReconciler] Reply {} has no quoteReply, skipping (not a reply to AI)", name);
                markProcessed(reply);
                client.update(reply);
                return;
            }

            // This reply quotes another reply - check if the quoted reply is from AI
            boolean isReplyToAi = isAiReply(quoteReply);
            log.debug("[ReplyReconciler] Reply {} quotes {}, isAiReply={}", name, quoteReply, isReplyToAi);

            if (!isReplyToAi) {
                log.debug("[ReplyReconciler] Not a reply to AI, skipping: {}", name);
                markProcessed(reply);
                client.update(reply);
                return;
            }

            // Dedup: check if we already have an AiCommentReply record for this reply
            boolean alreadyHasRecord = !client.list(AiCommentReply.class,
                    record -> name.equals(record.getSpec().getReplyTo())
                        && Boolean.TRUE.equals(record.getSpec().getIsAiConversation()),
                    null)
                .isEmpty();

            if (alreadyHasRecord) {
                log.debug("[ReplyReconciler] Already have AiCommentReply record for reply: {}, skipping", name);
                markProcessed(reply);
                client.update(reply);
                return;
            }

            // Mark as processed
            markProcessed(reply);
            client.update(reply);

            // Reply to AI → trigger AI reply (conversation continuation)
            String personaName = client.fetch(Comment.class, parentCommentName)
                .map(comment -> personaResolver.getPersonaNameFromCommentBlocking(client, comment))
                .orElse(null);
            log.info("[ReplyReconciler] Reply to AI detected: {}, triggering conversation, personaName: {}", name, personaName);
            orchestrator.processComment(parentCommentName, name, true, personaName)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                    null,
                    e -> log.error("[ReplyReconciler] Error processing reply {}: {}", name, e.getMessage(), e),
                    () -> log.info("[ReplyReconciler] Processing completed for reply: {}", name)
                );
        });

        return Result.doNotRetry();
    }

    /**
     * Check if a specific Reply is from AI persona.
     */
    private boolean isAiReply(String replyName) {
        return client.fetch(Reply.class, replyName)
            .map(reply -> {
                var owner = reply.getSpec().getOwner();
                if (owner != null && owner.getName() != null
                    && owner.getName().startsWith(AI_PERSONA_OWNER_PREFIX)) {
                    return true;
                }
                if (owner != null && owner.getAnnotations() != null
                    && "true".equals(owner.getAnnotations().get(AI_MARKER_ANNOTATION))) {
                    return true;
                }
                return false;
            })
            .orElse(false);
    }

    private boolean isProcessed(Map<String, String> annotations) {
        return annotations != null && "true".equals(annotations.get(PROCESSED_ANNOTATION));
    }

    private void markProcessed(Reply reply) {
        var annotations = reply.getMetadata().getAnnotations();
        if (annotations == null) {
            annotations = new HashMap<>();
            reply.getMetadata().setAnnotations(annotations);
        }
        annotations.put(PROCESSED_ANNOTATION, "true");
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
            .extension(new Reply())
            .syncAllOnStart(false)
            .build();
    }
}
