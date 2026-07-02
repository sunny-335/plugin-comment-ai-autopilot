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
import top.nxxy335.commentaiautopilot.service.WakeWordService;

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
    private final WakeWordService wakeWordService;

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

            // Check for wake word FIRST - wake word can bypass "must be reply to AI" check
            String replyContent = getReplyContent(reply);
            log.info("[ReplyReconciler] Wake word check for reply {}: content='{}'",
                name, replyContent.length() > 80 ? replyContent.substring(0, 80) + "..." : replyContent);
            var wakeMatch = wakeWordService.checkWakeWordBlocking(client, replyContent);

            // This reply quotes another reply - check if the quoted reply is from AI
            String quoteReply = reply.getSpec().getQuoteReply();
            boolean isReplyToAi = quoteReply != null && !quoteReply.isBlank() && isAiReply(quoteReply);
            log.debug("[ReplyReconciler] Reply {} quotes {}, isAiReply={}", name, quoteReply, isReplyToAi);

            // Skip if not a reply to AI AND no wake word matched
            if (!isReplyToAi && wakeMatch == null) {
                // No quoteReply or not replying to AI, and no wake word - skip
                log.debug("[ReplyReconciler] Not a reply to AI and no wake word, skipping: {}", name);
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

            if (wakeMatch != null) {
                // Wake word matched: trigger AI reply with the matched persona,
                // bypassing the "must be reply to AI" check and page-level enable check
                log.info("[ReplyReconciler] Wake word '{}' matched for persona '{}', triggering reply for: {}",
                    wakeMatch.wakeWord(), wakeMatch.personaName(), name);
                orchestrator.processComment(parentCommentName, name, true, wakeMatch.personaName(), true)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                        null,
                        e -> log.error("[ReplyReconciler] Error processing wake word reply {}: {}", name, e.getMessage(), e),
                        () -> log.info("[ReplyReconciler] Wake word processing completed for reply: {}", name)
                    );
            } else if (isReplyToAi) {
                // Normal flow: reply to AI → trigger AI reply (conversation continuation)
                String personaName = client.fetch(Comment.class, parentCommentName)
                    .map(comment -> personaResolver.getPersonaNameFromCommentBlocking(client, comment))
                    .orElse(null);
                log.info("[ReplyReconciler] Reply to AI detected: {}, triggering conversation, personaName: {}", name, personaName);
                orchestrator.processComment(parentCommentName, name, true, personaName, false)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                        null,
                        e -> log.error("[ReplyReconciler] Error processing reply {}: {}", name, e.getMessage(), e),
                        () -> log.info("[ReplyReconciler] Processing completed for reply: {}", name)
                    );
            }
        });

        return Result.doNotRetry();
    }

    /**
     * Check if a specific Reply is from AI persona.
     */
    private boolean isAiReply(String replyName) {
        return client.fetch(Reply.class, replyName)
            .map(reply -> {
                var spec = reply.getSpec();
                if (spec == null) return false;
                var owner = spec.getOwner();
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

    private String getReplyContent(Reply reply) {
        if (reply.getSpec() == null) return "";
        // Always strip HTML to get plain text for wake word matching
        String raw = reply.getSpec().getRaw();
        if (raw != null && !raw.isBlank()) {
            // raw might still contain HTML in some cases, always strip
            String plain = org.jsoup.Jsoup.clean(raw, org.jsoup.safety.Safelist.none()).trim();
            if (!plain.isBlank()) return plain;
        }
        String content = reply.getSpec().getContent();
        if (content != null && !content.isBlank()) {
            return org.jsoup.Jsoup.clean(content, org.jsoup.safety.Safelist.none()).trim();
        }
        return "";
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
            .extension(new Reply())
            .syncAllOnStart(false)
            .build();
    }
}
