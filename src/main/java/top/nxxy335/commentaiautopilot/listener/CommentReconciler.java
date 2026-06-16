package top.nxxy335.commentaiautopilot.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import top.nxxy335.commentaiautopilot.extension.AiCommentReply;
import top.nxxy335.commentaiautopilot.service.AiReplyOrchestrator;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
@RequiredArgsConstructor
public class CommentReconciler implements Reconciler<Reconciler.Request> {

    private final ExtensionClient client;
    private final AiReplyOrchestrator orchestrator;

    private static final String PROCESSED_ANNOTATION = "comment-ai-autopilot.nxxy335.top/processed";
    private static final String AI_MARKER_ANNOTATION = "comment-ai-autopilot.nxxy335.top/is-ai";
    private static final String AI_PERSONA_OWNER_PREFIX = "ai-persona-";
    private static final String AI_PERSONA_ANNOTATION = "comment-ai-autopilot.nxxy335.top/ai-persona";

    // Record the time when this bean was created (plugin startup time)
    private final Instant pluginStartTime = Instant.now();

    // In-memory dedup lock: prevents the same comment from being processed multiple times
    // even if reconcile is triggered concurrently
    private final ConcurrentHashMap<String, Boolean> processingLocks = new ConcurrentHashMap<>();

    @Override
    public Result reconcile(Request request) {
        var name = request.name();

        // Acquire lock at the very beginning to prevent any concurrent processing
        if (processingLocks.putIfAbsent(name, Boolean.TRUE) != null) {
            log.debug("[CommentReconciler] Already processing comment: {}, skipping", name);
            return Result.doNotRetry();
        }

        AtomicBoolean asyncStarted = new AtomicBoolean(false);
        try {
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
                String personaName = getPersonaNameFromComment(comment);

                // Top-level comment → always trigger AI reply
                log.info("[CommentReconciler] New top-level comment detected: {}, personaName: {}", name, personaName);
                asyncStarted.set(true);
                orchestrator.processComment(name, null, false, personaName)
                    .subscribeOn(Schedulers.boundedElastic())
                    .doFinally(signal -> {
                        processingLocks.remove(name);
                        log.debug("[CommentReconciler] Released processing lock for: {}", name);
                    })
                    .subscribe(
                        null,
                        e -> log.error("[CommentReconciler] Error processing comment {}: {}", name, e.getMessage(), e),
                        () -> log.info("[CommentReconciler] Processing completed for comment: {}", name)
                    );
            });
        } catch (Exception e) {
            log.error("[CommentReconciler] Error in reconcile for {}: {}", name, e.getMessage(), e);
        } finally {
            // Only release lock here if async processing was NOT started
            // (async path releases lock in doFinally)
            if (!asyncStarted.get()) {
                processingLocks.remove(name);
            }
        }

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

    /**
     * Read persona name from the post's annotations associated with this comment.
     */
    private String getPersonaNameFromComment(Comment comment) {
        var subjectRef = comment.getSpec().getSubjectRef();
        if (subjectRef == null || !"Post".equals(subjectRef.getKind())) {
            return null;
        }
        String postName = subjectRef.getName();
        return client.fetch(Post.class, postName)
            .map(post -> {
                // 1. 文章注解优先
                var annotations = post.getMetadata().getAnnotations();
                if (annotations != null) {
                    String persona = annotations.get(AI_PERSONA_ANNOTATION);
                    if (persona != null && !persona.isBlank()) {
                        return persona;
                    }
                }
                // 2. 分类注解
                var spec = post.getSpec();
                if (spec != null && spec.getCategories() != null) {
                    for (String categoryName : spec.getCategories()) {
                        var cat = client.fetch(Category.class, categoryName).orElse(null);
                        if (cat != null) {
                            var catAnnotations = cat.getMetadata().getAnnotations();
                            if (catAnnotations != null) {
                                String catPersona = catAnnotations.get(AI_PERSONA_ANNOTATION);
                                if (catPersona != null && !catPersona.isBlank()) {
                                    return catPersona;
                                }
                            }
                        }
                    }
                }
                // 3. 标签注解
                if (spec != null && spec.getTags() != null) {
                    for (String tagName : spec.getTags()) {
                        var tag = client.fetch(Tag.class, tagName).orElse(null);
                        if (tag != null) {
                            var tagAnnotations = tag.getMetadata().getAnnotations();
                            if (tagAnnotations != null) {
                                String tagPersona = tagAnnotations.get(AI_PERSONA_ANNOTATION);
                                if (tagPersona != null && !tagPersona.isBlank()) {
                                    return tagPersona;
                                }
                            }
                        }
                    }
                }
                return null;
            })
            .orElse(null);
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
